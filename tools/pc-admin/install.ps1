<#
  Installs "Curbox for PC" for the current Windows user (no admin rights needed):
  copies the app to %LOCALAPPDATA%\Programs, adds Desktop and Start menu
  shortcuts, and starts it.

  Used by "Curbox for PC.exe" (the one file to share), by "Install Curbox for PC.cmd" in the
  portable zip, and by build-windows.ps1 -Install.

  -Source   folder that holds "Curbox for PC.exe". When left out, the script
            looks for a "Curbox for PC" folder or an app.zip next to itself.
#>
param([string]$Source)

$ErrorActionPreference = 'Stop'
$AppName = 'Curbox for PC'
$Here = $PSScriptRoot
$Target = Join-Path $env:LOCALAPPDATA "Programs\$AppName"
$Temp = $null

if (-not $Source) {
    $Source = Join-Path $Here $AppName
    if (-not (Test-Path (Join-Path $Source "$AppName.exe"))) {
        $Temp = Join-Path $env:TEMP ('curbox-for-pc-' + [guid]::NewGuid())
        Expand-Archive -Path (Join-Path $Here 'app.zip') -DestinationPath $Temp -Force
        $Source = Join-Path $Temp $AppName
    }
}
if (-not (Test-Path (Join-Path $Source "$AppName.exe"))) { throw "No $AppName.exe in $Source" }

# A running copy locks its files, so stop it before replacing them.
Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and $_.Path -like "$Target\*" } |
    Stop-Process -Force
Start-Sleep -Milliseconds 700

if (Test-Path $Target) { Remove-Item $Target -Recurse -Force }
New-Item -ItemType Directory -Force (Split-Path $Target) | Out-Null
Copy-Item $Source $Target -Recurse
if ($Temp) { Remove-Item $Temp -Recurse -Force -ErrorAction SilentlyContinue }

$Exe = Join-Path $Target "$AppName.exe"
$Shell = New-Object -ComObject WScript.Shell
foreach ($place in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
    $link = $Shell.CreateShortcut((Join-Path $place "$AppName.lnk"))
    $link.TargetPath = $Exe
    $link.WorkingDirectory = $Target
    $link.IconLocation = "$Exe,0"
    $link.Description = 'Manage Curbox phones from this computer'
    $link.Save()
}

Write-Host "Installed: $Exe"
Start-Process $Exe
