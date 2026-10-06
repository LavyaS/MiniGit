@echo off
rem MiniGit launcher for Windows. Usage: minigit <command> [args]
rem Requires Java 17+ on PATH and a built jar (mvn clean package).
setlocal
set "JAR=%~dp0..\target\minigit.jar"
if not exist "%JAR%" (
    echo minigit: %JAR% not found. Run "mvn clean package" first. 1>&2
    exit /b 1
)
java -jar "%JAR%" %*
exit /b %ERRORLEVEL%
