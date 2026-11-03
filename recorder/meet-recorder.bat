@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem meet-recorder.bat - registratore dei sottotitoli dei meet Teams, col JDK 25 o superiore.
rem
rem   meet-recorder.bat installa [cartella dei meet]   avvio al login e avvio adesso; la cartella, se c'e',
rem                                                    va nella configurazione (default %USERPROFILE%\meet-teams)
rem   meet-recorder.bat rimuovi                        toglie l'avvio al login
rem   meet-recorder.bat avvia                          avvia adesso l'app del tray (anche senza argomenti)
rem   meet-recorder.bat console [argomenti]            stesso ciclo in questa console, Invio per fermare
rem   meet-recorder.bat replay ^<registro-watch.tsv^>    replay di un registro del probe
rem
rem Il JDK si cerca nelle cartelle di installa-strumenti.bat; vince il JDK con la versione piu' alta,
rem dalla 25 in su. PATH e JAVA_HOME non cambiano. L'avvio al login e' la voce meet-recorder della
rem chiave Run di HKCU: il javaw.exe di quel JDK per path, seguito dal sorgente MeetRecorder.java.

set "RECORDER_DIR=%~dp0"
if "%RECORDER_DIR:~-1%"=="\" set "RECORDER_DIR=%RECORDER_DIR:~0,-1%"
set "RECORDER_SOURCE=%RECORDER_DIR%\MeetRecorder.java"
set "RUN_KEY=HKCU\Software\Microsoft\Windows\CurrentVersion\Run"
set "RUN_VALUE=meet-recorder"
set "JDK_MIN_MAJOR=25"
set "NATIVE_ACCESS=--enable-native-access=ALL-UNNAMED"
set "LOG_FILE=%LOCALAPPDATA%\meet-recorder\meet-recorder.log"

set "COMMAND=%~1"
if "%COMMAND%"=="" set "COMMAND=avvia"

if /i "%COMMAND%"=="rimuovi" goto :remove

call :find_jdk
if not defined JDK_HOME (
    echo [ERRORE] Nessun JDK %JDK_MIN_MAJOR% o superiore nelle cartelle note.
    echo Installalo con installa-strumenti.bat, che lo mette in %%USERPROFILE%%\dev-tools\jdk-25, poi rilancia.
    exit /b 1
)
echo JDK %JDK_VERSION%: %JDK_HOME%

if /i "%COMMAND%"=="installa" goto :install
if /i "%COMMAND%"=="avvia" goto :start
if /i "%COMMAND%"=="console" goto :console
if /i "%COMMAND%"=="replay" goto :replay
echo [ERRORE] comando sconosciuto: %COMMAND%
echo uso: meet-recorder.bat installa [cartella] ^| rimuovi ^| avvia ^| console [argomenti] ^| replay ^<registro^>
exit /b 2

:install
if not "%~2"=="" (
    "%JDK_HOME%\bin\java.exe" %NATIVE_ACCESS% "%RECORDER_SOURCE%" --imposta-cartella "%~2"
    if errorlevel 1 (
        echo [ERRORE] cartella dei meet non scritta nella configurazione.
        exit /b 1
    )
)
reg add "%RUN_KEY%" /v "%RUN_VALUE%" /t REG_SZ /d "\"%JDK_HOME%\bin\javaw.exe\" %NATIVE_ACCESS% \"%RECORDER_SOURCE%\"" /f >nul
if errorlevel 1 (
    echo [ERRORE] voce %RUN_VALUE% non scritta in %RUN_KEY%.
    exit /b 1
)
echo Avvio al login: %RUN_KEY%, voce %RUN_VALUE%.
goto :start

:start
start "" "%JDK_HOME%\bin\javaw.exe" %NATIVE_ACCESS% "%RECORDER_SOURCE%"
echo meet-recorder avviato: l'icona compare nel tray, anche fra le icone nascoste.
echo Log: %LOG_FILE%
exit /b 0

:console
"%JDK_HOME%\bin\java.exe" %NATIVE_ACCESS% "%RECORDER_SOURCE%" --console %2 %3 %4 %5 %6 %7 %8 %9
exit /b %ERRORLEVEL%

:replay
"%JDK_HOME%\bin\java.exe" %NATIVE_ACCESS% "%RECORDER_SOURCE%" --replay %2 %3 %4 %5 %6 %7 %8 %9
exit /b %ERRORLEVEL%

:remove
reg delete "%RUN_KEY%" /v "%RUN_VALUE%" /f >nul 2>&1
if errorlevel 1 (
    echo Nessun avvio al login da togliere.
) else (
    echo Avvio al login tolto.
)
echo L'app gia' accesa resta accesa: si chiude con Esci nel menu dell'icona.
exit /b 0

rem Il JDK con la versione piu' alta, dalla JDK_MIN_MAJOR in su, fra le cartelle note e JAVA_HOME. Un
rem JDK vale se ha il file release, javaw.exe e javac.exe: il lancio da sorgente vuole il compilatore.
:find_jdk
set "JDK_HOME="
set "JDK_VERSION="
set "JDK_MAJOR_FOUND=0"
for %%R in ("%ProgramFiles%\Java" "%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Zulu" "%ProgramFiles%\Amazon Corretto" "%ProgramFiles%\BellSoft" "%USERPROFILE%\.jdks" "%USERPROFILE%\dev-tools") do if exist "%%~R\" for /d %%D in ("%%~R\*") do call :consider_jdk "%%~D"
if defined JAVA_HOME call :consider_jdk "%JAVA_HOME%"
exit /b 0

:consider_jdk
set "CANDIDATE=%~f1"
if not exist "%CANDIDATE%\release" exit /b 0
if not exist "%CANDIDATE%\bin\javaw.exe" exit /b 0
if not exist "%CANDIDATE%\bin\javac.exe" exit /b 0
set "CANDIDATE_VERSION="
for /f "tokens=2 delims==" %%V in ('findstr /b /c:"JAVA_VERSION=" "%CANDIDATE%\release"') do set "CANDIDATE_VERSION=%%~V"
if not defined CANDIDATE_VERSION exit /b 0
set "CANDIDATE_MAJOR="
for /f "tokens=1,2 delims=." %%A in ("%CANDIDATE_VERSION%") do (
    set "CANDIDATE_MAJOR=%%A"
    if "%%A"=="1" set "CANDIDATE_MAJOR=%%B"
)
set "CANDIDATE_MAJOR_NUMBER=0"
set /a CANDIDATE_MAJOR_NUMBER=CANDIDATE_MAJOR 2>nul
if !CANDIDATE_MAJOR_NUMBER! LSS %JDK_MIN_MAJOR% exit /b 0
if !CANDIDATE_MAJOR_NUMBER! LEQ !JDK_MAJOR_FOUND! exit /b 0
set "JDK_HOME=%CANDIDATE%"
set "JDK_VERSION=%CANDIDATE_VERSION%"
set "JDK_MAJOR_FOUND=!CANDIDATE_MAJOR_NUMBER!"
exit /b 0
