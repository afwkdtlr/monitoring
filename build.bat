@echo off
setlocal
cd /d %~dp0
if not exist out mkdir out
set JAVAC=C:\Program Files\Java\jdk1.8.0_202\bin\javac.exe
if not exist "%JAVAC%" set JAVAC=javac
"%JAVAC%" -encoding UTF-8 -source 1.8 -target 1.8 -d out src\com\teia\ops\*.java
if errorlevel 1 exit /b 1
echo build ok
