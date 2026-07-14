@echo off
setlocal

set "ADMIN_DIR=%~dp0"
set "MAVEN_CMD="

for /r "%USERPROFILE%\.m2\wrapper\dists" %%F in (mvn.cmd) do if not defined MAVEN_CMD if exist "%%F" set "MAVEN_CMD=%%F"
if not defined MAVEN_CMD (
  echo Admin Console could not find the repository's cached Maven runtime. 1>&2
  echo Run server\mvnw.cmd once, then retry. 1>&2
  exit /b 1
)

call "%MAVEN_CMD%" -q -f "%ADMIN_DIR%pom.xml" package -DskipTests
set "BUILD_EXIT=%errorlevel%"
if not "%BUILD_EXIT%"=="0" exit /b %BUILD_EXIT%

java -jar "%ADMIN_DIR%target\admin-console.jar" %*
