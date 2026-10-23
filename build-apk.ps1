#Requires -Version 5.1
<#
.SYNOPSIS
    懸浮翻譯：一鍵安裝工具鏈 → 同步專案到 B 槽 → 編譯出 APK。

.DESCRIPTION
    這支腳本設計成**可以重複執行**：每一步都會先檢查是否已經完成，已存在就跳過。
    所以第一次跑很久（要下載約 1GB），之後每次改程式碼再跑就只花編譯的時間。

    它會處理：
      1. 可攜版 JDK 17（Temurin，解壓到 B 槽，不需管理員權限）
      2. Android SDK command-line tools
      3. SDK 套件：platform-tools、platforms;android-37、build-tools;36.0.0
      4. Gradle 9.6.0 發行版
      5. 產生 gradlew（專案需要它）
      6. 同步專案到 B:\AndroidProjects\OverlayTranslator（避開 OneDrive）
      7. 在 B 槽編譯 app-debug.apk，並複製一份回專案的 artifacts\ 目錄

.EXAMPLE
    .\build-apk.ps1

.EXAMPLE
    # 工具鏈已裝好，只想重新編譯
    .\build-apk.ps1

.EXAMPLE
    # 只裝工具鏈，先不編譯
    .\build-apk.ps1 -SkipBuild
#>

[CmdletBinding()]
param(
    [string] $AndroidRoot   = 'B:\Android',
    [string] $TargetRoot    = 'B:\AndroidProjects',
    [string] $ProjectName   = 'OverlayTranslator',
    [string] $JdkBuild      = '17',
    [string] $GradleVersion = '9.6.0',
    [string] $PlatformPkg   = 'platforms;android-37.0',
    [string] $BuildToolsPkg = 'build-tools;37.0.0',
    [switch] $SkipToolchain,
    [switch] $SkipBuild
)

$ProgressPreference    = 'SilentlyContinue'

# 重要：這裡刻意用 'Continue' 而不是 'Stop'。
# 在 Windows PowerShell 5.1 底下，原生命令（java、gradle、sdkmanager、robocopy）
# 寫到 stderr 的訊息會被包成 ErrorRecord；若 ErrorActionPreference 是 'Stop'，
# 光是 `java -version` 的正常輸出就會讓整個腳本中斷。
# 需要「失敗就跳 catch」的地方，改用明確的 -ErrorAction Stop。
$ErrorActionPreference = 'Continue'

function Write-Step ([string]$m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Write-Ok   ([string]$m) { Write-Host "  [OK]   $m" -ForegroundColor Green }
function Write-Note ([string]$m) { Write-Host "  [..]   $m" -ForegroundColor Gray }
function Write-Warn2([string]$m) { Write-Host "  [注意] $m" -ForegroundColor Yellow }
function Write-Fail ([string]$m) { Write-Host "  [失敗] $m" -ForegroundColor Red }

function Expand-ZipFast {
    param([string]$Zip, [string]$Dest)
    if (-not (Test-Path $Dest)) { New-Item -ItemType Directory -Force -Path $Dest | Out-Null }
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    try {
        [System.IO.Compression.ZipFile]::ExtractToDirectory($Zip, $Dest)
    } catch {
        Write-Note "改用 Expand-Archive（$($_.Exception.Message)）"
        Expand-Archive -Path $Zip -DestinationPath $Dest -Force
    }
}

function Get-File {
    param([string]$Url, [string]$OutFile, [string]$Label)
    if ((Test-Path $OutFile) -and ((Get-Item $OutFile).Length -gt 1000)) {
        Write-Ok "$Label 已存在，跳過下載"
        return $true
    }
    Write-Note "下載 $Label …"
    Write-Note "  $Url"
    try {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing -TimeoutSec 3600 -ErrorAction Stop
        $sw.Stop()
        $mb = [math]::Round((Get-Item $OutFile).Length / 1MB, 1)
        Write-Ok "$Label 下載完成（$mb MB，$([math]::Round($sw.Elapsed.TotalSeconds,0)) 秒）"
        return $true
    } catch {
        Write-Fail "$Label 下載失敗：$($_.Exception.Message)"
        Remove-Item $OutFile -Force -ErrorAction SilentlyContinue
        return $false
    }
}

$source = $PSScriptRoot
$target = Join-Path $TargetRoot $ProjectName
$tools  = Join-Path $AndroidRoot 'tools'
$sdk    = Join-Path $AndroidRoot 'Sdk'
$ghome  = Join-Path $AndroidRoot 'gradle-home'
$jdk    = Join-Path $tools 'jdk17'
$gdist  = Join-Path $tools "gradle-$GradleVersion"

Write-Host "懸浮翻譯 — 建置腳本" -ForegroundColor Magenta
Write-Host "專案來源：$source"
Write-Host "建置位置：$target"

if (-not (Test-Path (Join-Path $source 'settings.gradle.kts'))) {
    Write-Fail "找不到 settings.gradle.kts，請把腳本放在專案根目錄。"
    exit 1
}

# ===========================================================================
Write-Step '步驟 0／7　建立目錄'
# ===========================================================================
foreach ($d in @($AndroidRoot, $tools, $sdk, $ghome, $TargetRoot)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }
    Write-Ok $d
}

# ===========================================================================
if (-not $SkipToolchain) {
    Write-Step '步驟 1／7　安裝 JDK 17'

    if (Test-Path (Join-Path $jdk 'bin\java.exe')) {
        Write-Ok "JDK 已存在：$jdk"
    } else {
        $zip = Join-Path $tools 'temurin17.zip'
        $url = "https://api.adoptium.net/v3/binary/latest/$JdkBuild/ga/windows/x64/jdk/hotspot/normal/eclipse"
        if (Get-File -Url $url -OutFile $zip -Label 'Temurin JDK 17') {
            Write-Note '解壓縮中…'
            $tmp = Join-Path $tools '_jdk_tmp'
            if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
            Expand-ZipFast -Zip $zip -Dest $tmp

            # Adoptium 的 zip 會有一層 jdk-17.0.x+y 目錄
            $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
            if ($null -eq $inner) { Write-Fail '解壓後找不到 JDK 目錄'; exit 1 }
            if (Test-Path $jdk) { Remove-Item $jdk -Recurse -Force }
            Move-Item $inner.FullName $jdk
            Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
            Write-Ok "JDK 就緒：$jdk"
        } else {
            Write-Fail 'JDK 下載失敗，無法繼續。'
            exit 1
        }
    }

    $env:JAVA_HOME = $jdk
    $env:PATH = (Join-Path $jdk 'bin') + ';' + $env:PATH
    $javaVersion = (& (Join-Path $jdk 'bin\java.exe') -version 2>&1 | Out-String).Trim() -split "`r?`n" | Select-Object -First 1
    Write-Ok "java -version → $javaVersion"

    # =======================================================================
    Write-Step '步驟 2／7　安裝 Android command-line tools'

    $sdkManager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (Test-Path $sdkManager) {
        Write-Ok "command-line tools 已存在"
    } else {
        $zip = Join-Path $tools 'cmdline-tools.zip'
        $downloaded = $false

        if (-not ((Test-Path $zip) -and ((Get-Item $zip).Length -gt 1000000))) {
            # 先從官方下載頁抓目前版本的檔名（版本號會變，不能寫死）
            $candidates = New-Object System.Collections.Generic.List[string]
            try {
                $page = Invoke-WebRequest -Uri 'https://developer.android.com/studio?hl=en' -UseBasicParsing -TimeoutSec 60 -ErrorAction Stop
                $matches = [regex]::Matches($page.Content, 'commandlinetools-win-(\d+)_latest\.zip')
                foreach ($m in $matches) {
                    $u = "https://dl.google.com/android/repository/commandlinetools-win-$($m.Groups[1].Value)_latest.zip"
                    if (-not $candidates.Contains($u)) { $candidates.Add($u) }
                }
                Write-Note "從下載頁找到 $($candidates.Count) 個候選版本"
            } catch {
                Write-Warn2 "無法讀取下載頁：$($_.Exception.Message)"
            }
            foreach ($n in @('14742923', '13114758', '12266719', '11076708')) {
                $u = "https://dl.google.com/android/repository/commandlinetools-win-${n}_latest.zip"
                if (-not $candidates.Contains($u)) { $candidates.Add($u) }
            }

            foreach ($u in $candidates) {
                if (Get-File -Url $u -OutFile $zip -Label 'command-line tools') {
                    $downloaded = $true
                    break
                }
            }
        } else {
            $downloaded = $true
            Write-Ok '已有下載好的 command-line tools 壓縮檔'
        }

        if (-not $downloaded) {
            Write-Fail 'command-line tools 下載失敗，無法繼續。'
            exit 1
        }

        Write-Note '解壓縮中…'
        $tmp = Join-Path $tools '_cmdline_tmp'
        if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
        Expand-ZipFast -Zip $zip -Dest $tmp

        $inner = Join-Path $tmp 'cmdline-tools'
        if (-not (Test-Path $inner)) {
            $inner = (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName
        }
        $destCmdline = Join-Path $sdk 'cmdline-tools'
        if (-not (Test-Path $destCmdline)) { New-Item -ItemType Directory -Force -Path $destCmdline | Out-Null }
        $latest = Join-Path $destCmdline 'latest'
        if (Test-Path $latest) { Remove-Item $latest -Recurse -Force }
        Move-Item $inner $latest
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        Write-Ok "command-line tools 就緒"
    }

    # =======================================================================
    Write-Step '步驟 3／7　安裝 SDK 套件'

    $env:ANDROID_SDK_ROOT = $sdk
    $env:ANDROID_HOME     = $sdk

    if (-not (Test-Path $sdkManager)) { Write-Fail "找不到 sdkmanager：$sdkManager"; exit 1 }

    Write-Note '接受授權條款…'
    $yes = ("y`r`n" * 300)
    $yes | & $sdkManager --sdk_root=$sdk --licenses 2>&1 | Out-Null
    Write-Ok '授權條款已處理'

    Write-Note "安裝 platform-tools、$PlatformPkg、$BuildToolsPkg（可能要幾分鐘）…"
    # 注意：sdkmanager 只要有一個套件名稱不存在，就會整個中止、什麼都不裝。
    # 所以這裡的套件名稱必須完全正確（例如已經沒有 platforms;android-37，
    # 現在是 platforms;android-37.0 這種帶次版本號的命名）。
    # build-tools 同時裝 36 與 37，因為 AGP 的預設版本可能落在任一邊。
    & $sdkManager --sdk_root=$sdk 'platform-tools' $PlatformPkg $BuildToolsPkg 'build-tools;36.0.0' 2>&1 |
        ForEach-Object { if ($_ -match 'Warning|Error|error|Failed') { Write-Note $_ } }
    if ($LASTEXITCODE -ne 0) {
        Write-Warn2 "sdkmanager 回傳代碼 $LASTEXITCODE。若缺少 $PlatformPkg，請確認該 API 是否已發布。"
    }
    Write-Ok 'SDK 套件安裝流程結束'

    # =======================================================================
    Write-Step '步驟 4／7　安裝 Gradle'

    if (Test-Path (Join-Path $gdist 'bin\gradle.bat')) {
        Write-Ok "Gradle 已存在：$gdist"
    } else {
        $zip = Join-Path $tools "gradle-$GradleVersion-bin.zip"
        $url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
        if (Get-File -Url $url -OutFile $zip -Label "Gradle $GradleVersion") {
            Write-Note '解壓縮中…'
            Expand-ZipFast -Zip $zip -Dest $tools
            if (-not (Test-Path (Join-Path $gdist 'bin\gradle.bat'))) {
                Write-Fail "解壓後找不到 $gdist\bin\gradle.bat"
                exit 1
            }
            Write-Ok "Gradle 就緒"
        } else {
            Write-Fail 'Gradle 下載失敗，無法繼續。'
            exit 1
        }
    }

    # =======================================================================
    Write-Step '步驟 5／7　產生 gradlew'

    $env:GRADLE_USER_HOME = $ghome

    if ((Test-Path (Join-Path $source 'gradlew.bat')) -and
        (Test-Path (Join-Path $source 'gradle\wrapper\gradle-wrapper.jar'))) {
        Write-Ok 'gradlew 已存在，跳過'
    } else {
        Push-Location $source
        try {
            & (Join-Path $gdist 'bin\gradle.bat') wrapper --gradle-version $GradleVersion --distribution-type bin 2>&1 |
                ForEach-Object { Write-Note $_ }
        } finally { Pop-Location }
        if (Test-Path (Join-Path $source 'gradlew.bat')) {
            Write-Ok 'gradlew 已產生'
        } else {
            Write-Warn2 'gradlew 沒有產生成功，但下面會直接用 Gradle 發行版編譯，影響不大。'
        }
    }
} else {
    Write-Warn2 '已指定 -SkipToolchain，跳過工具鏈安裝'
    $env:JAVA_HOME       = $jdk
    $env:PATH            = (Join-Path $jdk 'bin') + ';' + $env:PATH
    $env:ANDROID_SDK_ROOT = $sdk
    $env:ANDROID_HOME     = $sdk
    $env:GRADLE_USER_HOME = $ghome
}

# ===========================================================================
Write-Step '步驟 6／7　同步專案到 B 槽'
# ===========================================================================
if (-not (Test-Path $target)) { New-Item -ItemType Directory -Force -Path $target | Out-Null }

& robocopy $source $target /E `
    /XD build .gradle .idea captures .kotlin artifacts `
    /XF local.properties *.iml `
    /NFL /NDL /NJH /NJS /NP /R:1 /W:1 | Out-Null
$rc = $LASTEXITCODE
if ($rc -ge 8) { Write-Fail "robocopy 失敗（代碼 $rc）"; exit 1 }
Write-Ok "已同步（robocopy 代碼 $rc）"

# local.properties 讓 Gradle 知道 SDK 在哪（這個檔案不進版控，所以每次重寫）
$lp = Join-Path $target 'local.properties'
"sdk.dir=$($sdk -replace '\\','/')" | Set-Content -Path $lp -Encoding ASCII
Write-Ok "已寫入 local.properties → sdk.dir=$($sdk -replace '\\','/')"

if ($SkipBuild) {
    Write-Warn2 '已指定 -SkipBuild，停在這裡。'
    exit 0
}

# ===========================================================================
Write-Step '步驟 7／7　編譯 APK'
# ===========================================================================
Push-Location $target
try {
    Write-Note 'gradle assembleDebug …（第一次會下載依賴，可能要好幾分鐘）'
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    & (Join-Path $gdist 'bin\gradle.bat') assembleDebug --console=plain 2>&1 |
        ForEach-Object { Write-Host "    $_" }
    $buildExit = $LASTEXITCODE
    $sw.Stop()
    Write-Note "編譯耗時 $([math]::Round($sw.Elapsed.TotalSeconds,0)) 秒，結束代碼 $buildExit"
} finally {
    Pop-Location
}

$apk = Join-Path $target 'app\build\outputs\apk\debug\app-debug.apk'

if ($buildExit -ne 0 -or -not (Test-Path $apk)) {
    Write-Fail '編譯失敗。請看上面 Gradle 的錯誤訊息。'
    exit 1
}

$size = [math]::Round((Get-Item $apk).Length / 1MB, 2)
Write-Ok "APK 產生成功：$apk（$size MB）"

# 複製一份回專案的 artifacts\，方便檢視與安裝
$artifactDir = Join-Path $source 'artifacts'
if (-not (Test-Path $artifactDir)) { New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null }
Copy-Item $apk (Join-Path $artifactDir 'app-debug.apk') -Force
Write-Ok "已複製到 $(Join-Path $artifactDir 'app-debug.apk')"

Write-Host "`n完成。安裝指令：" -ForegroundColor Cyan
Write-Host "  adb install -r `"$apk`"" -ForegroundColor Gray
