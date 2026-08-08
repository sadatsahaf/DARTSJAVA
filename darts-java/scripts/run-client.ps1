$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location "$scriptDir\.."
java -cp "out;lib/*" darts.client.Client $args
