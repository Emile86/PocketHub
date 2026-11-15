# PocketHub

**PocketHub is an Android control hub and dual-screen overlay for the AYANEO Pocket DS.** It brings handheld controls, system monitoring, display tools and a dual-screen keyboard together in one interface.

> PocketHub is designed for AYANEO Pocket DS. Some system-level features depend on firmware, Android permissions, and root access.

## Features

- **System dashboard:** CPU and GPU load, frequency and temperature; fan RPM; battery; RAM and storage swap; display refresh rate.
- **Performance profiles:** Eco, Balanced, Streaming, Gaming and Max, with available fan and CPU/GPU tuning controls.
- **Fan controls:** view measured RPM and select supported fan modes independently of the performance profile.
- **Dual-screen controls:** choose the active display, configure controller focus, and select touchpad or cursor behavior.
- **Controller options:** adjust vibration and choose Xbox or Japanese button layout where supported by the device firmware.
- **Brightness and audio:** adjust display brightness and, with the audio service enabled, control sound by screen.
- **Dual-screen keyboard:** choose bottom-screen or active-screen placement, keyboard languages and themes, key size, toolbar visibility and haptic feedback.
- **Screenshots:** capture both displays and save either screen or both.
- **System tools:** CPU scheduling and storage swap controls on supported firmware.
- **Personalization:** eight interface languages, color accents, visual designs and startup options.

## Device and requirements

- **Target device:** AYANEO Pocket DS (dual-screen Android handheld).
- **Android:** API 26 or later (Android 8.0+); build uses Android SDK 35.
- **JDK:** 17.
- Basic interface features do not require root. Advanced fan, performance, display, audio and swap operations may need root and can vary by firmware.
- To use PocketHub Keyboard, enable it and select it in Android's keyboard/input-method settings. Accessibility-based button handling requires enabling PocketHub's accessibility service.

PocketHub is not guaranteed to work on other Android devices. AYANEO firmware updates may change the behavior of manufacturer-specific controls.

## Build the debug APK

Open the project in Android Studio, or on Windows run:

```powershell
.\gradlew.bat assembleDebug
```

The APK is created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Android Studio will install the required Android SDK components if they are not already present. This debug APK is suitable for development and sideloading; a release build should be signed with your own release key.

## Install with ADB

With USB debugging enabled and the device authorized:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Source and attribution

PocketHub is distributed under the Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Please retain the copyright and attribution notices when redistributing the source or a derivative build.

**AYANEO** and **Pocket DS** are names of their respective owners. PocketHub is an independent community project and is not affiliated with or endorsed by AYANEO.

## Search terms

AYANEO Pocket DS, PocketHub, Android handheld overlay, dual-screen Android control center, AYANEO fan control, gaming handheld performance profiles, CPU GPU monitoring, dual-screen keyboard, Android handheld utility.

---

# PocketHub (на русском)

PocketHub — приложение-панель управления и оверлей для двухэкранной Android-консоли **AYANEO Pocket DS**. Оно объединяет мониторинг системы, профили производительности, управление вентилятором и экранами, настройки контроллера, звук и клавиатуру для двух дисплеев.

Для базовой работы root не нужен. Расширенные системные функции могут требовать root-доступ и зависят от версии прошивки. Чтобы пользоваться клавиатурой, включите PocketHub Keyboard и выберите её в настройках способов ввода Android.

Сборка APK в Windows: `.\gradlew.bat assembleDebug`. Готовый APK находится в `app/build/outputs/apk/debug/app-debug.apk`.
