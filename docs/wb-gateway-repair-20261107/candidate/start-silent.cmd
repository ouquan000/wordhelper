@echo off
setlocal EnableExtensions DisableDelayedExpansion
rem Install with wb-service-check.ps1 beside this script in the formal run directory.
rem Keep the existing BOM sanitation, config argument and stdout/stderr destination.
cd /d "%~dp0"
if not exist "%~dp0wb-service-check.ps1" goto helper_missing
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "D:\deepseek_v4.1flash1017\thebigfish\tools\wb-config-sanitize.ps1" -Dir "%~dp0." -LogPath "D:\Apps\Bot\health\wb-guard.log" -Quiet
set "SANITIZE_RC=%errorlevel%"
if "%SANITIZE_RC%"=="0" goto check_main
if "%SANITIZE_RC%"=="1" goto check_main
echo WB_START ERROR config sanitation failed code=%SANITIZE_RC% 1>&2
exit /b 40

:check_main
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0wb-service-check.ps1" -Service main -PanelRoot "%~dp0.."
set "MAIN_RC=%errorlevel%"
if "%MAIN_RC%"=="0" goto check_router
if "%MAIN_RC%"=="10" goto main_missing
echo WB_START ERROR main inspection code=%MAIN_RC%; no duplicate process will be started. 1>&2
exit /b %MAIN_RC%

:main_missing
if exist "%~dp0.hold-restart" goto held
if not exist "%~dp0wb2api.exe" goto binary_missing

:check_router
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0wb-service-check.ps1" -Service router -PanelRoot "%~dp0.."
set "ROUTER_RC=%errorlevel%"
if "%ROUTER_RC%"=="0" goto router_ready
if "%ROUTER_RC%"=="10" goto router_missing
echo WB_START ERROR router inspection code=%ROUTER_RC%; owner or HTTP conflict requires attention. 1>&2
exit /b %ROUTER_RC%

:router_missing
if exist "%~dp0.hold-restart" goto held
if not exist "%~dp0start-router.vbs" goto router_starter_missing
start "" wscript.exe //B "%~dp0start-router.vbs"
set "START_RC=%errorlevel%"
if not "%START_RC%"=="0" goto router_launch_failed
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0wb-service-check.ps1" -Service router -PanelRoot "%~dp0.." -WaitSeconds 20
set "ROUTER_RC=%errorlevel%"
if "%ROUTER_RC%"=="0" goto router_ready
echo WB_START ERROR router failed identity/HTTP recheck code=%ROUTER_RC%. 1>&2
exit /b %ROUTER_RC%

:router_ready
if "%MAIN_RC%"=="0" exit /b 0
rem Recheck after the asynchronous router launch; never start over a new listener.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0wb-service-check.ps1" -Service main -PanelRoot "%~dp0.."
set "MAIN_RC=%errorlevel%"
if "%MAIN_RC%"=="0" exit /b 0
if "%MAIN_RC%"=="10" goto launch_main
echo WB_START ERROR main changed during startup code=%MAIN_RC%. 1>&2
exit /b %MAIN_RC%

:launch_main
if exist "%~dp0.hold-restart" goto held
"%~dp0wb2api.exe" -config config.json >> "%~dp0panel.log" 2>&1
exit /b %errorlevel%

:held
echo WB_START HOLD .hold-restart prevents a missing service from starting. 1>&2
exit /b 10
:helper_missing
echo WB_START ERROR missing run\wb-service-check.ps1; install the shared helper first. 1>&2
exit /b 40
:binary_missing
echo WB_START ERROR missing wb2api.exe. 1>&2
exit /b 40
:router_starter_missing
echo WB_START ERROR missing start-router.vbs. 1>&2
exit /b 40
:router_launch_failed
echo WB_START ERROR could not launch start-router.vbs code=%START_RC%. 1>&2
exit /b 40
