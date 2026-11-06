@echo off
setlocal
cd /d %~dp0
if not exist out\com\teia\ops\OpsMonitor.class call build.bat
set JAVA=C:\Program Files\Java\jdk1.8.0_202\bin\java.exe
if not exist "%JAVA%" set JAVA=java
echo TEIA OPS  http://127.0.0.1:18080
"%JAVA%" -Dfile.encoding=UTF-8 -cp out;lib\sqlite-jdbc-3.25.2.jar com.teia.ops.OpsMonitor %*
