@echo off
set GRADLE_VERSION=8.9
if not exist "%USERPROFILE%\.gradle\jb23-wrapper\gradle-%GRADLE_VERSION%\bin\gradle.bat" (
  echo This project is intended to be built on AndroidIDE using ./gradlew.
  exit /b 1
)
call "%USERPROFILE%\.gradle\jb23-wrapper\gradle-%GRADLE_VERSION%\bin\gradle.bat" %*
