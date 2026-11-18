$ErrorActionPreference = "Stop"

$AppHome = Split-Path -Parent $PSScriptRoot
$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$HostName = if ($env:FLEET_HOST) { $env:FLEET_HOST } else { "0.0.0.0" }
$Port = if ($env:FLEET_PORT) { $env:FLEET_PORT } else { "19090" }
$Jar = Join-Path $AppHome "lib\freeway-fleet-server.jar"
$JavaOpts = if ($env:JAVA_OPTS) { $env:JAVA_OPTS.Split(" ", [StringSplitOptions]::RemoveEmptyEntries) } else { @() }

if (-not (Test-Path $Jar)) {
  Write-Error "Fleet Server jar not found at $Jar"
}
& $Java @JavaOpts "-Dstdout.encoding=UTF-8" "-Dstderr.encoding=UTF-8" "-Dfreeway.http.server.host=$HostName" "-Dfreeway.http.server.port=$Port" -jar $Jar @args
