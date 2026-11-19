@echo off
cd /d "%~dp0"
java --add-modules jdk.httpserver src/Morrow.java
pause
