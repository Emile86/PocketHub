# PocketHub

> ⚠️ **Root required.** PocketHub uses root access (Magisk) for almost everything: performance profiles, fan control, input, sound and screenshots. Without root, only button remapping and the volume panel work. If you want to root your AYANEO Pocket DS, follow this guide: **https://github.com/Emile86/dsroot**

> 💡 **Works best with [PocketHub Launcher](https://github.com/Emile86/PocketHub-Launcher).** The two apps are designed to work together on both screens, so we recommend installing the launcher too.

PocketHub is a control hub for the dual-screen **AYANEO Pocket DS** . It brings performance, cooling, input, sound and screen controls together in one app, built for both screens and for use with the controller.

## Why PocketHub

- **Fully replaces the stock AYANEO app.** Everything you used it for is here, and the AYANEO button opens and hides PocketHub.
- **Much deeper settings.** Per-cluster CPU limits, GPU limits and lock, custom fan curves, game profiles with auto-tuning and per-screen sound go far beyond the stock options.
- **Fixes problems found in the stock app.**
- **Lighter on resources.** It uses less CPU and memory, keeps one root session open instead of spamming root requests, and keeps most icons still to save power.

## Main features

### ⚡ Performance
- **Five profiles:** Eco, Balanced, Streaming, Gaming and Max.
- **Full profile editor:** scheduling mode, CPU limit per cluster, GPU limit, GPU lock, default fan mode.
- **Turbo boost:** keeps CPU and GPU at the top of the profile's limits for steadier FPS. It backs off automatically if the chip gets too hot.
- **Restored after a reboot:** the last profile and fan mode come back automatically.

### 🌀 Fan & cooling
- **Fan modes:** Off, Low, High, Max or your own custom curve.
- **Overheat protection:** when the fan is off, it steps in automatically if the temperature stays high, then steps back once things cool down.
- **Fan while charging:** optionally keeps the fan running while the device charges with the screen off.

### 🎮 Game tuning
- **Your own profile for every game**, even for a single game inside GameHub, Winlator or an emulator. PocketHub recognises the running `.exe` or ROM.
- **Automatic switching:** the game's profile turns on when the game starts, and your previous profile returns when it closes.
- **Auto-tune:** finds the lowest CPU/GPU limits that keep your frame rate. Choose the target FPS (20–165 or Auto) and what matters besides FPS: a quiet fan, a temperature limit, or FPS only.
- **Adaptive mode:** limits go up and down live to hold the frame rate with the least heat.
- **Quiet mode:** every profile runs one noise level lower.
- **Quick profile window:** switch profiles over the running game with a single button press.
- **Launchers are ignored:** home screens are never treated as games.

### 📊 Dashboard
- **Live FPS chart** of the last minute.
- **CPU and GPU** load, clocks and temperatures, with colour warnings.
- **Battery:** charge level and an estimate of the time left or time to full.
- **Memory and storage** usage.
- **One-tap controls** for fan, refresh rate, brightness, sound and screenshots.

### 🖥️ Dual screens
- **Screen control:** choose which screens are on (both, top or bottom).
- **Input for the top screen:** a cursor, or a touchpad on the lower screen.
- **Controller focus:** follows your last touch, or is locked to one screen.
- **Brightness per screen.**
- **Screenshots of both screens at once:** save the top, the bottom or both. Nothing from PocketHub appears in the shot.
- **External monitor:** handheld + monitor, or monitor only, with a resolution picker.

### ⌨️ Keyboard, touchpad & PC keyboard
- **Dual-screen keyboard** on the lower screen or on the active screen:
  - 6 languages, including German QWERTZ, French AZERTY and Chinese pinyin;
  - 12 themes;
  - accents on long press;
  - clipboard history, emoji, arrow keys and Tab.
- **Touchpad:** turns the lower screen into a mouse touchpad with click, right-click, scrolling and drag.
- **PC keyboard:** a full computer layout with Esc, F1–F12, Ctrl / Alt / Win and arrows. Modifiers are sticky, so shortcuts like Ctrl+C or Alt+Tab work.

### 🔊 Sound
- **Separate volume for each screen:** overall, upper and lower.
- **Per-app volume:** a slider for every app that is playing.
- **Equalizer:** presets, custom bands, extra gain with a limiter, and bass. Games can have their own equalizer.
- **PocketHub volume panel:** replaces Android's volume popup, on the screen you choose.

### 🕹️ Buttons & controller
- **Button remapping:** each extra button gets its own short, long and double press.
- **Remap actions:**
  - Back, Home and Recent apps;
  - screenshot;
  - profile switch and the dual-screen controls;
  - launching an app, and more.
- **Ready-to-use defaults** after installation.
- **Controller settings:** Xbox (XInput) or AYANEO (DInput) mode, vibration strength, and Xbox or Japanese button layout.

### 📱 Everyday tools
- **Favorites:** your apps one tap away. Each one can open on the top or bottom screen.
- **Quick settings:** Wi-Fi, Bluetooth, rotation, airplane mode, location, Do not disturb, battery saver, dark theme and force landscape.
- **Memory:** a swap file of 1–16 GB.
- **Wi-Fi sleep:** turns Wi-Fi off when the screen is off.

### 🎨 Look & setup
- **Many complete themes:** each one changes cards, buttons, sliders and icons, not just colours.
- **8 languages:** English, Russian, Dutch, Spanish, German, French, Italian and Chinese.
- **First-launch setup wizard:** sets everything up in a few steps, with the recommended answers already selected.
- **Built-in help guide** with search.
