# PocketHub

Control hub and dual-screen overlay for the AYANEO Pocket DS (Android 13, root).

## Project layout

```
PocketHub/
├─ app/
│  ├─ build.gradle.kts        version, SDK levels, signing
│  ├─ debug.keystore          signing key (keeps updates installable over older builds)
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ assets/              pockethub_swap.sh (swap file setup)
│     ├─ res/                 icon, strings, accessibility and keyboard configs
│     └─ java/com/example/ayaneooverlay/
│        ├─ MainActivity.kt                 main UI: pages, profiles, settings, themes
│        ├─ HelpContent.kt                  help texts in all 8 languages
│        ├─ LivingIcons.kt                  animated icons, aurora background, FPS chart
│        ├─ DualScreenImeService.kt         PocketHub Keyboard (IME)
│        ├─ FreeTypingInjector.kt           keyboard typing without a text field
│        ├─ PcKeyboardOverlayService.kt     PC keyboard overlay
│        ├─ TouchpadOverlayService.kt       on-screen touchpad + cursor
│        ├─ RootInputBridge.java            root helper that injects keys/touches
│        ├─ RootAudioBridge(.java/Client.kt) root helper for per-screen audio
│        ├─ ScreenAudioVolumeService.kt     per-screen volume
│        ├─ FanGuard.kt                     overheat protection loop
│        ├─ TurboBoost.kt                   Turbo boost loop
│        ├─ PocketHubSwap.kt                swap file
│        ├─ PocketHubButtonTakeover.kt      AYANEO button / accessibility takeover
│        ├─ AyaButtonAccessibilityService.kt
│        └─ PocketHubBootReceiver.kt        start-up after boot
├─ gradle/wrapper/            Gradle wrapper
├─ build.gradle.kts, settings.gradle.kts, gradle.properties
└─ gradlew.bat
```

## Build

Android Studio: open this folder → Build → Build APK(s).
Command line:

```bat
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`

Install: `adb install -r app\build\outputs\apk\debug\app-debug.apk`
