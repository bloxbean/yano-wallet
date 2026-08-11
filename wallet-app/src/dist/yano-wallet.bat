@echo off
rem Yano Wallet launcher - portable "bring your own Java" distribution.
rem Requires a Java 25+ runtime on PATH (or set JAVA_HOME). The managed node runs
rem under the same Java, so a full JDK/JRE 25 is needed. No Java? Use the .msi,
rem which bundles its own runtime.
setlocal
set "DIR=%~dp0"

if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA=java"
)

"%JAVA%" %JAVA_OPTS% ^
    --enable-native-access=ALL-UNNAMED ^
    -Dyano.node.jar="%DIR%yano-node\yano.jar" ^
    -cp "%DIR%lib\*" ^
    com.bloxbean.cardano.yano.wallet.app.YanoWalletApp %*

endlocal
