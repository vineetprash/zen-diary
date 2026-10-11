# Zen Diary

Private, offline-first mood diary for Android (Kotlin, Jetpack Compose, Material 3). Min SDK 26.


## Build
```
gradle wrapper --gradle-version 8.9     # once, then commit the wrapper
./gradlew :app:assembleDebug
```

## Signing & automatic releases (GitHub Actions)
1. `./scripts/generate-keystore.sh` - creates `zen-diary-release.jks` and prints the values to add as repo secrets:
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
2. **Back up the .jks and passwords somewhere safe.** Lose it and nobody can update without uninstalling.
3. Push to `main`: `.github/workflows/release.yml` runs unit tests, builds a signed APK and publishes a GitHub Release
   (`v1.0.<run number>`). `versionCode` = run number, always increasing, signed with the same key -> installs as an update.

## Google Drive backup - one-time setup
1. Google Cloud Console -> new project -> enable **Google Drive API**.
2. OAuth consent screen: External, add yourself (and testers) as test users. Scope: `.../auth/drive.appdata`
   (non-sensitive: only a hidden per-app folder, no Google security review needed).
3. Credentials -> **OAuth client ID -> Android**: package `app.zendiary`, SHA-1 of your **release** keystore
   (printed by the keystore script). Add a second client for the debug keystore SHA-1 if you test debug builds.
4. In the app: Settings -> Connect Google Drive. Backups are visible only to the app (not in the Drive UI).

## Architecture
Single Gradle module, package-per-layer/feature, manual DI in `AppContainer`.
- `data/` Room + SQLCipher DB, encrypted image store, DataStore settings, repositories
- `domain/` pure logic (streaks, stats, cycle prediction) - unit-testable
- `io/export`, `io/importer` - **Open/Closed**: implement `Exporter` / `Importer`, add one line in `AppContainer`
- `ui/insights/StatsSections` - add a chart card = add one object to the list
- `backup/`, `work/`, `lock/`, `widget/`

## Formats
- `.zdb` lossless backup (ZIP: manifest.json + images), optional password encryption (PBKDF2 + AES-GCM)
- CSV / TXT: plain text, or a ZIP with `images/` when photos are included
- PDF: readable journal; a hidden copy of the data is appended so Zen Diary can re-import its own PDFs
- Import: all of the above + **Daylio CSV** and **Daylio .daylio backup (with photos)**
- Re-importing never duplicates (stable per-entry ids; Daylio entries are matched by content)

## Known limits / honest notes
- Daylio `.daylio` layout is undocumented; the importer follows the community-known layout (ZIP -> Base64 JSON,
  photos under `assets/`) and reports anything it can't match. 
- PDFs from other apps can't be imported
- UI strings are inline in Kotlin, not yet in `strings.xml` (localization pending).
