param([switch]$Elevated)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    if ($Elevated) { throw 'Administrator access was not granted.' }
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $PSCommandPath + '"'), '-Elevated')
    $process = Start-Process powershell.exe -ArgumentList $arguments -Verb RunAs -WindowStyle Hidden -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "Driver installation failed. See .tools\hypervisor-install.log." }
    exit 0
}
$driver = "$project\.tools\android-sdk\extras\google\Android_Emulator_Hypervisor_Driver"
$log = "$project\.tools\hypervisor-install.log"
try {
    if ((Get-Service aehd -ErrorAction SilentlyContinue).Status -eq 'Running') {
        'AEHD is already running.' | Set-Content $log
        exit 0
    }
    if (-not (Test-Path "$driver\silent_install.bat")) { throw 'Official AEHD package is not installed in this SDK.' }
    Push-Location $driver
    try { & "$driver\silent_install.bat" *> $log } finally { Pop-Location }
    $service = Get-Service aehd -ErrorAction Stop
    if ($service.Status -ne 'Running') { throw 'AEHD did not start. No Windows security features have been disabled.' }
    'AEHD installed and running.' | Add-Content $log
} catch {
    $_ | Out-String | Add-Content $log
    exit 1
}
