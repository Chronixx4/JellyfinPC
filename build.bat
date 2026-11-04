@echo off
rem Baut JellyfinPC.jar und daraus ein Windows-Programm (Ordner dist\JellyfinPC\JellyfinPC.exe, bringt Java mit).
rem Voraussetzungen: JDK 21 (mit jpackage) und Maven im PATH.
call mvn -q -DskipTests clean package || exit /b 1
if exist dist rmdir /s /q dist
jpackage --type app-image --name JellyfinPC --input target --main-jar JellyfinPC.jar ^
  --main-class de.marvin.jellyfin.pc.Launcher --dest dist --java-options "-Dprism.order=d3d,sw" || exit /b 1
echo Fertig: dist\JellyfinPC\JellyfinPC.exe
