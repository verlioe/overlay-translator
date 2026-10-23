package com.dsh.overlaytranslator.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.dsh.overlaytranslator.core.AppLog

/**
 * 把無障礙節點樹壓縮成「可能是聊天內容」的幾行文字。
 *
 * ## 為什麼是啟發式而不是精準解析
 *
 * 每個聊天 App 的畫面結構都不同，而且會改版。想要精準抓「訊息泡泡」就得針對
 * WhatsApp / LINE / Telegram 各寫一套資源 ID 對應表，改版就壞。
 * 這裡改用通用做法：廣度優先走訪整棵樹，把「有文字又不是輸入框」的節點全部收集起來，
 * 再用黑名單濾掉介面零件（工具列、按鈕、時間戳、傳送鍵），最後只留畫面上最下面的幾行
 * ——聊天軟體的新訊息一定在下面。
 *
 * 這個做法不完美，所以懸浮窗上另外提供了「最近擷取」清單讓使用者自己點選正確的那一行，
 * 而不是假裝自動判斷一定對。
 */
object ScreenTextExtractor {

    /** 走訪節點數上限。無障礙回呼跑在主執行緒，走太多節點會 ANR。 */
    private const val MAX_NODES = 1400

    private const val MAX_DEPTH = 40

    /** 留給使用者挑的候選行數。 */
    private const val MAX_CANDIDATES = 12

    private const val MAX_TEXT_LENGTH = 4000

    /**
     * 輸入框上方保留多少 px 當「介面區」。
     *
     * 聊天 App 輸入框那一列（含左右兩側的相機／圖片／附件圖示）不該被當成訊息 ——
     * Messenger 那排「傳圖片／附件」就是這樣被翻出來的。這裡是純物件、沒有 Context，
     * 所以用固定 px；寧可少擋一點，也不要往上吃掉真正的訊息。
     */
    private const val COMPOSER_BAND_MARGIN_PX = 48

    /**
     * 訊息節點裡「時間」的樣子（用在 [dotEdgeFor] 判斷哪一行是時間列）。
     * 這不是拿來濾掉訊息，只是拿來把「訊息本文」與「時間＋讀取狀態」分開。
     */
    private val TIME_IN_TEXT = Regex("\\d{1,2}:\\d{2}")

    /**
     * 資源 ID 或類別名稱含有這些字串的，幾乎都是介面零件而不是聊天內容。
     * 這是黑名單，寧可漏殺也不要錯殺使用者真正的訊息。
     */
    private val CHROME_ID_HINTS = listOf(
        "toolbar", "action_bar", "actionbar", "appbar", "app_bar", "title", "tab_", "tabs",
        "button", "btn_", "_btn", "send", "emoji", "smiley", "attach", "menu", "search",
        "header", "footer", "avatar", "icon", "badge", "nav_", "status_bar", "tooltip",
        "banner", "input", "composer", "edit_", "_edit", "hint", "unread", "divider",
        "separator", "progress", "checkbox", "switch", "spinner", "seekbar", "recycler",
        "list_view", "scrollview", "viewpager",
    )

    /** 時間、日期之類的系統文字。 */
    private val CHROME_TEXT_PATTERNS = listOf(
        Regex("^\\d{1,2}:\\d{2}(\\s?[APap]\\.?[Mm]\\.?)?$"),
        Regex("^\\d{1,2}/\\d{1,2}(/\\d{2,4})?$"),
        Regex("^(昨天|今天|前天|明天|剛剛|上午|下午|晚上|中午)\\s*\\d{0,2}:?\\d{0,2}$"),
        Regex("^([APap]\\.?[Mm]\\.?)$"),
        Regex("^週?[一二三四五六日天]$"),
        Regex("^\\d{1,2}\\s*(分鐘前|小時前|天前|秒前)$"),
        // 英文的日期分隔線（Messenger 用「THU AT 21:18」「TODAY AT 12:34」）
        Regex("(?i)^(sun|mon|tue|wed|thu|fri|sat)[a-z]*\\s+(at\\s+)?\\d{1,2}:\\d{2}(\\s?[APap]\\.?[Mm]\\.?)?$"),
        Regex("(?i)^(today|yesterday)\\s+(at\\s+)?\\d{1,2}:\\d{2}(\\s?[APap]\\.?[Mm]\\.?)?$"),
        Regex("^(週|星期|禮拜)[一二三四五六日天]\\s*\\d{1,2}:\\d{2}$"),
        // 只有日期沒有時間的分隔線（Messenger：「October 15」；TG：「15 October 2026」「2026.10.15」）
        Regex("(?i)^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}(,?\\s+\\d{4})?$"),
        Regex("(?i)^\\d{1,2}\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?(\\s+\\d{4})?$"),
        Regex("^\\d{1,2}[./-]\\d{1,2}([./-]\\d{2,4})?$"),
        Regex("^\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}\\.?$"),
        Regex("^\\d{4}年\\d{1,2}月\\d{1,2}日$"),
        Regex("^\\d{1,2}月\\d{1,2}日$"),
        Regex("(?i)^(today|yesterday|昨天|今天|前天|明天)$"),
        // 英文的「相對時間」狀態列（Messenger：「Delivered 10 minutes ago」「Sent 2 hours ago」）
        Regex("(?i)^(delivered|sent|read|seen|received|sending)?\\s*" +
            "\\d+\\s*(sec|secs|second|seconds|min|mins|minute|minutes|" +
            "hr|hrs|hour|hours|day|days|week|weeks|month|months|year|years)\\s+ago$"),
        Regex("(?i)^(just|right)\\s+now$"),
        // 中文／日文的「已送達 10 分鐘前」這種帶前綴的相對時間
        Regex("^(已送達|已送出|已讀|送達|送出)?\\s*\\d+\\s*(秒前|分鐘前|小時前|天前|週前|個月前)$"),
        Regex("(?i)^(monday|tuesday|wednesday|thursday|friday|saturday|sunday)$"),
        Regex("^(週|星期|禮拜)[一二三四五六日天]$"),
    )

    /**
     * 回條／狀態類的系統文字。**整行**符合才算，避免誤殺真正的訊息。
     *
     * 為什麼需要這份清單？實機回報：Telegram 的「已讀」被當成一則訊息翻出來，
     * 小球也就跟著跑到那兩個字旁邊 —— 使用者看到的是「小球位置不對」，
     * 但真正的問題是那兩個字根本不是任何人的發言。這些字都是 App 自己畫的狀態，一律不翻。
     */
    private val STATUS_TEXT_PATTERNS = listOf(
        Regex(
            "^(已讀|已读|已讀取|已送達|已送达|已送出|已傳送|已传送|已發送|" +
                "傳送中|传送中|送出中|發送中|发送中|傳送失敗|发送失败|已封鎖|已封锁|" +
                "輸入中|输入中|正在輸入|正在输入|上線中|在线|最後上線|最后上线)$",
        ),
        Regex(
            "^(Seen|Read|Delivered|Sent|Sending|Typing|Typing\\.{3}|Online|Unread|" +
                "Last seen recently|Last seen|Active now)$",
            RegexOption.IGNORE_CASE,
        ),
        // 「已讀 12:34」或「12:34 已讀」這種把狀態跟時間併在一起的寫法
        Regex("^(已讀|已读|Seen|Read|Delivered|Sent)\\s+\\d{1,2}:\\d{2}(\\s?[APap]\\.?[Mm]\\.?)?$"),
        Regex("^\\d{1,2}:\\d{2}(\\s?[APap]\\.?[Mm]\\.?)?\\s+(已讀|已读|Seen|Read)$"),
        // 送達記號（勾勾、點點）
        Regex("^[✓✔]{1,2}$"),
        Regex("^[•·‧]{2,}$"),
    )

    /**
     * 系統／無障礙的提示語（**只看開頭**）。
     *
     * 為什麼需要？實機回報：Facebook Messenger 會把「Double tap it to …」這種字串
     * 放進節點的 contentDescription，而我們在沒有 text 時會退而採用 contentDescription
     * （見 traverse），於是就翻出一句沒人要看的系統說明。
     *
     * ⚠️ 一定要**錨定在開頭**：提示語都是整句從頭開始的（"Double tap to …"、"Tap to retry"），
     * 而真正的訊息可能**剛好含有**這些字（"I'll tap to pay"）。用「包含」比對會誤殺真訊息
     * —— 這是實際 dump 過 Messenger 之後改的。
     */
    private val HINT_PREFIX_PATTERNS = listOf(
        Regex("(?i)^\\s*double[\\s-]?tap\\b"),
        Regex("(?i)^\\s*tap\\s+(it\\s+)?to\\b"),
        Regex("(?i)^\\s*touch\\s+to\\b"),
        Regex("(?i)^\\s*tap\\s+here\\b"),
        Regex("(?i)^\\s*long[\\s-]?press\\b"),
        Regex("(?i)^\\s*swipe\\s+(up|down|left|right|to)\\b"),
        Regex("^\\s*(雙擊|輕觸|點兩下|連點|長按|按一下|滑動)"),
    )

    /**
     * 只有在**很短**的字串裡才用「包含」比對的提示語。
     *
     * 「double tap」這種詞組出現在訊息中間時，通常是短的操作說明（例如「Failed. Double tap…」）；
     * 長度限制是為了不要誤殺長訊息。
     */
    private val HINT_CONTAINS_PATTERNS = listOf(
        Regex("(?i)\\bdouble[\\s-]?tap\\b"),
        Regex("(?i)\\btap\\s+it\\s+to\\b"),
    )

    /** 任何位置出現都算提示語的樣式（用在 stripUiNoise：把黏在訊息後面的說明剪掉）。 */
    private val HINT_ANYWHERE = listOf(
        Regex("(?i)double[\\s-]?tap"),
        Regex("雙擊"),
        Regex("輕觸兩下"),
        Regex("連點兩下"),
        Regex("點兩下"),
        Regex("長按"),
        Regex("按住"),
    )

    /**
     * 「時間＋狀態」那一行（TG 會把它跟訊息放在同一個節點裡，用換行分隔）：
     * `Sent at 15:30, Seen`、`Received at 15:29`、`已讀 15:30`。
     * 這一行的內容是系統資訊，不該被當成訊息翻出來（實機回報「TG 把日期也翻譯了」）。
     */
    private val TIME_LINE = Regex(
        "(?i)^(received|sent|delivered|read|已讀|已读|已送達|已送达|傳送|发送)?\\s*" +
            "(at\\s*)?\\d{1,2}:\\d{2}(\\s*[APap]\\.?[Mm]\\.?)?(\\s*[,，].*)?$"
    )

    /** 看起來像「App 自己加在訊息前面的名字」（例：Alice）。 */
    private val SENDER_PREFIX = Regex("^[\\p{L}\\p{N}_.\\- ]{1,24}\$")

    /** 「很短的提示語」的長度上限。 */
    private const val HINT_SHORT_MAX_LENGTH = 60

    fun extract(
        root: AccessibilityNodeInfo?,
        packageName: String,
        topChromeBottom: Int = 0,
    ): ScreenSnapshot {
        val capturedAt = System.currentTimeMillis()
        if (root == null) {
            return ScreenSnapshot(packageName, capturedAt, emptyList(), null)
        }
        return try {
            traverse(root, packageName, capturedAt, topChromeBottom)
        } catch (t: Throwable) {
            // 節點樹可能在同一瞬間被系統回收，這時放棄這一輪就好，下一輪事件會再來。
            AppLog.e("ScreenTextExtractor", "走訪節點樹失敗（通常是畫面剛好更新）", t)
            ScreenSnapshot(packageName, capturedAt, emptyList(), null)
        }
    }

    private fun traverse(
        root: AccessibilityNodeInfo,
        packageName: String,
        capturedAt: Long,
        topChromeBottom: Int,
    ): ScreenSnapshot {
        val rawLines = ArrayList<CapturedLine>(64)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val depths = ArrayDeque<Int>()
        queue.addLast(root)
        depths.addLast(0)

        var enqueued = 1
        var visited = 0
        var composerText: String? = null
        var composerIsFocused = false
        var composerBounds: Bounds? = null

        // 重複使用同一個 Rect，避免在最多 1400 次的迴圈裡產生 1400 個物件
        val screenRect = Rect()
        // 走訪時就要用到畫面寬度（判斷圓點要貼哪一側），所以先量一次。
        val screenRightEarly = boundsOf(root, screenRect).right

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            val depth = depths.removeFirst()
            visited++
            if (depth > MAX_DEPTH) continue

            val className = node.className?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val editable = node.isEditable || className.contains("EditText", ignoreCase = true)

            if (editable) {
                // 輸入框：優先採用「有焦點」的那一個（畫面上可能同時有多個輸入框）
                //
                // 注意 hintText：實測發現在真實裝置上，空輸入框的 text 會等於提示文字
                // （示範對話的「在這裡打字…」被讀成 16 字的輸入內容）。
                // 那不是使用者打的字，必須排除，否則「翻譯我的輸入」會去翻提示文字。
                val hint = node.hintText?.toString()?.trim().orEmpty()
                val typedText = if (hint.isNotEmpty() && text == hint) "" else text

                if (typedText.isNotEmpty()) {
                    val focused = node.isFocused
                    if (composerText == null || focused || !composerIsFocused) {
                        composerText = typedText
                        composerIsFocused = focused
                        composerBounds = boundsOf(node, screenRect)
                    }
                }
            } else {
                // 有 text 就用 text，沒有才退而用 contentDescription
                val candidate = text.ifEmpty { description }
                if (candidate.isNotEmpty()) {
                    val cleaned = stripUiNoise(candidate)
                    if (cleaned.isEmpty()) continue
                    val lineBounds = boundsOf(node, screenRect)
                    // 排除介面零件。實測發現只靠資源 ID 黑名單不夠：
                    // 「返回」「示範對話」這種標題，以及底部的「模擬對方傳來訊息」等按鈕
                    // 都會被當成聊天內容翻出來，既沒用又擋住操作。
                    //
                    // 判準（與 App 無關，所以不會因為改版失效）：
                    //   1. 類別是 Button（含 ImageButton）＝控制項，不是訊息
                    //   2. **可點擊、而且自己沒有 text（只有 contentDescription）**＝圖示或按鈕
                    //   3. 畫面最上方那條「標題列」區域裡的東西＝工具列
                    //   4. 輸入框那一列（含左右工具列）以下的東西＝介面（在後面的過濾做）
                    //
                    // 為什麼是「可點擊**且沒有 text**」而不是「可點擊」？
                    //   - Messenger 這種 Compose 寫的聊天 App，**訊息文字節點本身就帶 clickable**
                    //     → 只看 clickable 會把真正的訊息全部濾掉，只剩日期分隔線（實機回報
                    //     「Messenger 都讀錯，沒讀到訊息」）。
                    //   - 但完全不看 clickable 又會把 Telegram 標題列的**通話／視訊圖示**
                    //     （只有 contentDescription、沒有 text）收進來，圓點就跑到標題列上
                    //     （實機回報「圓點位置不對」）。
                    //   → 兩者的差別剛好就是「有沒有自己的 text」。
                    val isIconOrButton = className.contains("Button", true) ||
                        (node.isClickable && text.isEmpty())
                    val inTopChrome = lineBounds.top < topChromeBottom
                    if (!isIconOrButton && !inTopChrome) {
                        rawLines.add(
                            CapturedLine(
                                text = cleaned,
                                viewId = viewId,
                                order = visited,
                                bounds = lineBounds,
                                // 方向一定要用**原始**文字判斷：stripUiNoise 會把
                                // 「Sent at 15:30, Seen」那一行砍掉，而那正是方向的依據。
                                dotEdge = dotEdgeFor(
                                    CapturedLine(text = candidate, bounds = lineBounds),
                                    screenRightEarly,
                                ),
                            )
                        )
                    }
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (enqueued >= MAX_NODES) break
                val child = node.getChild(i) ?: continue
                queue.addLast(child)
                depths.addLast(depth + 1)
                enqueued++
            }
        }

        val filtered = ArrayList<CapturedLine>(MAX_CANDIDATES)
        // 同一段文字常常同時掛在「整列」與「文字本身」兩個節點上（Telegram 就是這樣）。
        // 以前保留先遇到的那個（＝外層整列，寬度幾乎是整個畫面），
        // 圓點就會被算到畫面邊緣、全部疊成一排（實機回報的「TG 小球位置不對」）。
        // 所以這裡改成**保留面積最小的那個**（＝最貼合文字的那個節點）。
        val byText = LinkedHashMap<String, CapturedLine>()
        // 輸入框那一列（含左右兩側的工具列圖示）以下都算介面零件：
        // Messenger 的「傳圖片／附件」那排按鈕就在那裡，以前會被當成訊息翻出來。
        val composerTop = composerBounds?.top
        val composerBottom = composerBounds?.bottom
        val screenRectNow = boundsOf(root, screenRect)
        val screenBottom = screenRectNow.bottom
        val screenRight = screenRectNow.right
        val cutBelowComposer = composerTop != null &&
            composerTop > screenBottom / 2 &&
            composerTop > 0
        for (line in rawLines) {
            if (isChrome(line)) continue
            if (line.text == composerText) continue
            if (cutBelowComposer) {
                val top = composerTop!!
                // ① 從輸入框上緣（再往上留一點）開始的整條都是介面
                if (line.bounds.top >= top - COMPOSER_BAND_MARGIN_PX) continue
                // ② 往下延伸到輸入框底下（例如把整排包住的容器）也是介面
                if (composerBottom != null && line.bounds.bottom > composerBottom) continue
                // ③ 把整個輸入框包在裡面的節點＝容器，不是訊息
                if (composerBottom != null &&
                    line.bounds.top <= top && line.bounds.bottom >= composerBottom
                ) {
                    continue
                }
            }
            // dotEdge 已經在建立候選時用原始文字算好了（見上面），這裡只做文字比對。
            val adjusted = line
            val existing = byText[adjusted.text]
            // 取捨順序：① 有判斷出方向（dotEdge != AUTO）的優先 —— 那代表它是「整列」節點，
            // 方向資訊只存在於整列節點上；② 同分時才比面積（最貼合文字的那個）。
            val better = existing == null ||
                (existing.dotEdge == DotEdge.AUTO && adjusted.dotEdge != DotEdge.AUTO) ||
                (existing.dotEdge == adjusted.dotEdge &&
                    area(adjusted.bounds) < area(existing.bounds))
            if (better) {
                byText[adjusted.text] = adjusted
            }
        }
        filtered.addAll(byText.values)

        // ② Telegram 這種 App，真正的訊息**全部**都是「整列」節點（實測 1280 寬）。
        //    所以在有整列型節點的畫面上，其他窄的東西（置頂訊息橫幅、日期分隔線、
        //    「未讀訊息」分隔線）一律不是訊息 —— 直接全部丟掉。
        val wideRows = filtered.filter { it.bounds.width >= screenRight * 0.91f }
        if (wideRows.size >= 2) {
            filtered.removeAll { it.bounds.width < screenRight * 0.91f }
        }

        // ③ 同一列有時候會被拆成好幾個節點（例如圖片訊息裡還有子節點），
        //    每個節點都會長出一顆圓點。垂直範圍重疊超過 60% 就只留面積最大的那一個。
        val deduped = ArrayList<CapturedLine>(filtered.size)
        for (line in filtered.sortedByDescending { area(it.bounds) }) {
            val overlaps = deduped.any { other ->
                val overlap = minOf(line.bounds.bottom, other.bounds.bottom) -
                    maxOf(line.bounds.top, other.bounds.top)
                overlap > minOf(line.bounds.height, other.bounds.height) * 0.6f
            }
            if (!overlaps) deduped.add(line)
        }
        filtered.clear()
        filtered.addAll(deduped.sortedBy { it.bounds.top })

        val incoming = if (filtered.size > MAX_CANDIDATES) {
            filtered.subList(filtered.size - MAX_CANDIDATES, filtered.size).toList()
        } else {
            filtered.toList()
        }

        return ScreenSnapshot(packageName, capturedAt, incoming, composerText, composerBounds)
    }

    /**
     * 取得節點在**螢幕座標**上的位置。
     * 內嵌譯文模式靠這個把譯文貼在訊息旁邊，所以座標必須是螢幕座標而不是相對於父節點。
     * 重用傳進來的 Rect 以避免在走訪迴圈中不斷配置物件。
     */
    private fun boundsOf(node: AccessibilityNodeInfo, reusable: Rect): Bounds {
        return try {
            node.getBoundsInScreen(reusable)
            Bounds(reusable.left, reusable.top, reusable.right, reusable.bottom)
        } catch (t: Throwable) {
            // 節點可能已經失效，這時就當作沒有位置資訊
            Bounds.EMPTY
        }
    }

    /** 矩形面積。用來在「同一段文字掛在多個節點上」時挑最貼合文字的那一個。 */
    private fun area(bounds: Bounds): Long =
        bounds.width.toLong().coerceAtLeast(0L) * bounds.height.toLong().coerceAtLeast(0L)

    /**
     * 判斷這一行是不是「整列一個節點」的訊息，並決定圓點要貼哪一側。
     *
     * ## 為什麼不猜泡泡位置
     *
     * 曾經試過「用列高推字寬、用字數推泡泡寬度」來估計泡泡在哪裡，實機結果是：
     * 短訊息還可以，長一點的訊息就會把圓點算進泡泡裡、**壓在字上**
     * （實機症狀：「好像滿屌的」那顆圓點蓋住一個字）。使用者直接指定了簡單規則：
     *
     * > 我的訊息 → 圓點貼螢幕最左；對方的 → 貼最右。
     *
     * 這樣位置固定、可預期，而且**永遠不可能蓋到任何文字**。
     *
     * ## 怎麼知道是「我的」還是「對方的」
     *
     * TG 每一列的文字是兩行：第一行是訊息、第二行是「日期＋時間」，而**只有自己送出的訊息**
     * 會在時間後面多一段讀取狀態（樣式：`日期 時間, Seen` vs 對方的 `日期 時間`）。
     * 所以「時間後面還有沒有字」就是判斷法。
     *
     * 其他 App 的訊息節點本來就回報得很精準，這裡一律回 [DotEdge.AUTO] 不做任何事。
     */
    internal fun dotEdgeFor(line: CapturedLine, screenWidth: Int): DotEdge {
        if (screenWidth <= 0) return DotEdge.AUTO
        // 只處理「幾乎佔滿整個畫面寬度」的節點
        if (line.bounds.width < screenWidth * 0.91f) return DotEdge.AUTO
        val rows = line.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.size < 2) return DotEdge.AUTO
        val last = rows.last()
        val timeMatch = TIME_IN_TEXT.find(last) ?: return DotEdge.AUTO
        // 時間後面還有內容（讀取狀態）＝自己送的 → 圓點貼最左；對方 → 貼最右
        val mine = last.length > timeMatch.range.last + 1
        return if (mine) DotEdge.LEFT else DotEdge.RIGHT
    }

    /**
     * 把黏在訊息後面的**系統提示語**剪掉（不是整行丟掉）。
     *
     * 實機案例（Messenger）：一則訊息節點的文字是
     * `名字, 但GPT適合審計xDDDDDD, 雙擊可查看傳送/接收的日期和時間, 雙擊並按住可對訊息做出回應`
     * —— 訊息本身是真的，但後面被黏了兩段操作說明。
     * 以前的做法是「整行含提示語就丟掉」，結果連真訊息一起丟 ✗。
     *
     * 作法：用逗號／頓號／句號切成一節一節，把命中提示語樣式的節刪掉。
     * 如果有刪到東西，會**順便**把開頭的「名字, 」前綴也去掉 ——
     * 因為那代表這段文字是 App 自己組出來給讀屏用的標籤（名字＋訊息＋說明），
     * 不是使用者打的内容。
     *
     * @return 剪完的文字（可能與原本相同）
     */
    internal fun stripUiNoise(text: String): String {
        // ① 先砍掉最後一行的「時間＋狀態」列
        var working = text
        val rawRows = working.split('\n')
        if (rawRows.size >= 2 && TIME_LINE.matches(rawRows.last().trim())) {
            working = rawRows.dropLast(1).joinToString("\n").trim()
        }
        if (working.isEmpty()) return text
        if (!HINT_ANYWHERE.any { it.containsMatchIn(working) }) return working
        val text = working
        val parts = text.split(Regex("[,\\uFF0C\\u3001;\\uFF1B]"))
        if (parts.size < 2) return text
        val kept = parts.filterNot { part ->
            HINT_PREFIX_PATTERNS.any { it.containsMatchIn(part) } ||
                HINT_CONTAINS_PATTERNS.any { it.containsMatchIn(part) }
        }
        if (kept.isEmpty()) return text
        var result = kept.joinToString(", ").trim()
        // 開頭若剩「名字, 訊息」，把名字那一段拿掉
        val head = result.substringBefore(',', "")
        if (head.isNotBlank() && head.length <= 24 &&
            result.length > head.length + 2 && SENDER_PREFIX.matches(head.trim())
        ) {
            result = result.substringAfter(',').trim()
        }
        return result.ifEmpty { text }
    }

    internal fun isChrome(line: CapturedLine): Boolean {
        val text = line.text
        if (text.isEmpty()) return true
        if (text.length > MAX_TEXT_LENGTH) return true
        // 單一 ASCII 字元幾乎都是圖示或按鈕（+、×、›、1…），
        // 但單一 CJK 字或 emoji 有可能是真的訊息，所以只濾 ASCII。
        if (text.length == 1 && text[0].code < 0x0100) return true

        val id = line.viewId.lowercase()
        if (id.isNotEmpty() && CHROME_ID_HINTS.any { id.contains(it) }) return true

        if (CHROME_TEXT_PATTERNS.any { it.matches(text) }) return true

        // 回條／狀態（「已讀」這種 App 自己畫的狀態）與系統提示語（「Double tap to …」）
        if (STATUS_TEXT_PATTERNS.any { it.matches(text.trim()) }) return true
        if (HINT_PREFIX_PATTERNS.any { it.containsMatchIn(text) }) return true
        if (text.length <= HINT_SHORT_MAX_LENGTH &&
            HINT_CONTAINS_PATTERNS.any { it.containsMatchIn(text) }
        ) {
            return true
        }

        // 只有標點／裝飾符號、既沒有字也沒有 emoji 的（例如 "..."）：不是發言。
        // 注意：純數字要留著 —— 泰式笑聲「555」就是數字；emoji 單獨一則也是真的訊息。
        if (text.none { it.isLetterOrDigit() } && !looksLikeEmoji(text)) return true

        return false
    }

    /**
     * 粗略判斷有沒有 emoji（emoji 單獨一則是很常見的訊息，不能當成裝飾符號濾掉）。
     *
     * ⚠️ 必須逐**碼點**比對，不能逐 `Char`：絕大多數 emoji 在補充平面（例如 😊 = U+1F60A），
     * 在 UTF-16 裡是 surrogate pair，逐 Char 只會看到 0xD83D / 0xDE0A —— 兩個都不在
     * 0x1F000..0x1FAFF 裡，於是這個函式永遠回 false，emoji-only 訊息會被當裝飾濾掉。
     * （單元測試 `numbers and emoji only messages are not chrome` 抓到的真 bug。）
     */
    private fun looksLikeEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (
                c in 0x1F000..0x1FAFF || c in 0x2600..0x27BF || c in 0x2B00..0x2BFF ||
                c in 0x1F1E6..0x1F1FF || c == 0xFE0F || c == 0x200D
            ) {
                return true
            }
            i += Character.charCount(c)
        }
        return false
    }
}
