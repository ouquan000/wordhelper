@echo off
chcp 65001 >nul
title 卡背重写服务
cd /d "%~dp0"

rem 优先 py -3，没有再退回 python
set "PY="
where py >nul 2>nul
if not errorlevel 1 set "PY=py -3"
if defined PY goto probe

where python >nul 2>nul
if not errorlevel 1 set "PY=python"
if defined PY goto probe

echo.
echo 没找到 Python 3。
echo 先装一个 Python 3.8 以上版本，安装时勾上 "Add python.exe to PATH"，再双击本文件。
echo.
pause
exit /b 1

:probe
rem 重写服务挂在编辑器那个 8765 上；它已经在跑就别再抢一次端口
%PY% -c "import urllib.request as u; u.urlopen('http://127.0.0.1:8765/rewrite/status', timeout=2).read()" >nul 2>nul
if not errorlevel 1 goto alive

echo 启动服务（编辑器和重写服务在同一个端口上）...
echo   面板：http://127.0.0.1:8765/rewrite/
echo   接口：http://127.0.0.1:8765/rewrite/marks
echo.
%PY% "..\cardback-editor\server.py"
set "CODE=%errorlevel%"

echo.
if not "%CODE%"=="0" echo 服务退出了，退出码 %CODE%。
pause
exit /b %CODE%

:alive
echo 8765 上已经有服务在跑，直接用现成的。
start "" http://127.0.0.1:8765/rewrite/
echo 面板：http://127.0.0.1:8765/rewrite/
echo.
pause
exit /b 0
