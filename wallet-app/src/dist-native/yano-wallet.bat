@echo off
REM Yano Wallet launcher - native distribution (ADR-044).
REM
REM Needs NOTHING installed: the wallet is an AOT-compiled binary and the
REM bundled Yano node is a native executable too. No Java, no JRE.
REM
REM Usage:
REM   yano-wallet.bat                        launch the wallet
REM   yano-wallet.bat --network=preprod ...  pass-through CLI options
REM
REM Environment:
REM   YANO_NODE_JAR  override the managed node binary (default .\yano-node\yano.exe)

setlocal
set "DIR=%~dp0"
set "WALLET=%DIR%yano-wallet.exe"
if not defined YANO_NODE_JAR set "YANO_NODE_JAR=%DIR%yano-node\yano.exe"

if not exist "%WALLET%" (
    echo Error: %WALLET% is missing. 1>&2
    exit /b 1
)

REM The node is optional - the wallet can connect to an external one.
if not exist "%YANO_NODE_JAR%" (
    echo Warning: managed node not found at %YANO_NODE_JAR% 1>&2
    echo          Only external-node connections will work. 1>&2
    "%WALLET%" %*
    exit /b %ERRORLEVEL%
)

"%WALLET%" -Dyano.node.jar="%YANO_NODE_JAR%" %*
exit /b %ERRORLEVEL%
