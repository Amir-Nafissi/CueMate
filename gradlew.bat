@echo off
where gradle >nul 2>nul
if %errorlevel%==0 (
  gradle %*
  exit /b %errorlevel%
)
echo Gradle is not installed on this machine. Install Gradle or use Android Studio to sync the project.
exit /b 1
