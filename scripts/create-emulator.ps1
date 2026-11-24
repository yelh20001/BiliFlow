$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$env:ANDROID_HOME = "$project\.tools\android-sdk"
$env:ANDROID_AVD_HOME = "$project\.tools\avd"
$env:JAVA_HOME = (Get-ChildItem "$project\.tools\jdk" -Directory | Select-Object -First 1).FullName
$env:DEBUG = ''
New-Item -ItemType Directory -Force $env:ANDROID_AVD_HOME | Out-Null
$existing = Join-Path $env:ANDROID_AVD_HOME 'BiliFlow_API_35.avd'
if (Test-Path $existing) { Write-Host 'BiliFlow_API_35 already exists.'; exit 0 }
'no' | & "$env:ANDROID_HOME\cmdline-tools\latest\bin\avdmanager.bat" create avd --name BiliFlow_API_35 --package 'system-images;android-35;google_apis;x86_64' --device pixel_6
if ($LASTEXITCODE -ne 0) { throw 'Emulator creation failed.' }
$config = Join-Path $existing 'config.ini'
$overrides = @{
    'hw.lcd.width' = '720'; 'hw.lcd.height' = '1600'; 'hw.lcd.density' = '320'
    'hw.ramSize' = '3072'; 'disk.dataPartition.size' = '4G'; 'hw.keyboard' = 'yes'
    'showDeviceFrame' = 'no'; 'hw.gpu.enabled' = 'yes'; 'hw.gpu.mode' = 'swiftshader'
}
$lines = Get-Content -LiteralPath $config
foreach ($key in $overrides.Keys) {
    $lines = @($lines | Where-Object { $_ -notmatch "^$([regex]::Escape($key))\s*=" })
    $lines += "$key=$($overrides[$key])"
}
Set-Content -LiteralPath $config -Value $lines -Encoding ASCII
Write-Host 'Created BiliFlow_API_35 (Android 15, 720 x 1600).'
