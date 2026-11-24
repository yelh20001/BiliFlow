param([switch]$SkipChecks, [switch]$Offline, [switch]$Release)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Push-Location $project
try {
    $bundledJdk = Get-ChildItem "$project\.tools\jdk" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($bundledJdk) { $env:JAVA_HOME = $bundledJdk.FullName }
    $env:DEBUG = ''
    $variant = if ($Release) { 'Release' } else { 'Debug' }
    $tasks = @(":app:assemble$variant")
    if (-not $SkipChecks) { $tasks += @(':app:testDebugUnitTest', ":app:lint$variant") }
    $gradle = "$project\gradlew.bat"
    $bundledGradle = "$project\.tools\gradle\gradle-8.11.1\bin\gradle.bat"
    if (Test-Path $bundledGradle) { $gradle = $bundledGradle }
    $buildArgs = @()
    if ($Offline) {
        $buildArgs += '--offline'
        if (Test-Path "$project\.tools\repositories.gradle") { $buildArgs += @('--init-script', "$project\.tools\repositories.gradle") }
    }
    & $gradle @buildArgs @tasks
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    New-Item -ItemType Directory -Force "$project\dist" | Out-Null
    $variantDir = $variant.ToLowerInvariant()
    $outputName = if ($Release) { 'BiliFlow.apk' } else { 'BiliFlow-debug.apk' }
    Copy-Item "$project\app\build\outputs\apk\$variantDir\app-$variantDir.apk" "$project\dist\$outputName"
    Write-Host "APK: $project\dist\$outputName"
} finally { Pop-Location }
