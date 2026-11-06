@echo off
chcp 65001 >nul
title 卡背编辑器
cd /d "%~dp0"

rem 优先 py -3，没有再退回 python
set "PY="
where py >nul 2>nul
if not errorlevel 1 set "PY=py -3"
if defined PY goto run

where python >nul 2>nul
if not errorlevel 1 set "PY=python"
if defined PY goto run

echo.
echo 没找到 Python 3。
echo 先装一个 Python 3.8 以上版本，安装时勾上 "Add python.exe to PATH"，再双击本文件。
echo.
pause
exit /b 1

:run
%PY% server.py
set "CODE=%errorlevel%"

echo.
if not "%CODE%"=="0" echo 服务退出了，退出码 %CODE%。
pause
