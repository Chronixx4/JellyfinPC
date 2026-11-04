# Jellyfin PC (Windows-Desktop-Client)

Desktop-Variante des JellyfinTV-Projekts: JavaFX-Oberfläche, Player auf Basis von VLC (libVLC), der auch DTS/TrueHD/HEVC direkt abspielt.
Die Jellyfin-Logik (`Jf.java`, `JfItem.java`) ist aus der Android-TV-App übernommen.

## Funktionen
Anmeldung (Passwort oder Quick Connect), Start mit „Weiter schauen“, „Als nächstes“, „Neu in …“, Bibliotheken mit Sortierung/Filter/Genre,
Suche, Detailseiten (Besetzung, Staffeln/Folgen, Medien-Badges), Player mit Fortsetzen, Ton-/Untertitelwahl, Tempo, Vollbild (F / Doppelklick),
nächste Folge automatisch, Wiedergabestatus wird an den Server gemeldet.

## Voraussetzungen zum Starten
- Der Player (libVLC) ist im Ordner `vlc` neben der EXE eingebaut, es muss nichts installiert werden. Der Ordner `vlc` muss neben `JellyfinPC.exe` liegen. Ohne ihn wird ein installierter VLC (64 Bit) verwendet.
- Fertige `JellyfinPC.jar`: `java -jar JellyfinPC.jar` (JDK/JRE 17 oder neuer).

## Bauen
`build.bat` (JDK 21 + Maven) erzeugt `target\JellyfinPC.jar` und `dist\JellyfinPC\JellyfinPC.exe` mit mitgeliefertem Java.
Zum Testen unter Linux: `mvn -Djavafx.platform=linux package`.

## Stand
Oberfläche (Login, Startseite mit Banner, Details, Staffeln/Folgen) gegen einen Test-Server per Screenshot geprüft.
Noch nicht geprüft: Wiedergabe mit echtem VLC und echtem Jellyfin-Server, Windows-EXE.
Server-Standardadresse in `Jf.java` (`DEFAULT_SERVER`) anpassen, oder beim ersten Start im Login-Fenster eingeben.

## Auto-Update über GitHub
Die App liest beim Start `version.json` aus diesem Repo (`raw.githubusercontent.com/Chronixx4/JellyfinPC/main/version.json`).
Ist `windows.build` größer als der eingebaute Build (`src/main/resources/app.properties`), wird die EXE vom dort angegebenen
Release-Asset geladen (MD5-Prüfung) und ersetzt. Die Einträge `tv` und `mobile` sind für die späteren Android-Apps reserviert.

Neue Version veröffentlichen: `build`/`version` in `app.properties` erhöhen, bauen, EXE als Release-Asset `vX.Y` hochladen,
dann `version.json` (Build, Version, URL, MD5) anpassen und pushen.

Zum Bauen muss `vlc-win64.zip` (libVLC 3.0.x, 64 Bit) nach `src/main/resources/` gelegt werden; sie ist nicht im Git.
