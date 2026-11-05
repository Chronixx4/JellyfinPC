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

Neue Version veröffentlichen: `build`/`version` in `src/main/resources/app.properties` erhöhen und nach `main` pushen.
Der Workflow `.github/workflows/release.yml` baut dann die EXE, legt das Release `vX.Y` an und aktualisiert `version.json` (Build, URL, MD5).

`src/main/resources/vlc-win64.zip` (libVLC 64 Bit, eingebettet) liegt im Repo, damit der Build in GitHub Actions funktioniert.

## Handy-/Tablet-App (Android, `android/`)
Gradle-Projekt mit zwei Modulen: `core` (Jellyfin-REST-Client `Jf`/`JfItem`, vom PC-Code abgeleitet, auch für die spätere TV-App gedacht)
und `mobile` (Touch-Oberfläche, Paket `de.marvin.jellyfin.mobile`, Player auf Media3/ExoPlayer).
Eine spätere Android-TV/Fire-TV-App kommt als drittes Modul `tv` neben `mobile` und nutzt `core` mit.

Funktionen (seit 3.0 wie die PC-App, außer Gemeinsam schauen/Controller/Farbdesign): Anmeldung (Passwort/Quick Connect/Profile), Start (Weiter schauen, Als Nächstes, Neu in …), Bibliotheken mit Sortierung/Filter/Genre,
Suche, Merkliste, Details (Besetzung, Staffeln/Folgen, Ähnliche), gesehen/Merkliste setzen (auch per langem Drücken),
Player mit Doppeltippen ±10 s, Wischen zum Spulen/Helligkeit/Lautstärke, Ton-/Untertitel, Tempo, Kapitel, Vorspann überspringen,
nächste Folge, Bild-in-Bild und Wiedergabestatus an den Server. Wenn der Handy-Player eine Datei nicht direkt abspielen kann (z. B. DTS), wandelt der Server um.

Bauen: `cd android && ./gradlew :mobile:assembleRelease` (JDK 17+, Android-SDK 34) -> `mobile/build/outputs/apk/release/mobile-release.apk`.
Version und Build-Nummer kommen aus derselben `app.properties` wie bei der EXE; `versionCode` = `build`.
Der Workflow baut EXE und APK zusammen und trägt die APK in `version.json` unter `mobile` ein. Die App sucht beim Start danach
(abschaltbar im Konto-Menü) und öffnet nach dem Download den Android-Installationsdialog.

Signatur: `android/jellyfin-release.keystore` (Passwort `jellyfin`) liegt bewusst im Repo, damit jede APK dieselbe Signatur hat und sich als Update
installieren lässt. Das ist für eine private Sideload-App ok, aber kein Schutz gegen gefälschte APKs.

Tests: `ROBO_DIR=<Ordner mit android-all-Jar> ./gradlew :mobile:testDebugUnitTest` startet die echten Seiten (Login, Start, Bibliothek, Details, Serie,
Suche, Konto, Player-Aufbau) in Robolectric gegen einen Mock-Server. Echte Wiedergabe und Gesten sind damit nicht abgedeckt.

## Handy als Fernbedienung (seit 3.1)

Die PC-App meldet sich beim Jellyfin-Server als steuerbares Gerät an (Sessions-/Remote-Control-API, WebSocket `/socket`
und `POST /Sessions/Capabilities/Full`). In der Handy-App öffnet das TV-Symbol oben die Fernbedienung: Gerät wählen,
dann Pause/Weiter, ±Sprung, Spulen, nächste Folge, Stop und Lautstärke. Auf jeder Detailseite startet das TV-Symbol
neben „Abspielen“ den Titel auf dem gewählten Gerät. Andere Clients (z. B. die TV-App) erscheinen automatisch, sobald sie
sich genauso anmelden (`Remote.java` als Vorlage).
