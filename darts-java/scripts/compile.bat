@echo off
setlocal
cd /d "%~dp0\.."
if not exist out mkdir out
javac -cp "lib/*" -d out src\darts\common\*.java src\darts\server\*.java src\darts\client\*.java
if %ERRORLEVEL% equ 0 (
    echo [SUCCESS] Compilation finished clean.
) else (
    echo [ERROR] Compilation failed.
    exit /b %ERRORLEVEL%
)
