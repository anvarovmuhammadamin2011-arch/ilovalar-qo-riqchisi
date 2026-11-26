<#
  Builds "Curbox for PC", a standalone Windows app with its own Java runtime.

    .\build-windows.ps1            builds, in dist\:
                                     Curbox for PC\            the app folder
                                     Curbox for PC.exe         one file to share: installs and opens it
                                     Curbox for PC.zip         same app plus "Install Curbox for PC.cmd"
                                     MBSI PC V3.exe            installer window for other computers
    .\build-windows.ps1 -Install   also installs it here (see install.ps1)

  Needs a JDK 17 or newer (JAVA_HOME, or %USERPROFILE%\jdks\jdk-*).
#>
param([switch]$Install)

$ErrorActionPreference = 'Stop'
$AppName = 'Curbox for PC'
$Version = '3.0.0'
$Here = $PSScriptRoot

function Find-Jdk {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\jpackage.exe'))) { return $env:JAVA_HOME }
    $local = Get-ChildItem (Join-Path $env:USERPROFILE 'jdks') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'bin\jpackage.exe') } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($local) { return $local.FullName }
    throw 'No JDK with jpackage found. Install JDK 17+ or set JAVA_HOME.'
}

$Jdk = Find-Jdk
$Bin = Join-Path $Jdk 'bin'
$Build = Join-Path $Here 'build'
$Dist = Join-Path $Here 'dist'
$Classes = Join-Path $Build 'classes'
$JarDir = Join-Path $Build 'input'

Write-Host "JDK: $Jdk"
foreach ($dir in @($Build, (Join-Path $Dist $AppName))) {
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
}
New-Item -ItemType Directory -Force $Classes, (Join-Path $Classes 'web'), $JarDir, $Dist | Out-Null

Write-Host 'Compiling...'
& (Join-Path $Bin 'javac.exe') --release 17 -encoding UTF-8 -d $Classes (Join-Path $Here 'CurboxAdmin.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

# The page, logo and icon ship inside the jar under web/.
foreach ($file in @('admin.html', 'user.html', 'logo.png', 'favicon.ico')) {
    Copy-Item (Join-Path $Here $file) (Join-Path $Classes 'web') -Force
}

Write-Host 'Packing jar...'
& (Join-Path $Bin 'jar.exe') --create --file (Join-Path $JarDir 'curbox-for-pc.jar') --main-class CurboxAdmin -C $Classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

Write-Host 'Packaging Windows app (this takes a minute)...'
& (Join-Path $Bin 'jpackage.exe') `
    --type app-image `
    --name $AppName `
    --app-version $Version `
    --vendor 'MBSI' `
    --description 'Manage Curbox phones from this computer' `
    --icon (Join-Path $Here 'favicon.ico') `
    --input $JarDir `
    --main-jar 'curbox-for-pc.jar' `
    --main-class CurboxAdmin `
    --add-modules 'java.base,java.desktop,jdk.httpserver' `
    --jlink-options '--strip-debug --no-man-pages --no-header-files --compress=2' `
    --dest $Dist
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }

$AppDir = Join-Path $Dist $AppName
Write-Host "Built: $AppDir"

# ---- Files to share: one Setup.exe, and a zip for when an .exe is blocked ----

$Share = Join-Path $Build 'share'
New-Item -ItemType Directory -Force $Share | Out-Null
Copy-Item (Join-Path $Here 'install.ps1') $Share -Force

# app.zip carries the app folder inside the Setup.exe.
Compress-Archive -Path $AppDir -DestinationPath (Join-Path $Share 'app.zip') -Force

# No "setup" or "install" in the name: Windows would then ask for admin rights,
# because this IExpress exe has no manifest saying it runs as the user.
$Setup = Join-Path $Dist "$AppName.exe"
if (Test-Path $Setup) { Remove-Item $Setup -Force }
$Sed = Join-Path $Build 'setup.sed'
@"
[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=0
HideExtractAnimation=1
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=%InstallPrompt%
DisplayLicense=%DisplayLicense%
FinishMessage=%FinishMessage%
TargetName=%TargetName%
FriendlyName=%FriendlyName%
AppLaunched=%AppLaunched%
PostInstallCmd=%PostInstallCmd%
AdminQuietInstCmd=%AdminQuietInstCmd%
UserQuietInstCmd=%UserQuietInstCmd%
SourceFiles=SourceFiles
[Strings]
InstallPrompt=
DisplayLicense=
FinishMessage=
TargetName=$Setup
FriendlyName=$AppName
AppLaunched=powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File install.ps1
PostInstallCmd=<None>
AdminQuietInstCmd=
UserQuietInstCmd=
FILE0="install.ps1"
FILE1="app.zip"
[SourceFiles]
SourceFiles0=$Share\
[SourceFiles0]
%FILE0%=
%FILE1%=
"@ | Set-Content -Path $Sed -Encoding ASCII

Write-Host 'Making Setup.exe...'
# IExpress fails on a quoted SED path, so pass the short 8.3 form, which has no spaces.
$SedShort = (New-Object -ComObject Scripting.FileSystemObject).GetFile($Sed).ShortPath
$iexpress = Start-Process -FilePath "$env:WINDIR\System32\iexpress.exe" -ArgumentList '/N', '/Q', $SedShort -Wait -PassThru
if ($iexpress.ExitCode -ne 0 -or -not (Test-Path $Setup)) { throw 'IExpress failed to make Setup.exe' }
Write-Host "Setup: $Setup"

# The zip holds the app folder plus a double-click installer.
$ZipStage = Join-Path $Build 'zip'
New-Item -ItemType Directory -Force $ZipStage | Out-Null
Copy-Item $AppDir $ZipStage -Recurse
Copy-Item (Join-Path $Here 'install.ps1') $ZipStage -Force
$cmdLines = @(
    '@echo off',
    'powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"'
)
Set-Content -Path (Join-Path $ZipStage "Install $AppName.cmd") -Value $cmdLines -Encoding ASCII
$Zip = Join-Path $Dist "$AppName.zip"
if (Test-Path $Zip) { Remove-Item $Zip -Force }
Compress-Archive -Path (Join-Path $ZipStage '*') -DestinationPath $Zip
Write-Host "Zip:   $Zip"

# MBSI PC V3.exe: a real installer window (MbsiInstaller.cs) with the app inside, built by the
# C# compiler that ships with Windows. Unlike the IExpress exe it starts no PowerShell script.
$Csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$Mbsi = Join-Path $Dist 'MBSI PC V3.exe'
if (Test-Path $Mbsi) { Remove-Item $Mbsi -Force }
& $Csc /nologo /target:winexe /codepage:65001 /optimize+ "/out:$Mbsi" "/win32icon:$(Join-Path $Here 'favicon.ico')" `
    /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll `
    "/resource:$(Join-Path $Share 'app.zip'),app.zip" (Join-Path $Here 'MbsiInstaller.cs')
if ($LASTEXITCODE -ne 0) { throw 'csc failed to make MBSI PC V3.exe' }
Write-Host "MBSI:  $Mbsi"

if ($Install) {
    & (Join-Path $Here 'install.ps1') -Source $AppDir
}
