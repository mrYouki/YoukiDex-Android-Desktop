<p align="center">
  <img src="https://raw.githubusercontent.com/mrYouki/YoukiDex-Android-Desktop/main/app/src/main/ic_launcher-playstore.png" width="100" alt="YoukiDEX Logo"/>
</p>

<h1 align="center">YoukiDEX</h1>

<p align="center">
  A full desktop experience layer for Android — no launcher swap needed.
</p>

<p align="center">
  <a href="https://github.com/mrYouki/YoukiDex-Android-Desktop/releases">
    <img src="https://img.shields.io/github/v/release/mrYouki/YoukiDex-Android-Desktop?style=for-the-badge" alt="Latest release"/>
  </a>
  <a href="https://github.com/mrYouki/YoukiDex-Android-Desktop/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/mrYouki/YoukiDex-Android-Desktop?style=for-the-badge" alt="License"/>
  </a>
  <a href="https://github.com/mrYouki/YoukiDex-Android-Desktop/releases/latest">
    <img src="https://img.shields.io/badge/Download-APK-brightgreen?style=for-the-badge&logo=android" alt="Download APK"/>
  </a>
  <img src="https://img.shields.io/badge/Android-11.0%2B-green?style=for-the-badge&logo=android" alt="Android"/>
</p>

---

## 🚀 Quick Start

Once installed and permissions are granted:

1. Open the **YoukiDEX** app and configure your basic settings
2. Navigate to your device's **notification/status bar**
3. Add the **YoukiDEX shortcut** tile to your quick settings panel
4. Tap the shortcut — and you're in! 🎉

---

## ⚠️ Important

Some features require granting elevated permissions via ADB. Without them, certain functions like secure settings or recent apps will not work.

```bash
adb shell pm grant com.youki.dex android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.youki.dex GET_USAGE_STATS allow
```

### 📦 Update System Change
Starting from v2.7, updates will be released in **small frequent batches** — minor version numbers like `2.7`, `2.8`, `2.9` are small incremental updates with individual fixes or additions. **Major version numbers** like `3.0` or `4.0` are big milestone releases with significant changes. This keeps the app improving steadily without waiting for large updates.

> Minor updates may arrive anywhere between **one week to one month** depending on availability — this project is developed by a student in their spare time, so patience is appreciated! 🎒

---

## 📋 Changelog

### ✨ v2.7 — Simple Update

**New Features:**

- **Wallpaper App Shortcut** — A new dock button that launches your preferred wallpaper app, configurable from Settings
- **Improved Google Cast Support** — Enhanced Cast stability and performance
- **Root Support (Magisk / KernelSU)** — Root is now detected and used automatically before falling back to Shizuku or ADB

**Bug Fixes:**

- **Forced Landscape now works correctly** — The desktop now properly enforces landscape orientation in all cases

**Improvements:**

- **Faster Launch** — Orientation decision is now cached on first run; cold-start is 200–400ms faster on all devices
- **Smoother Dock Animations** — Button animations now run on the GPU (RenderThread) instead of the main thread — no more jank on budget CPUs
- **Android 16 Ready** — Overlay window handling updated for API 35+ *(some edge cases still being worked on)*

**Changes:**

- Minimum Android raised from **8.0 → 11.0 (API 30)**
- **Re-added Arabic language support** — Arabic translations have been restored — required for reliable Shizuku support and modern overlay APIs. If you are on Android 10 or below, **v2.6** is the last version that supports your device.

---

### 🩹 v2.6 — Hotfix

**Fixed:**

- **`SecurityException` crash on Accessibility connect** — The most reported crash: app crashed immediately after granting Accessibility permission on Android 12+. Root cause was a BroadcastReceiver registered without `RECEIVER_EXPORTED` / `RECEIVER_NOT_EXPORTED` flag.
- **3 Kotlin type errors in `DockService`** — `minOf` operator precedence bug, unsafe calls on nullable `SeekBar?` / `ImageView?` / `ImageButton?` types, and `Int` vs `Long` mismatch in `postDelayed`.
- **`NotificationService` staying alive after app is closed** — The notification listener service was not receiving a stop signal when the app shut down, leaving notifications active in the background.
- **Quick Settings tile failing silently without permissions** — The tile attempted to enable Accessibility programmatically without checking for `WRITE_SECURE_SETTINGS`. It now detects missing permissions and shows an explanatory dialog before redirecting to the correct settings screen. Same check added for Notification Listener access.

---

### 🔥 v2.5 — Material DEX
> Major update — Material You improvements, Shizuku integration, window enhancements, and bug fixes

**New Features:**

- **Default Launcher Support** — YoukiDEX can now be set as your default home screen launcher so the desktop loads automatically on every boot
- **Shizuku Integration** — Replaces wireless ADB with a clean privileged shell bridge. Install Shizuku once via ADB, then everything is automatic
- **Home Screen Shortcut** — Automatically prompts to add a YoukiDEX shortcut to the home screen on first launch
- **Centered / Floating Dock** — New KDE-style centered dock mode with bottom margin, switchable from the classic edge-pinned Windows style
- **Fully Transparent Theme** — New theme option for a completely invisible dock background
- **Bubble Color Customization** — Choose between Material You dynamic colors or a fully custom color for dock button bubbles, with adjustable opacity
- **Hide Status Bar** — Hide the top Android status bar for a cleaner desktop look
- **Built-in Shell Terminal** — Run shell commands directly inside the app using ShellServer or Shizuku
- **Deep Shortcuts Support** — Long-press any app in the dock to access app-specific shortcuts
- **Material You Improvements** — Better dark surface handling, improved palette refresh on wallpaper/theme change, proper alpha support across all UI elements

**Bug Fixes:**

- **Fullscreen Fix** — Apps set to fullscreen mode now correctly launch in fullscreen
- **CPU / RAM Monitor Fix** — Resource monitor counter now resets correctly, fixing incorrect readings
- **Window Performance** — `TYPE_WINDOWS_CHANGED` accessibility events debounced (250ms), eliminating dozens of redundant calls per second
- **Notification Bubble** — Bubble color re-applied correctly after background resource changes
- **Context Menu** — Dock app context menu now centered on screen with proper window flags
- **Removed Arabic language support**

---

### 🔥 v2.1hotfix
> Critical bug fixes based on user reports

**Fixed:**

- **`UnsupportedOperationException: startActivityAndCollapse`** — crash on **Android 14+** when tapping the Quick Settings tile
  > Root cause: `startActivityAndCollapse(Intent)` was deprecated and fully blocked in Android 14 (API 34). Fixed by switching to `startActivityAndCollapse(PendingIntent)` on API 34+.

- **Dock disappearing completely on launch**
  > Root cause: `dex_mode_active` SharedPreference defaulted to `false`, forcing the dock overlay to `GONE` on every service start.

- **Dock hiding every time an app is opened**
  > Root cause: `LAUNCHER_PAUSED` broadcast fired on every `onPause()`, triggering `unpinDock()` on every app switch.

- **Dock service shutting down when disabling via tile**
  > Root cause: `disable_self` handler was killing the entire Accessibility Service. Now only hides the dock UI without terminating the service.

---

### v2.0
- Renamed project from SmartDock to **YoukiDEX**
- New package name: `com.youki.dex`
- Added Resource Monitor (CPU & RAM display)
- Added Quick Settings Panel with brightness and volume sliders
- Toggle Minimize: tap a running app in the taskbar to hide/restore it

---

## 📸 Screenshots

<p align="center">
  <img src="https://raw.githubusercontent.com/mrYouki/YoukiDex-Android-Desktop/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="100%"/>
</p>

---

## 📥 Download

| Source | Link |
|--------|------|
| 📦 GitHub Releases | [Download APK](https://github.com/mrYouki/YoukiDex-Android-Desktop/releases/latest) |
| 🔧 Obtainium | [Add via Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/mrYouki/YoukiDex-Android-Desktop) |

---

## 📋 Table of Contents

- [Features](#-features)
- [Requirements](#-requirements)
- [Permissions](#-permissions)
- [Known Issues](#-known-issues)
- [FAQ](#-faq)
- [Credits](#-credits)

---

## ✨ Features

- **Floating Dock** — Persistent customizable dock as a system overlay, always on top of any app
- **No Launcher Swap Needed** — Works on top of any existing launcher via Accessibility Service
- **Centered or Edge Dock** — Switch between KDE-style centered floating dock and classic edge-pinned dock
- **Hot Corners** — Trigger actions by moving to any corner of the screen
- **Freeform Window Support** — Launch apps in resizable floating windows
- **App Drawer** — Full-featured app list with search and sorting
- **Deep Shortcuts** — Long-press icons for app-specific quick actions
- **Icon Pack Support** — Compatible with third-party icon packs
- **Notification Panel** — View and dismiss notifications from the dock
- **System Tray** — Live battery, Wi-Fi, Bluetooth, and sound indicators
- **Custom Profile** — Set your own username and profile picture in the app drawer
- **Keyboard Shortcuts** — Hardware keyboard support for power users
- **Multi-Display Support** — Works across multiple connected screens
- **Appearance Theming** — Customize colors, transparency, and layout — including a fully transparent mode
- **Bubble Customization** — Material You dynamic colors or a fully custom color with adjustable opacity
- **Shizuku Integration** — Privileged shell access without keeping ADB connected
- **Sound Events** — Optional audio feedback for dock interactions
- **Resource Monitor** — Live CPU and RAM usage in the taskbar
- **Wallpaper App Shortcut** — A dock button that launches your preferred wallpaper app, configurable from Settings
- **Google Cast Support** — Improved Cast stability and performance
- **Root Support** — Automatic Magisk / KernelSU detection for elevated permissions

---

## 🔧 Requirements

| Requirement | Value |
|-------------|-------|
| Minimum Android | 11.0 (API 30) |
| Target Android | 14 (API 34) |
| Language | Kotlin 1.9.24 |
| Build Tool | Gradle |

---

## 🔐 Permissions

| Permission | Purpose |
|------------|---------|
| `SYSTEM_ALERT_WINDOW` | Draw the dock over other apps |
| `ACCESSIBILITY_SERVICE` | Detect system UI events |
| `PACKAGE_USAGE_STATS` | Show recent & running apps |
| `WRITE_SECURE_SETTINGS` | Apply system-level settings |
| `QUERY_ALL_PACKAGES` | List installed apps in the drawer |
| `NOTIFICATION_LISTENER` | Show notifications in the dock |

---

## ⚠️ Known Issues

### RedMagic & ZTE — Floating Windows not working
These manufacturers block Freeform/Floating Windows by default. To fix:
1. Enable **Developer Options** (tap Build Number 7 times)
2. In Developer Options enable:
   - **Force activities to be resizable**
   - **Enable freeform windows**
3. Go to **Settings → Display → Desktop Mode** and enable it
4. Reboot

> This is a manufacturer restriction, not a bug in YoukiDEX.

### Force Landscape not working on some apps
Apps that hardcode `portrait` orientation cannot be forced to landscape without root or system app privileges. This is an Android OS limitation.

---

## ❓ FAQ

**Q: Does it work without setting it as my default launcher?**
Yes! YoukiDEX runs as a system overlay — your current launcher stays untouched.

**Q: Why does the dock disappear after reboot?**
Make sure the Accessibility Service is enabled and set to auto-start in your device's battery/power settings.

**Q: WRITE_SECURE_SETTINGS isn't working?**
Grant it via ADB:
```bash
adb shell pm grant com.youki.dex android.permission.WRITE_SECURE_SETTINGS
```

**Q: What is Shizuku and do I need it?**
Shizuku gives YoukiDEX privileged shell access without keeping ADB connected wirelessly. You only need ADB once to start Shizuku, then it handles everything automatically. Optional but recommended.

**Q: Why isn't the full source code available on GitHub?**
This project is developed entirely on a mobile phone with limited resources. Uploading and managing a full codebase from a phone isn't practical, so only what's possible is shared. The project is still open source at heart — contributions and feedback are always welcome.

**Q: Google Play Protect blocks the APK?**
This happens because the APK is distributed outside the Play Store. Temporarily disable Play Protect to install, then re-enable it. The app is fully open source — you can review every line of code yourself.

**Q: The display size option in Advanced settings doesn't do anything — why?**
Display size control requires `WRITE_SECURE_SETTINGS`, which can only be granted via **Shizuku** or **ADB**. If Shizuku is not running, the option will appear in settings but will have no effect.

To fix it:
1. Install and start **Shizuku** on your device
2. Open YoukiDEX → grant Shizuku permission when prompted
3. The display size control will now work correctly

> Without Shizuku or ADB, Android does not allow third-party apps to change system display size.

**Q: Why was the minimum Android version raised to 11.0?**
A few reasons pushed this change:
- **Shizuku** works most reliably on Android 11+ due to how it hooks into the system shell
- The overlay window type the dock depends on is fully stable and guaranteed from API 30+
- Android 11 introduced better freeform window APIs that YoukiDEX now takes advantage of
- Supporting Android 8/9/10 was causing hard-to-maintain edge-case bugs alongside newer features

If you are on Android 10 or below, **v2.6** is the last supported version for your device.

**Q: I can't enable Accessibility on Android 13/14/15 — the toggle is blocked by the system?**
This is caused by Android's **Restricted Settings** feature, which blocks Accessibility access for APKs installed outside the Play Store.

Fix via ADB (one-time only):
```bash
adb shell appops set com.youki.dex REQUEST_INSTALL_PACKAGES allow
```
Then go back to **Settings → Accessibility** and enable YoukiDEX normally.

Alternatively, installing the APK directly via ADB skips the restriction entirely:
```bash
adb install YoukiDex.apk
```

**Q: Overlay windows / dock behave strangely on Android 16 — is this a known issue?**
Yes, this is a known issue. Android 16 (API 35+) introduced stricter rules around overlay window touch handling and inset management, which affects how the dock and some windows are drawn and interacted with. We are aware of it and working on fixes progressively — it will be fully resolved over the coming updates. Most core features still work normally in the meantime.

---

## 🚫 Not Planned

These features will **not** be added to YoukiDEX:

| Feature | Reason |
|---------|--------|
|  Blur / Background blur effects | Android blocks real-time blur on overlay windows without system-level privileges |
|  Google Play Store release | The app requires sensitive permissions that Google does not allow on the Play Store |
|  iOS / iPadOS support | Completely different OS — not possible |
|  Cloud sync for settings | A local Backup/Restore system already exists and is sufficient |
|  Multi-user support | Extremely complex and severely limited by Android system-level permissions |
|  Task Manager | *(Planned, not yet implemented)* |
|  Widget support in the dock | *(Planned, not yet implemented)* |
|  Built-in File Manager | *(Planned, not yet implemented)* |
|  wallpaper engine | ✅ Added in v2.7 (Live Wallpaper) |
---

## 🙏 Credits

**Smart Dock** — The original project this is based on and heavily modified from.

The open-source Android community for tools, libraries, and inspiration that made this project possible.

---

## ⚖️ Disclaimer

This project is an independent open-source tool and is not affiliated with or endorsed by any company or organization.

---

<p align="center">Made with by <a href="https://github.com/mrYouki">mrYouki</a></p>
