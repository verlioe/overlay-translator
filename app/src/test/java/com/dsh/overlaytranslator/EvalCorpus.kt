package com.dsh.overlaytranslator

import com.dsh.overlaytranslator.core.AppSettings
import com.dsh.overlaytranslator.translate.Direction

/**
 * 翻譯品質回歸測試用的固定語料。
 *
 * 這些案例故意挑「以前真的出過事」或「最容易被改壞」的組合：
 * 三種語氣、兩種方向、診斷、短句／口語／emoji／多語系。
 *
 * 提示詞由 `PromptSnapshotTest` 用**App 真正的** `DeepSeekEngine.systemPrompt()` 產生，
 * 輸出到 `app/build/eval/prompts.json`，再由 `tools/eval_prompts.py` 檢查
 * （`--check` 離線驗證契約，`--live` 才會真的打模型）。
 */
data class EvalCase(
    val id: String,
    val direction: Direction,
    val tone: String,
    val myLanguage: String,
    val theirLanguage: String,
    val text: String,
    /** 前面的對話（對方說的原文），用來驗證上下文有沒有被帶進去。 */
    val context: List<String> = emptyList(),
    /** 輸出應該是什麼文字系統（給 --live 檢查語言有沒有翻對）。 */
    val expectScript: String = "han",
)

object EvalCorpus {

    private fun theirs(
        id: String,
        tone: String,
        text: String,
        context: List<String> = emptyList(),
        myLanguage: String = "繁體中文",
        theirLanguage: String = "日本語",
        expectScript: String = "han",
    ) = EvalCase(
        id = id,
        direction = Direction.THEIR_TEXT_TO_ME,
        tone = tone,
        myLanguage = myLanguage,
        theirLanguage = theirLanguage,
        text = text,
        context = context,
        expectScript = expectScript,
    )

    private fun mine(
        id: String,
        tone: String,
        text: String,
        myLanguage: String = "繁體中文",
        theirLanguage: String = "日本語",
        expectScript: String = "latin",
    ) = EvalCase(
        id = id,
        direction = Direction.MY_TEXT_TO_THEM,
        tone = tone,
        myLanguage = myLanguage,
        theirLanguage = theirLanguage,
        text = text,
        expectScript = expectScript,
    )

    val cases: List<EvalCase> = listOf(
        // 對方訊息 → 我的語言
        theirs(
            id = "ja-casual-emoji",
            tone = "熟人閒聊",
            text = "お前ほんとバカだなwww😆",
        ),
        theirs(
            id = "ja-formal-request",
            tone = "正式禮貌",
            text = "恐れ入りますが、明日の会議の資料をご共有いただけますでしょうか。",
        ),
        theirs(
            id = "ja-context-pronoun",
            tone = "普通閒聊",
            text = "それでいいよ、任せる。",
            context = listOf(
                "来週の飲み会、どこにする？",
                "新しくできた駅前の店、どうかな",
            ),
        ),
        theirs(
            id = "th-short-555",
            tone = "熟人閒聊",
            text = "555 น่ารักดีนะ",
            theirLanguage = "泰文",
        ),
        theirs(
            id = "en-empty-ish",
            tone = "普通閒聊",
            text = "ok",
            theirLanguage = "英文",
        ),
        // 我的訊息 → 對方語言
        mine(
            id = "zh-to-ja-polite",
            tone = "正式禮貌",
            text = "不好意思打擾您，請問明天方便嗎？",
        ),
        mine(
            id = "zh-to-en-casual",
            tone = "熟人閒聊",
            text = "最好是啦，你少來 XD",
            theirLanguage = "英文",
        ),
        // 診斷（輸出必須有【診斷】【建議】【建議翻譯】三段，而且診斷／建議翻譯要用我的語言）
        EvalCase(
            id = "diagnose-casual-draft",
            direction = Direction.DIAGNOSE_MY_OUTGOING,
            tone = "正式禮貌",
            myLanguage = "繁體中文",
            theirLanguage = "日本語",
            text = "明日、ちょっと無理かも。ごめん！",
            expectScript = "han",
        ),
    )
}
