<#
.SYNOPSIS
    构建只包含 arm64-v8a 的签名 release APK，并复制到 dist/。

.DESCRIPTION
    versionCode / versionName 取自 app/build.gradle，脚本不改仓库文件。
    产物：dist/moememos-<versionName>-arm64-v8a.apk

    签名与工具链参数按以下顺序解析，先命中者生效：
      1. 脚本参数（-StoreFile 等）
      2. 环境变量（ANDROID_SIGNING_STORE_FILE / JAVA_HOME / ANDROID_HOME 等）
      3. scripts/signing.local.json（本地文件，已被 .gitignore 忽略）
    不要把真实密码写进本脚本或任何会被提交的文件。

.EXAMPLE
    .\scripts\build-release-arm64.ps1

.EXAMPLE
    .\scripts\build-release-arm64.ps1 -OutDir D:\apk
#>
[CmdletBinding()]
param(
    [string]$StoreFile = "",
    [string]$StorePassword = "",
    [string]$KeyAlias = "",
    [string]$KeyPassword = "",
    [string]$JavaHome = "",
    [string]$AndroidHome = "",
    [string]$OutDir = "",
    [string]$SigningConfig = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# PowerShell 5.1 默认按系统 OEM 代码页输出，重定向或捕获日志时中文会乱码。
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$TargetAbi = "arm64-v8a"

function Stop-WithError([string]$message) {
    Write-Host "错误：$message" -ForegroundColor Red
    exit 1
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $repoRoot "gradlew.bat"
$appBuildGradle = Join-Path $repoRoot "app\build.gradle"
$releaseOutputDir = Join-Path $repoRoot "app\build\outputs\apk\release"
$releaseApk = Join-Path $releaseOutputDir "app-release.apk"

if (-not $OutDir) {
    $OutDir = Join-Path $repoRoot "dist"
}
if (-not $SigningConfig) {
    $SigningConfig = Join-Path $PSScriptRoot "signing.local.json"
}

$localSigning = @{}
if (Test-Path -LiteralPath $SigningConfig) {
    try {
        $signingJson = Get-Content -LiteralPath $SigningConfig -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($property in $signingJson.PSObject.Properties) {
            $localSigning[$property.Name] = [string]$property.Value
        }
    }
    catch {
        Stop-WithError "无法解析签名配置 $SigningConfig：$($_.Exception.Message)"
    }
}

function Resolve-Setting([string]$value, [string]$envName, [string]$jsonKey) {
    if ($value) {
        return $value
    }
    if ($envName) {
        $fromEnv = [Environment]::GetEnvironmentVariable($envName)
        if ($fromEnv) {
            return $fromEnv
        }
    }
    if ($jsonKey -and $localSigning.ContainsKey($jsonKey)) {
        return $localSigning[$jsonKey]
    }
    return ""
}

$resolvedStoreFile = Resolve-Setting $StoreFile "ANDROID_SIGNING_STORE_FILE" "storeFile"
$resolvedStorePassword = Resolve-Setting $StorePassword "ANDROID_SIGNING_STORE_PASSWORD" "storePassword"
$resolvedKeyAlias = Resolve-Setting $KeyAlias "ANDROID_SIGNING_KEY_ALIAS" "keyAlias"
$resolvedKeyPassword = Resolve-Setting $KeyPassword "ANDROID_SIGNING_KEY_PASSWORD" "keyPassword"
$resolvedJavaHome = Resolve-Setting $JavaHome "JAVA_HOME" "javaHome"
$resolvedAndroidHome = Resolve-Setting $AndroidHome "ANDROID_HOME" "androidHome"

$missing = @()
if (-not $resolvedStoreFile) { $missing += "keystore 路径 (storeFile)" }
if (-not $resolvedStorePassword) { $missing += "storePassword" }
if (-not $resolvedKeyAlias) { $missing += "keyAlias" }
if (-not $resolvedKeyPassword) { $missing += "keyPassword" }
if (-not $resolvedJavaHome) { $missing += "JDK 路径 (javaHome)" }
if (-not $resolvedAndroidHome) { $missing += "Android SDK 路径 (androidHome)" }
if ($missing.Count -gt 0) {
    Stop-WithError @"
缺少打包配置：$($missing -join '、')
把 scripts/signing.local.example.json 复制成 scripts/signing.local.json 并填好，
或用同名参数 / 环境变量传入。该本地文件已被 .gitignore 忽略，不要提交。
"@
}

if (-not (Test-Path -LiteralPath $gradlew)) {
    Stop-WithError "找不到 $gradlew，请在仓库根目录下的 scripts/ 中运行本脚本。"
}
if (-not (Test-Path -LiteralPath $resolvedJavaHome)) {
    Stop-WithError "找不到 JavaHome：$resolvedJavaHome"
}
if (-not (Test-Path -LiteralPath $resolvedAndroidHome)) {
    Stop-WithError "找不到 AndroidHome：$resolvedAndroidHome"
}
if (-not (Test-Path -LiteralPath $resolvedStoreFile)) {
    Stop-WithError "找不到签名文件：$resolvedStoreFile"
}
if (-not (Test-Path -LiteralPath $appBuildGradle)) {
    Stop-WithError "找不到 $appBuildGradle"
}

$buildGradleText = Get-Content -LiteralPath $appBuildGradle -Raw -Encoding UTF8
$versionCodeMatch = [regex]::Match($buildGradleText, 'versionCode\s*=\s*(\d+)')
$versionNameMatch = [regex]::Match($buildGradleText, 'versionName\s*=\s*"([^"]+)"')
if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    Stop-WithError "无法从 app/build.gradle 解析 versionCode / versionName。"
}
$versionCode = $versionCodeMatch.Groups[1].Value
$versionName = $versionNameMatch.Groups[1].Value

$env:JAVA_HOME = $resolvedJavaHome
$env:ANDROID_HOME = $resolvedAndroidHome
$env:ANDROID_SIGNING_STORE_FILE = $resolvedStoreFile
$env:ANDROID_SIGNING_STORE_PASSWORD = $resolvedStorePassword
$env:ANDROID_SIGNING_KEY_ALIAS = $resolvedKeyAlias
$env:ANDROID_SIGNING_KEY_PASSWORD = $resolvedKeyPassword

Write-Host "MoeMemos $versionName ($versionCode) · $TargetAbi" -ForegroundColor Cyan
Write-Host "签名文件：$resolvedStoreFile"

# 上一次的产物会让“构建失败但文件仍在”看起来像成功。
if (Test-Path -LiteralPath $releaseOutputDir) {
    Remove-Item -LiteralPath $releaseOutputDir -Recurse -Force
}

# -PpreferSettingsRepos：本机全局 Gradle init 脚本会注入镜像仓库，项目的
# FAIL_ON_PROJECT_REPOS 会直接拒绝；默认构建行为不受影响。
$gradleArgs = @(
    ":app:assembleRelease",
    "-PabiFilter=$TargetAbi",
    "-PpreferSettingsRepos",
    "-Dorg.gradle.java.home=$resolvedJavaHome",
    "-Dorg.gradle.java.installations.auto-download=false",
    "--console=plain"
)

Write-Host "开始打包…" -ForegroundColor Cyan
& $gradlew @gradleArgs
if ($LASTEXITCODE -ne 0) {
    Stop-WithError "Gradle 构建失败（exit $LASTEXITCODE）。"
}

if (-not (Test-Path -LiteralPath $releaseApk)) {
    Stop-WithError "构建完成但未找到 APK：$releaseApk"
}

# 校验产物里确实只有目标 ABI：abiFilters 覆盖不到的老依赖或打包配置变化都在这里暴露。
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($releaseApk)
try {
    $abis = @(
        $zip.Entries |
            Where-Object { $_.FullName -like "lib/*/*" } |
            ForEach-Object { ($_.FullName -split "/")[1] } |
            Sort-Object -Unique
    )
}
finally {
    $zip.Dispose()
}

$unexpectedAbis = @($abis | Where-Object { $_ -ne $TargetAbi })
if ($unexpectedAbis.Count -gt 0) {
    Stop-WithError "APK 里出现了非目标 ABI：$($unexpectedAbis -join ', ')"
}
if ($abis.Count -eq 0) {
    Write-Host "提示：APK 不含 native 库，跳过 ABI 校验。" -ForegroundColor Yellow
}

if (-not (Test-Path -LiteralPath $OutDir)) {
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
}
$apkName = "moememos-$versionName-$TargetAbi.apk"
$apkPath = Join-Path $OutDir $apkName
Copy-Item -LiteralPath $releaseApk -Destination $apkPath -Force

$apkItem = Get-Item -LiteralPath $apkPath
$sizeMb = "{0:N1}" -f ($apkItem.Length / 1MB)
$sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()

Write-Host ""
Write-Host "打包完成" -ForegroundColor Green
Write-Host "  产物：$apkPath"
Write-Host "  版本：$versionName ($versionCode)"
Write-Host "  ABI ：$($abis -join ', ')"
Write-Host "  大小：$sizeMb MB"
Write-Host "  SHA256：$sha256"
