@echo off
REM ─────────────────────────────────────────────────────────────────
REM  run.bat — Launch a PeerNode
REM
REM  Usage:
REM    run.bat                         (port 6000, shared = .\shared)
REM    run.bat --port 6001 --shared .\shared-b
REM
REM  Demo (open TWO terminals in this folder):
REM    Terminal 1:  run.bat --port 6000 --shared .\shared-a
REM    Terminal 2:  run.bat --port 6001 --shared .\shared-b
REM ─────────────────────────────────────────────────────────────────

java -cp out com.cpan226.p2p.PeerNode %*
