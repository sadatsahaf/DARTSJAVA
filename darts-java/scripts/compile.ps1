$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location "$scriptDir\.."
if (!(Test-Path "out")) {
    New-Item -ItemType Directory -Path "out" | Out-Null
}
$cp = "lib/*"
$sources = Get-ChildItem -Path "src" -Recurse -Include *.java | Select-Object -ExpandProperty FullName
javac -cp $cp -d out $sources
if ($LASTEXITCODE -eq 0) {
    Write-Host "[SUCCESS] Compilation finished clean." -ForegroundColor Green
} else {
    Write-Host "[ERROR] Compilation failed." -ForegroundColor Red
    exit $LASTEXITCODE
}
