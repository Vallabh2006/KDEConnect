@echo off
set "JAVA_HOME=C:\Users\ASUS\AppData\Local\Programs\jdk-17\jdk-17.0.12+7"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
call .\gradlew.bat assembleDebug
