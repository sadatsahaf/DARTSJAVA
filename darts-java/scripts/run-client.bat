@echo off
setlocal
cd /d "%~dp0\.."
java -cp "out;lib/*" darts.client.Client %*
