@echo off
setlocal
cd /d "%~dp0.."
where make >nul 2>&1
if errorlevel 1 (
  echo make no esta en PATH. Instala make o abr? una terminal donde make funcione.
  pause
  exit /b 1
)
make windows-run
set EXITCODE=%ERRORLEVEL%
if not %EXITCODE%==0 (
  echo.
  echo make windows-run salio con codigo %EXITCODE%
  pause
)
exit /b %EXITCODE%
