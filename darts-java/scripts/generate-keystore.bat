@echo off
REM Generates a self-signed TLS keystore for local DARTS development.
REM See docs\05_SECURITY.md — self-signed certs are acceptable for this project.

set SCRIPT_DIR=%~dp0
set CERT_DIR=%SCRIPT_DIR%..\certs
set KEYSTORE=%CERT_DIR%\server.jks
set ALIAS=darts-server
set STOREPASS=changeit

if not exist "%CERT_DIR%" mkdir "%CERT_DIR%"

if exist "%KEYSTORE%" (
    echo Keystore already exists at %KEYSTORE% — delete it first to regenerate.
    exit /b 1
)

keytool -genkeypair ^
    -alias %ALIAS% ^
    -keyalg RSA ^
    -keysize 2048 ^
    -validity 365 ^
    -keystore "%KEYSTORE%" ^
    -storepass %STOREPASS% ^
    -keypass %STOREPASS% ^
    -dname "CN=localhost, OU=DARTS, O=DARTS, L=Local, ST=Local, C=US"

if %ERRORLEVEL% neq 0 (
    echo [ERROR] keytool failed.
    exit /b %ERRORLEVEL%
)

echo [SUCCESS] Keystore created at %KEYSTORE% (alias=%ALIAS%, storepass=%STOREPASS%)
