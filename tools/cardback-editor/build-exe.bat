@echo off
chcp 65001 >nul
setlocal
rem 把卡背编辑器打成单文件 exe（不需要用户装 Python）
rem 产物：dist\exe\CardbackEditor.exe
rem 首次运行会自动建虚拟环境并装 PyInstaller，之后就走缓存，几十秒出包。

cd /d "%~dp0"
set "ROOT=%~dp0..\.."
set "VENV=%ROOT%\_toolchain\venv-exe"
set "PY=%VENV%\Scripts\python.exe"

if not exist "%PY%" (
  echo [1/3] 建虚拟环境 %VENV%
  python -m venv "%VENV%"
  if errorlevel 1 goto :fail
) else (
  echo [1/3] 虚拟环境已存在，跳过
)

echo [2/3] 装 PyInstaller
"%PY%" -m pip install --no-cache-dir --disable-pip-version-check --quiet pyinstaller
if errorlevel 1 goto :fail

echo [3/3] 打包
"%VENV%\Scripts\pyinstaller.exe" --noconfirm --clean --onefile --name CardbackEditor ^
  --add-data "%~dp0index.html;." --add-data "%~dp0styles.css;." ^
  --add-data "%~dp0app.js;." --add-data "%~dp0samples.js;." ^
  --distpath "%ROOT%\dist\exe" --workpath "%ROOT%\_toolchain\pybuild" --specpath "%ROOT%\_toolchain\pybuild" ^
  "%~dp0server.py"
if errorlevel 1 goto :fail

echo.
echo 好了：%ROOT%\dist\exe\CardbackEditor.exe
exit /b 0

:fail
echo.
echo 打包失败，看上面的报错。
exit /b 1
