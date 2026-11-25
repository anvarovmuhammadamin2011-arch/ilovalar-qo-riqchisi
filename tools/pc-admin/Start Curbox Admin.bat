@echo off
title Curbox Admin
cd /d "%~dp0"

rem Find a Java 17 or newer: JAVA_HOME first, then a JDK under %USERPROFILE%\jdks, then PATH.
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE (
  for /d %%D in ("%USERPROFILE%\jdks\jdk-*") do if exist "%%D\bin\java.exe" set "JAVA_EXE=%%D\bin\java.exe"
)
if not defined JAVA_EXE set "JAVA_EXE=java"

start "" "http://localhost:8787"
"%JAVA_EXE%" CurboxAdmin.java
echo.
echo Curbox Admin stopped. Press any key to close this window.
pause >nul
