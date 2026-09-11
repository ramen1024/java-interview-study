@echo off
rem Double-click entry point for Windows. All real logic lives in dev.sh next to
rem this file; this wrapper only locates Git Bash and forwards the arguments.
rem
rem NOTE: keep this file ASCII-only. cmd.exe reads a .cmd byte-wise using the
rem console code page (GBK on a zh-CN box), so UTF-8 Chinese in rem/echo lines
rem gets misparsed and shreds the quoting and arguments. chcp below only fixes
rem the *output* code page so dev.sh's UTF-8 Chinese renders correctly.
rem For an interactive run (clean Ctrl+C), prefer: bash dev.sh in Git Bash.
chcp 65001 >nul
setlocal

set "SCRIPT_DIR=%~dp0"
if not defined SCRIPT_DIR for %%I in ("%~f0") do set "SCRIPT_DIR=%%~dpI"

set "GITBASH="
if exist "%ProgramFiles%\Git\bin\bash.exe" set "GITBASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined GITBASH if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "GITBASH=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined GITBASH if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "GITBASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"
if not defined GITBASH for %%I in (bash.exe) do if not "%%~$PATH:I"=="" set "GITBASH=%%~$PATH:I"

if not defined GITBASH (
    echo [ERROR] Git Bash not found. Install Git for Windows, or run "bash dev.sh" inside Git Bash.
    pause
    exit /b 1
)

"%GITBASH%" "%SCRIPT_DIR%dev.sh" %*
set "EXITCODE=%ERRORLEVEL%"

rem A double-clicked window must not vanish on failure; stay out of the way when
rem invoked from an existing terminal.
if not "%EXITCODE%"=="0" echo [ERROR] startup failed, exit code %EXITCODE%
echo %cmdcmdline% | findstr /i /c:"%~nx0" >nul && pause

exit /b %EXITCODE%
