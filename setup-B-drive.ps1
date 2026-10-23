#Requires -Version 5.1
<#
.SYNOPSIS
    把「懸浮翻譯」專案部署到 B 槽，並把 Android 開發環境的設定準備好。

.DESCRIPTION
    為什麼需要這支腳本自己動手跑？
    因為 DSH（AI 助理）的檔案沙箱只允許它寫入工作目錄（C 槽的 OneDrive 裡），
    寫 B 槽會被系統拒絕。這支腳本請你在「自己的」PowerShell 裡執行，
    就沒有這個限制。

    它會做四件事：
      1. 建立 B 槽的開發目錄結構
      2. 把專案複製過去（排除 build／.gradle／.idea 等暫存檔）
      3. 準備 Gradle wrapper（Android Studio 與命令列建置都需要它）
      4. 設定使用者環境變數，讓 SDK、Gradle 快取、AVD 全部放到 B 槽

.EXAMPLE
    # 直接用預設值
    .\setup-B-drive.ps1

.EXAMPLE
    # 只想複製專案，不要改環境變數
    .\setup-B-drive.ps1 -SkipEnvVars

.NOTES
    如果 PowerShell 擋下執行，請改用：
        powershell -ExecutionPolicy Bypass -File .\setup-B-drive.ps1
#>

[CmdletBinding()]
param(
    [string] $TargetRoot     = 'B:\AndroidProjects',
    [string] $ProjectName    = 'OverlayTranslator',
    [string] $AndroidRoot    = 'B:\Android',
    [string] $GradleVersion  = '9.6.0',
    [switch] $SkipEnvVars,
    [switch] $SkipProjectCopy
)

$ErrorActionPreference = 'Continue'

function Write-Step  ([string]$m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Write-Ok    ([string]$m) { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Write-Warn2 ([string]$m) { Write-Host "  [注意] $m" -ForegroundColor Yellow }
function Write-Err2  ([string]$m) { Write-Host "  [失敗] $m" -ForegroundColor Red }

$source = $PSScriptRoot
$target = Join-Path $TargetRoot $ProjectName

Write-Host "懸浮翻譯 — B 槽部署腳本" -ForegroundColor Magenta
Write-Host "來源：$source"
Write-Host "目標：$target"

# ---------------------------------------------------------------------------
Write-Step '1/5 檢查來源與目標磁碟'
# ---------------------------------------------------------------------------
if (-not (Test-Path (Join-Path $source 'settings.gradle.kts'))) {
    Write-Err2 "找不到 settings.gradle.kts。請把這支腳本放在專案根目錄再執行。"
    exit 1
}
Write-Ok "來源專案看起來正常"

if (-not (Test-Path 'B:\')) {
    Write-Err2 '找不到 B 槽。'
    exit 1
}
$drive = Get-PSDrive -Name 'B' -ErrorAction SilentlyContinue
if ($drive) {
    Write-Ok ("B 槽可用空間：{0:N1} GB" -f ($drive.Free / 1GB))
}

# ---------------------------------------------------------------------------
Write-Step '2/5 建立 B 槽開發目錄'
# ---------------------------------------------------------------------------
$dirs = @(
    $TargetRoot,
    $AndroidRoot,
    (Join-Path $AndroidRoot 'Sdk'),
    (Join-Path $AndroidRoot 'gradle-home'),
    (Join-Path $AndroidRoot 'avd'),
    (Join-Path $AndroidRoot 'tools')
)
foreach ($d in $dirs) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
    Write-Ok $d
}

# ---------------------------------------------------------------------------
Write-Step '3/5 複製專案到 B 槽'
# ---------------------------------------------------------------------------
if ($SkipProjectCopy) {
    Write-Warn2 '已指定 -SkipProjectCopy，略過複製'
} else {
    if (-not (Test-Path $target)) { New-Item -ItemType Directory -Force -Path $target | Out-Null }

    $robocopyArgs = @(
        $source, $target,
        '/E',
        '/XD', 'build', '.gradle', '.idea', 'captures', '.kotlin',
        '/XF', 'local.properties', '*.iml',
        '/NFL', '/NDL', '/NJH', '/NJS', '/NP',
        '/R:1', '/W:1'
    )
    & robocopy @robocopyArgs | Out-Null
    $rc = $LASTEXITCODE
    # robocopy 的 0~7 都算成功
    if ($rc -ge 8) {
        Write-Err2 "robocopy 失敗（代碼 $rc）"
        exit 1
    }
    Write-Ok "已複製到 $target（robocopy 代碼 $rc）"
}

# ---------------------------------------------------------------------------
Write-Step '4/5 準備 Gradle wrapper'
# ---------------------------------------------------------------------------
$wrapperJar   = Join-Path $target 'gradle\wrapper\gradle-wrapper.jar'
$gradlewBat   = Join-Path $target 'gradlew.bat'
$needJar      = -not (Test-Path $wrapperJar)
$needScripts  = -not (Test-Path $gradlewBat)

if (-not $needJar -and -not $needScripts) {
    Write-Ok 'wrapper 已經齊全，跳過'
} else {
    # --- 快路：只缺 jar，就從官方 GitHub 抓那個 43KB 的檔案 -----------------
    if ($needJar -and -not $needScripts) {
        $url = "https://raw.githubusercontent.com/gradle/gradle/v$GradleVersion/gradle/wrapper/gradle-wrapper.jar"
        Write-Host "  嘗試下載 gradle-wrapper.jar：$url"
        try {
            $old = $ProgressPreference
            $ProgressPreference = 'SilentlyContinue'
            Invoke-WebRequest -Uri $url -OutFile $wrapperJar -UseBasicParsing -TimeoutSec 60
            $ProgressPreference = $old
            if ((Get-Item $wrapperJar).Length -gt 10000) {
                Write-Ok 'gradle-wrapper.jar 下載完成'
                $needJar = $false
            } else {
                Write-Warn2 '下載到的檔案太小，可能不是 jar'
                Remove-Item $wrapperJar -Force -ErrorAction SilentlyContinue
            }
        } catch {
            Write-Warn2 "快路下載失敗：$($_.Exception.Message)"
        }
    }

    # --- 穩路：抓完整 Gradle 發行版，用它產生整套 wrapper -------------------
    if ($needJar -or $needScripts) {
        Write-Host '  改用完整 Gradle 發行版來產生 wrapper（約 130MB，會存到 B 槽）'
        $zip = Join-Path $AndroidRoot "tools\gradle-$GradleVersion-bin.zip"
        $url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"

        if (-not (Test-Path $zip)) {
            try {
                Write-Host "  下載 $url"
                $old = $ProgressPreference
                $ProgressPreference = 'SilentlyContinue'
                Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing -TimeoutSec 1800
                $ProgressPreference = $old
                Write-Ok '下載完成'
            } catch {
                Write-Err2 "下載 Gradle 失敗：$($_.Exception.Message)"
                Write-Warn2 '可以改用 Android Studio 開啟專案，讓它自己處理 Gradle wrapper。'
                exit 1
            }
        } else {
            Write-Ok '已存在下載好的 Gradle 壓縮檔，直接使用'
        }

        $extractDir = Join-Path $AndroidRoot 'tools'
        $gradleHome = Join-Path $extractDir "gradle-$GradleVersion"
        if (-not (Test-Path $gradleHome)) {
            Write-Host '  解壓縮中…'
            Expand-Archive -Path $zip -DestinationPath $extractDir -Force
        }
        $gradleBat = Join-Path $gradleHome 'bin\gradle.bat'
        if (-not (Test-Path $gradleBat)) {
            Write-Err2 "找不到 $gradleBat"
            exit 1
        }

        Write-Host '  在專案裡執行 gradle wrapper…'
        Push-Location $target
        try {
            & $gradleBat wrapper --gradle-version $GradleVersion --distribution-type bin
            $rc = $LASTEXITCODE
        } finally {
            Pop-Location
        }
        if ($rc -ne 0) {
            Write-Err2 "gradle wrapper 失敗（代碼 $rc）"
            exit 1
        }
        Write-Ok 'wrapper 已產生'
    }
}

# ---------------------------------------------------------------------------
Write-Step '5/5 設定環境變數（使用者層級）'
# ---------------------------------------------------------------------------
if ($SkipEnvVars) {
    Write-Warn2 '已指定 -SkipEnvVars，略過'
} else {
    $vars = [ordered]@{
        'ANDROID_SDK_ROOT' = (Join-Path $AndroidRoot 'Sdk')
        'ANDROID_HOME'     = (Join-Path $AndroidRoot 'Sdk')
        'GRADLE_USER_HOME' = (Join-Path $AndroidRoot 'gradle-home')
        'ANDROID_AVD_HOME' = (Join-Path $AndroidRoot 'avd')
    }
    foreach ($k in $vars.Keys) {
        [Environment]::SetEnvironmentVariable($k, $vars[$k], 'User')
        Set-Item -Path "env:$k" -Value $vars[$k]
        Write-Ok "$k = $($vars[$k])"
    }
    Write-Warn2 '環境變數要重開終端機（或重開機）才會在別的地方生效。'
}

# ---------------------------------------------------------------------------
Write-Host "`n=== 完成 ===" -ForegroundColor Cyan
Write-Host @"
接下來請這樣做：

1. 安裝 Android Studio（如果還沒裝）
   官方下載：https://developer.android.com/studio
   安裝時可以選自訂路徑，例如 B:\Android\Android Studio

2. 第一次啟動 Android Studio 後，到：
   Settings → Languages & Frameworks → Android SDK
   把 "Android SDK Location" 改成：$AndroidRoot\Sdk
   然後在 SDK Platforms 勾選 Android 15 (API 35) 與最新的 API 37
   在 SDK Tools 勾選 Android SDK Build-Tools 36.0.0 與 Android SDK Platform-Tools

3. 用 Android Studio 開啟這個資料夾：
   $target

4. 等 Gradle Sync 跑完，按綠色的 ▶ Run 就會裝到你的裝置上。

專案位置：$target
"@ -ForegroundColor Gray
