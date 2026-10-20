# rubato-eq

Android-App, die live analysiert, was auf dem Handy läuft (vor allem Spotify), und automatisch einen
sanften Korrektur-EQ darüberlegt, damit jeder Song tonal ausgewogen klingt. Reine Signalverarbeitung,
kein ML. Die Ziel-Klangbalance entsteht aus der eigenen Bibliothek.

Zielgerät: Samsung Galaxy S26 Ultra (One UI, Android 16/17). Privates Projekt, kein Play Store.

## So funktioniert es

1. **Now Playing:** Ein `NotificationListenerService` schaltet `MediaSessionManager` frei. rubato verfolgt
   die aktive Session (Quelle, Titel, Künstler, Dauer, Status). Ein Songwechsel gilt erst, wenn die
   Metadaten 0,7 s stabil sind (Player schicken sie oft stückweise).
2. **Analyse:** `Visualizer` auf Session 0 (Output-Mix), 1024er FFT mit maximaler Rate (meist 20 Hz).
   Die Bins werden in 8 etwa oktavbreite Bänder summiert. Die unteren zwei Oktaven sind zu einem Band
   zusammengefasst, weil ein Bin ~43–47 Hz breit ist:

   | Band | 90 | 250 | 500 | 1k | 2k | 4k | 8k | 16k |
   |---|---|---|---|---|---|---|---|---|
   | obere Kante (Hz) | 177 | 354 | 707 | 1414 | 2828 | 5657 | 11314 | 24000 (Analyse bis 16000) |

   Die Leistung wird linear über alle Frames des Songs gemittelt und erst dann in dB umgerechnet.
   Stille-Frames werden verworfen (Peak/RMS-Messung des Visualizers, sonst leere FFT), ebenso die
   erste Sekunde nach einem Songwechsel. Zum Schluss wird auf den mittleren Bandpegel normalisiert,
   so bleibt nur die tonale Balance übrig.
3. **Zielkurve:** laufender Durchschnitt der normalisierten Spektren aller analysierten Songs (jeder Song
   zählt einmal, ab 20 s analysiertem Audio) plus globaler Geschmacks-Offset (Bass/Höhen).
4. **Korrektur:** `Stärke × min(1, n/20) × (Ziel − Messung)`, über Nachbarbänder geglättet
   ([¼ ½ ¼]) und auf ±3 dB begrenzt (in der UI bis ±6 dB). Die erste Korrektur kommt nach 5 s
   analysiertem Audio, danach alle 10 s mit höchstens 0,5 dB pro Band und Schritt. Jede Änderung wird
   über 1,5 s gerampt. Darauf kommen die drei manuellen Song-Slider (Bass/Mitten/Höhen).
5. **Cache:** Pro Song (Künstler + Titel + Dauer) speichert Room das Spektrum, die Korrektur und die
   manuellen Offsets. Läuft ein bekannter Song wieder, gilt seine Korrektur sofort ab Start, und die
   Messung wird fortgesetzt.
6. **EQ:** `DynamicsProcessing` mit Pre-EQ (Bänder = Analysebänder, 20-ms-Frames → dasselbe
   1024er-FFT-Raster) und einem Limiter bei 0 dBFS als Sicherheitsnetz. Zwei Modi (Debug-Screen):
   - **Global:** ein Effekt auf Session 0.
   - **Player-Session:** ein Effekt pro Session, die ein Player per
     `ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION` meldet.
7. **Kompensation:** Misst der Visualizer hinter unserem EQ, wird die gerade angewendete Verstärkung pro
   Frame und Band herausgerechnet, sonst entsteht eine Rückkopplung. Laut AOSP ist der Visualizer ein
   `INSERT_FIRST`- und DynamicsProcessing ein `INSERT_LAST`-Effekt. Im Global-Modus sollte der
   Visualizer also vor dem EQ messen (Standard: aus), im Session-Modus sicher dahinter (Standard: an).
   Beides ist pro Modus im Debug-Screen umschaltbar.
8. **Trainingsdaten:** Jede manuelle Korrektur wird mit den Song-Merkmalen (Spektrum, Ziel, Automatik,
   Einstellungen) als Trainingsbeispiel gespeichert. Die Datenbank lässt sich als JSON über das
   Share-Sheet exportieren.

## Architektur

```
core/   reines Kotlin/JVM, ohne Android, mit Unit-Tests
  analysis/    BandLayout, FftBandAggregator, SpectrumAccumulator, Spectra, SilenceGate
  correction/  CorrectionMath, ToneOffset, LibraryTarget, GainRamp, SongAnalysis (Zeitplan)
  update/      AppVersion (Versionsvergleich), GitHubRelease (API-Antwort)
  model/       SongKey
app/
  nowplaying/  MediaListenerService, NowPlayingTracker
  audio/       VisualizerCapture, EqEffectController, AudioSessionRegistry (+ Receiver)
  engine/      RubatoEngine, EngineState, EventLog
  service/     RubatoService (Foreground Service), Notifications, Receiver (Pause, Update/Boot)
  data/        Room (songs, training_examples), DataStore-Einstellungen, JSON-Export
  update/      UpdateManager, InstallResultReceiver (Self-Updater)
  ui/          Compose (Onboarding, Live, Einstellungen, Update, Debug)
```

Die Engine läuft komplett auf einem eigenen `HandlerThread`. Visualizer-, MediaController- und
AudioEffect-Callbacks kommen dort an, und die Coroutines der Engine laufen ebenfalls dort, deshalb gibt
es keine Locks. Datenbank-Schreibzugriffe laufen seriell in Reihenfolge (`SerialWriter`).

Der Foreground Service hat den Typ `microphone`, weil der Visualizer `RECORD_AUDIO` braucht (eine
While-in-use-Berechtigung). Ein solcher Service darf nur aus dem Vordergrund starten. Nach einem Update
(`MY_PACKAGE_REPLACED`) oder Neustart zeigt rubato deshalb die Notification „Update installiert –
tippen zum Starten“. Der Tipp öffnet die App, und die App startet den Service.

## Bauen

Voraussetzungen: JDK 17+ (getestet mit JDK 25) und Android SDK mit Plattform 37.2 (lädt AGP bei
akzeptierten Lizenzen selbst nach). `local.properties` mit `sdk.dir=…` ist gitignoriert.

```sh
./gradlew test assembleDebug      # Unit-Tests + Debug-APK (ch.sommermeyer.rubato.debug)
./gradlew installDebug            # per adb aufs Handy
```

Debug- und Release-Build lassen sich parallel installieren (`.debug`-Suffix). Der Self-Updater ist nur
im Release-Build aktiv.

Versionierung: `versionCode` = GitHub-Run-Nummer, `versionName` = `0.1.<run>`, Tag `v0.1.<run>`.
Lokale Builds heissen `0.1.0-local`.

## Release-Signierung

Ein Release-Build wird **nur** mit dem Release-Key signiert. Fehlt die Konfiguration, bricht
`assembleRelease` mit einer klaren Meldung ab (Task `verifyReleaseSigning`). Mit dem Debug-Key wird nie
signiert, sonst könnten Updates die installierte App nicht mehr ersetzen.

### Keystore erzeugen (einmalig)

```sh
keytool -genkeypair -v -keystore rubato-release.jks -storetype PKCS12 \
  -alias rubato -keyalg RSA -keysize 4096 -validity 36500 \
  -dname "CN=rubato-eq"
```

Bei PKCS12 sind Keystore- und Key-Passwort identisch. **Keystore und Passwort sicher sichern.** Geht der
Key verloren, lässt sich die App nur noch nach einer Deinstallation (mit Datenverlust) aktualisieren.

### GitHub-Secrets

Repository → Settings → Secrets and variables → Actions → *New repository secret*:

| Secret | Inhalt |
|---|---|
| `KEYSTORE_BASE64` | Keystore als Base64 (eine Zeile) |
| `KEYSTORE_PASSWORD` | Keystore-Passwort |
| `KEY_ALIAS` | `rubato` (bzw. der gewählte Alias) |
| `KEY_PASSWORD` | Key-Passwort (bei PKCS12 = Keystore-Passwort) |

Base64 erzeugen:

```powershell
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("rubato-release.jks")) | Set-Clipboard
```

```sh
base64 -w0 rubato-release.jks     # Linux (macOS: base64 -i rubato-release.jks)
```

Alternativ mit der GitHub CLI:

```sh
base64 -w0 rubato-release.jks | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS --body rubato
gh secret set KEY_PASSWORD
```

### Lokal signieren

`keystore.properties.example` nach `keystore.properties` kopieren (gitignoriert) und ausfüllen, oder die
Umgebungsvariablen `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` setzen (sie haben
Vorrang). Danach `./gradlew assembleRelease`.

## CI/CD

- `.github/workflows/release.yml`: bei Push auf `main` und per `workflow_dispatch`. Unit-Tests →
  `assembleRelease` (signiert) → Signatur prüfen → GitHub Release `v0.1.<run>` mit Asset `rubato-eq.apk`.
  Release-Notes sind die Commit-Messages seit dem letzten Tag. Rechte: nur `contents: write`.
- `.github/workflows/pull-request.yml`: bei Pull Requests nur `./gradlew test assembleDebug`.

## Erstinstallation

1. **Samsung Auto Blocker ausschalten:** Einstellungen → Sicherheit und Datenschutz → Auto Blocker.
   Sonst blockiert One UI die Installation von APKs, auch die Updates.
2. APK installieren: entweder `rubato-eq.apk` aus dem ersten GitHub-Release am Handy herunterladen und
   öffnen (dem Browser bzw. „Eigene Dateien“ die Installation erlauben), oder per
   `adb install rubato-eq.apk`.
3. App öffnen und das Onboarding durchgehen: Benachrichtigungszugriff, Audioanalyse (RECORD_AUDIO),
   Benachrichtigungen, Akku-Optimierung ignorieren, „Unbekannte Apps installieren“ (für den Updater).
4. Ist der Benachrichtigungszugriff gesperrt („Eingeschränkte Einstellung“): App-Info → ⋮ →
   „Eingeschränkte Einstellungen zulassen“, dann erneut versuchen.
5. rubato unter Einstellungen → Akku → Hintergrundnutzungslimits → **Nie schlafende Apps** eintragen.
6. Alle weiteren Updates kommen über den Update-Tab der App (One-Tap). Wo Android es verlangt, erscheint
   der Bestätigungsdialog.

## Erste Tests am Gerät

1. **Onboarding:** Alle fünf Punkte grün? Musste „Eingeschränkte Einstellungen zulassen“ genutzt werden?
2. **Visualizer:** rubato an, Spotify abspielen. Im Debug-Screen sollte der Visualizer „verbunden“ zeigen,
   ~20 Frames/s, Peak/RMS „ja“. Im Live-Screen bewegt sich das Spektrum. Bleibt es leer, liefert Session 0
   auf One UI vermutlich nur Stille: Log prüfen.
3. **EQ global:** Modus „Global“, im Live-Screen den Song-Slider Bass auf +6 dB ziehen. Ist das deutlich
   hörbar und zeigt der Effekt-Status „aktiv“, funktioniert der globale Modus.
4. **EQ Session:** Modus „Player-Session“, in Spotify Pause und wieder Play. Unter „Gemeldete
   Player-Sessions“ sollte Spotify auftauchen und der Effekt aktiv sein. Dann denselben +6-dB-Test machen.
5. **Kompensation**, für den Modus, der funktioniert: Kompensation **aus**, ruhigen, gleichmässigen Track
   abspielen, Bass-Slider auf +6 dB und zurück. Springt im Debug-Screen die Spalte „live“ beim Band 90
   um etwa +5 dB mit, misst der Visualizer **nach** dem EQ, dann Kompensation **an**. Bleibt sie ruhig,
   aus lassen. Gegenprobe: Bei falscher Einstellung „an“ laufen die automatischen Korrekturen über einige
   Minuten bis ans Maximum. Bei falscher Einstellung „aus“ bleiben sie bei etwa der Hälfte.
6. **Songwechsel/Pause:** Skippen, pausieren, Stille. Das Ereignis-Log zeigt Songstart/-ende. Die erste
   Korrektur kommt nach ~5 s.
7. **Cache:** Einen Song zweimal hören. Beim zweiten Mal steht sofort „Bekannter Song – gespeicherte
   Korrektur aktiv“.
8. **Notification:** Titel und Korrektur-Info sichtbar, Pause/Fortsetzen funktioniert.
9. **Update-Pfad:** Nach dem zweiten Release im Update-Tab installieren. Danach sollte „Update installiert –
   tippen zum Starten“ erscheinen.
10. **Export:** Einstellungen → „Als JSON teilen“.

## Bekannte Risiken

- **EQ-Modus auf One UI:** DynamicsProcessing auf Session 0 ist seit Android 9 „deprecated“ und kann von
  Hersteller-Builds ignoriert werden. Der Session-Modus braucht Player, die den Broadcast senden. Spotify
  tut das, viele andere Apps nicht. Läuft der Player schon, bevor rubato startet, fehlt die Session bis
  zum nächsten Play.
- **Visualizer auf Session 0** kann je nach Ausgabepfad (Offload, manche Bluetooth-Codecs, Samsung-Effekte)
  Stille liefern. Er hat nur 8 Bit, kein Fenster (Leakage) und wird pro Block auf den Peak normalisiert.
  Diese Fehler treffen Zielkurve und Messung gleich und heben sich daher weitgehend auf.
- **Kompensation** (vor/nach dem EQ) ist auf One UI unbekannt, siehe Test 5.
- **Latenz:** Der FFT-basierte DynamicsProcessing-EQ verzögert um einige 10 ms. Im Global-Modus kann das
  bei Videos die Lippensynchronität minimal verschieben.
- **Andere Effekte:** Dolby Atmos, Adapt Sound und Samsung-Equalizer liegen zusätzlich im Signalweg. Eine
  andere EQ-App mit gleicher Priorität kann die Kontrolle übernehmen (Status „keine Kontrolle“).
- **Hintergrund:** One UI kann den Dienst trotz Akku-Ausnahme beenden („Nie schlafende Apps“ hilft). Nach
  Update und Neustart muss man ihn per Tipp neu starten (Android-Regel für den Mikrofon-Typ).
- **Self-Update ohne Rückfrage** setzt voraus, dass die App sich selbst aktualisiert und
  „Unbekannte Apps installieren“ erlaubt ist. Sonst kommt der System-Dialog.
- **Bibliothek:** Podcasts und Hörbücher (> 30 s) fliessen derzeit in die Zielkurve ein.
- R8/Minify ist bewusst aus, damit das Release-Verhalten dem Debug-Build entspricht.
