@echo off
REM ─────────────────────────────────────────────────────────────────
REM  compile.bat — Compiles all Java source files
REM  Run from: p2p-academic-sharing\
REM ─────────────────────────────────────────────────────────────────

echo [Build] Compiling P2P Academic Resource Sharing Network...

if not exist "out" mkdir out

javac -encoding UTF-8 -d out -sourcepath src src\com\cpan226\p2p\*.java

if %ERRORLEVEL% == 0 (
    echo [Build] SUCCESS — class files written to .\out\
) else (
    echo [Build] FAILED — check errors above.
)
