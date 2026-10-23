#Requires -Version 5.1
<#
.SYNOPSIS
    安裝 APK 到裝置，並自動重新啟用無障礙服務。

.DESCRIPTION
    為什麼需要這支腳本？

    Android 有一個安全設計：**每次重新安裝 App，系統都會清掉它的無障礙服務**
    （避免開發者透過更新偷偷取得權限）。所以每次 `adb install -r` 之後，
    無障礙服務都會變回關閉，必須再開一次。

    更麻煩的是：側載 App 在 Android 13+ 的設定裡可能被「受限制的設定」擋住，
    而且部分 ROM（例如 Lenovo）根本沒有那個解鎖選項，使用者會完全卡住。

    這支腳本用 adb 直接寫入 secure settings，繞過 UI 限制。實測在
    Lenovo TB322FC / Android 16 上有效且穩定。

.EXAMPLE
    # 用專案裡最新的 APK，裝好並自動啟用無障礙
    .\deploy.ps1

.EXAMPLE
    # 裝完順便開啟 App
    .\deploy.ps1 -Launch

.EXAMPLE
    # 只重新啟用無障礙，不重裝
    .\deploy.ps1 -SkipInstall

.EXAMPLE
    # 指定裝置（同時接多台時）
    .\deploy.ps1 -Device <your-device-serial>
#>

[CmdletBinding()]
param(
    [string] $Device,
    [string] $Apk,
    [string] $Package = 'com.dsh.overlaytranslator',
    [string] $ServiceClass = 'a11y.ChatAccessibilityService',
    [string] $Adb = 'B:\Android\Sdk\platform-tools\adb.exe',
    [switch] $SkipInstall,
    [switch] $SkipAccessibility,
    [switch] $Launch
)

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'

function Write-Step ([string]$m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Write-Ok   ([string]$m) { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Write-Note ([string]$m) { Write-Host "  [..]   $m" -ForegroundColor Gray }
function Write-Warn2([string]$m) { Write-Host "  [注意] $m" -ForegroundColor Yellow }
function Write-Fail ([string]$m) { Write-Host "  [失敗] $m" -ForegroundColor Red }

if (-not (Test-Path $Adb)) {
    Write-Fail "找不到 adb：$Adb"
    Write-Note '請確認 Android SDK platform-tools 已安裝，或用 -Adb 指定路徑。'
    exit 1
}

# ---------------------------------------------------------------------------
Write-Step '1/5 選擇裝置'
# ---------------------------------------------------------------------------
if (-not $Device) {
    $lines = & $Adb devices 2>&1 | Where-Object { $_ -match '\tdevice$' }
    $serials = @($lines | ForEach-Object { ($_ -split '\s+')[0] })
    if ($serials.Count -eq 0) {
        Write-Fail '沒有偵測到任何已授權的裝置。'
        Write-Note '請確認：① USB 已插好 ② 已開 USB 偵錯 ③ 裝置上已按「允許」'
        & $Adb devices -l 2>&1 | ForEach-Object { Write-Note $_ }
        exit 1
    }
    if ($serials.Count -gt 1) {
        Write-Host '  偵測到多台裝置，請選擇：' -ForegroundColor Gray
        for ($i = 0; $i -lt $serials.Count; $i++) {
            $m = ((& $Adb -s $serials[$i] shell getprop ro.product.model 2>&1) | Out-String).Trim()
            $v = ((& $Adb -s $serials[$i] shell getprop ro.build.version.release 2>&1) | Out-String).Trim()
            Write-Host "    [$($i + 1)] $($serials[$i])  →  $m / Android $v" -ForegroundColor Gray
        }
        $answer = Read-Host '  輸入編號（直接按 Enter 選 1，或用 -Device 參數跳過這個提示）'
        $index = 0
        if ($answer -match '^\d+$') {
            $n = [int]$answer
            if ($n -ge 1 -and $n -le $serials.Count) { $index = $n - 1 }
        }
        $Device = $serials[$index]
        Write-Ok "已選擇 $Device"
    } else {
        $Device = $serials[0]
    }
}

$modelName = ((& $Adb -s $Device shell getprop ro.product.model 2>&1) | Out-String).Trim()
$androidVer = ((& $Adb -s $Device shell getprop ro.build.version.release 2>&1) | Out-String).Trim()
$sdkVer = ((& $Adb -s $Device shell getprop ro.build.version.sdk 2>&1) | Out-String).Trim()
Write-Ok "$Device  →  $modelName / Android $androidVer (API $sdkVer)"

# ---------------------------------------------------------------------------
Write-Step '2/5 安裝 APK'
# ---------------------------------------------------------------------------
if ($SkipInstall) {
    Write-Warn2 '已指定 -SkipInstall，略過安裝'
} else {
    if (-not $Apk) {
        $candidate = Join-Path $PSScriptRoot 'artifacts\app-debug.apk'
        if (Test-Path $candidate) {
            $Apk = $candidate
        } else {
            $candidate2 = 'B:\AndroidProjects\OverlayTranslator\app\build\outputs\apk\debug\app-debug.apk'
            if (Test-Path $candidate2) { $Apk = $candidate2 }
        }
    }
    if (-not $Apk -or -not (Test-Path $Apk)) {
        Write-Fail '找不到 APK。請先執行 build-apk.ps1，或用 -Apk 指定路徑。'
        exit 1
    }
    $sizeMb = [math]::Round((Get-Item $Apk).Length / 1MB, 2)
    Write-Note "安裝 $Apk（$sizeMb MB）"

    # --user 0 很重要：不指定時 adb 會裝到所有使用者，
    # 有 App Clone / 雙開的 ROM 就會在啟動時跳出「Select」選單要你選分身。
    $installOut = & $Adb -s $Device install -r --user 0 $Apk 2>&1
    if ($installOut -match 'Success') {
        Write-Ok '安裝成功'
    } else {
        Write-Fail "安裝失敗：$installOut"
        exit 1
    }
}

# ---------------------------------------------------------------------------
Write-Step '3/5 重新啟用無障礙服務'
# ---------------------------------------------------------------------------
$component = "$Package/$Package.$ServiceClass"

if ($SkipAccessibility) {
    Write-Warn2 '已指定 -SkipAccessibility，略過'
} else {
    # 關鍵一步：側載 App 的「受限制的設定」在底層就是這個 appop。
    # 實測（Lenovo TB322FC / Android 16）：若它是 default，ROM 會在使用者不知情時
    # 撤銷無障礙服務 —— 服務先正常運作幾分鐘，然後突然消失，logcat 只留下
    #   enabled_accessibility_services = null
    # 而且這台機器的 App 資訊頁根本沒有「允許受限制的設定」那一項可以點。
    # 用 appops 直接設成 allow 之後就穩定了（實測 60 秒以上不再被撤銷）。
    & $Adb -s $Device shell appops set $Package ACCESS_RESTRICTED_SETTINGS allow 2>&1 | Out-Null
    $restricted = ((& $Adb -s $Device shell appops get $Package ACCESS_RESTRICTED_SETTINGS 2>&1) | Out-String).Trim()
    Write-Ok "ACCESS_RESTRICTED_SETTINGS → $restricted"

    # 先清空再設回去，強制系統 unbind → rebind，等於真的重啟服務
    & $Adb -s $Device shell settings put secure enabled_accessibility_services '""' 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    & $Adb -s $Device shell settings put secure enabled_accessibility_services $component 2>&1 | Out-Null
    & $Adb -s $Device shell settings put secure accessibility_enabled 1 2>&1 | Out-Null
    Start-Sleep -Seconds 1
    # 有些 ROM 會把 accessibility_enabled 覆寫掉，再寫一次
    & $Adb -s $Device shell settings put secure accessibility_enabled 1 2>&1 | Out-Null
    Start-Sleep -Seconds 4

    $now = ((& $Adb -s $Device shell settings get secure enabled_accessibility_services 2>&1) | Out-String).Trim()
    $enabled = ((& $Adb -s $Device shell settings get secure accessibility_enabled 2>&1) | Out-String).Trim()

    if ($now -like "*$Package*") {
        Write-Ok "已啟用：$component"
        Write-Note "accessibility_enabled = $enabled"
    } else {
        Write-Warn2 "看起來沒有生效，目前值 = [$now]"
        Write-Note '如果這個 ROM 會撤銷 adb 寫入，請改用手動：'
        Write-Note '  設定 → 無障礙 → 已下載的服務 → 開啟本 App'
        Write-Note '  若開關是灰的，先找 App 資訊頁 → 右上角 ⋮ →「允許受限制的設定」'
        Write-Note '  （小米 / HyperOS 一定有這一項；部分 Lenovo ROM 沒有）'
    }
}

# ---------------------------------------------------------------------------
Write-Step '4/5 檢查懸浮窗服務'
# ---------------------------------------------------------------------------
$win = (& $Adb -s $Device shell dumpsys window windows 2>&1 | Select-String "u0 $Package\}" | Select-Object -First 1)
if ($win) {
    Write-Ok '懸浮窗正在運作'
} else {
    Write-Warn2 '懸浮窗沒有在運作（重裝 APK 會殺掉 App 行程，這是正常的）'
    Write-Note '請在 App 主畫面按「啟動懸浮窗」'
}

# ---------------------------------------------------------------------------
Write-Step '5/5 完成'
# ---------------------------------------------------------------------------
if ($Launch) {
    & $Adb -s $Device shell am start --user 0 -n "$Package/.ui.MainActivity" 2>&1 | Out-Null
    Write-Ok '已開啟 App'
}

Write-Host ''
Write-Host '接下來：' -ForegroundColor Gray
Write-Host '  1. 如果懸浮窗沒出現，在 App 主畫面按「啟動懸浮窗」' -ForegroundColor Gray
Write-Host '  2. 示範對話：主畫面往下捲 →「開啟示範對話」（左上角有「‹ 返回」）' -ForegroundColor Gray
Write-Host '  3. 真實翻譯要先填 API Key：設定 → API Key（頁面裡有取得步驟）' -ForegroundColor Gray
