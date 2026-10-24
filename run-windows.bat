@echo off
cd /d "%~dp0"
where mvn >nul 2>&1
if errorlevel 1 (
  echo Apache Maven was not found. Install Maven and add it to PATH.
  pause
  exit /b 1
)
mvn compile exec:java
if errorlevel 1 pause
