param([switch]$Visible, [switch]$SoftwareAcceleration)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$env:ANDROID_HOME = "$project\.tools\android-sdk"
$env:ANDROID_AVD_HOME = "$project\.tools\avd"
$emulator = "$env:ANDROID_HOME\emulator\emulator.exe"
if (Test-Path "$project\.tools\emulator35\emulator\emulator.exe") { $emulator = "$project\.tools\emulator35\emulator\emulator.exe" }
if (-not (Test-Path $emulator)) { throw 'Install the Android emulator first; see README.' }
$emulatorArgs = @('-avd', 'BiliFlow_API_35', '-no-snapshot', '-no-boot-anim', '-no-audio', '-crash-report-mode', 'disabled', '-gpu', 'swiftshader', '-memory', '3072', '-cores', '4')
if ($emulator -like '*emulator35*') {
    $emulatorArgs = @($emulatorArgs | Where-Object { $_ -notin @('-crash-report-mode', 'disabled') } | ForEach-Object { if ($_ -eq 'swiftshader') { 'swiftshader_indirect' } else { $_ } })
}
if (-not $Visible) { $emulatorArgs += '-no-window' }
if ($SoftwareAcceleration) {
    $coreIndex = [Array]::IndexOf($emulatorArgs, '-cores')
    $emulatorArgs[$coreIndex + 1] = '1'
    $emulatorArgs += @('-accel', 'off', '-feature', '-Vulkan', '-show-kernel')
}
$windowStyle = if ($Visible) { 'Normal' } else { 'Hidden' }
Start-Process -FilePath $emulator -ArgumentList $emulatorArgs -WindowStyle $windowStyle -RedirectStandardOutput "$project\.tools\emulator.log" -RedirectStandardError "$project\.tools\emulator-error.log"
Write-Host 'Emulator starting. Check readiness with adb shell getprop sys.boot_completed.'
