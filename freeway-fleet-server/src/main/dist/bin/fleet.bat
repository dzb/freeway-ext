@echo off
setlocal

set "APP_HOME=%~dp0.."
if defined JAVA_HOME (
  set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_BIN=java"
)
if not defined FLEET_HOST set "FLEET_HOST=0.0.0.0"
if not defined FLEET_PORT set "FLEET_PORT=19090"
set "JAR=%APP_HOME%\lib\fleet.jar"

if not exist "%JAR%" (
  echo Fleet Server jar not found at %JAR% 1>&2
  exit /b 1
)

"%JAVA_BIN%" %JAVA_OPTS% ^
  -Dstdout.encoding=UTF-8 ^
  -Dstderr.encoding=UTF-8 ^
  -Dfreeway.http.server.host=%FLEET_HOST% ^
  -Dfreeway.http.server.port=%FLEET_PORT% ^
  -jar "%JAR%" %*
