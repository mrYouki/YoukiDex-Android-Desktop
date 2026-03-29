<p align="center">
  <img src="https://raw.githubusercontent.com/mrYouki/YoukiDex-Android-Desktop/main/icon.png" width="100" alt="YoukiDEX Logo"/>
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
  <img src="https://img.shields.io/badge/Android-8.0%2B-green?style=for-the-badge&logo=android" alt="Android"/>
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

---

## 📋 Changelog

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
  <img src="https://raw.githubusercontent.com/mrYouki/YoukiDex-Android-Desktop/main/Screenshot_1.png" width="45%"/>
  &nbsp;
  <img src="https://raw.githubusercontent.com/mrYouki/YoukiDex-Android-Desktop/main/Screenshot_2.png" width="45%"/>
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
- **Built-in Shell Terminal** — Run shell commands directly inside the app
- **Sound Events** — Optional audio feedback for dock interactions
- **Resource Monitor** — Live CPU and RAM usage in the taskbar

---

## 🔧 Requirements

| Requirement | Value |
|-------------|-------|
| Minimum Android | 8.0 (API 26) |
| Target Android | 14 (API 34) |
| Language | Kotlin 1.9.20 |
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

---

## 🚫 Not Planned

These features will **not** be added to YoukiDEX:

| Feature | Reason |
|---------|--------|
| 🌫️ Blur / Background blur effects | Android blocks real-time blur on overlay windows without system-level privileges |
| 🏪 Google Play Store release | The app requires sensitive permissions that Google does not allow on the Play Store |
| 🍎 iOS / iPadOS support | Completely different OS — not possible |
| ☁️ Cloud sync for settings | A local Backup/Restore system already exists and is sufficient |
| 👥 Multi-user support | Extremely complex and severely limited by Android system-level permissions |
| 💻 Built-in Terminal | *(Planned, not yet implemented)* |
| 📋 Task Manager | *(Planned, not yet implemented)* |
| 🧩 Widget support in the dock | *(Planned, not yet implemented)* |
| 📁 Built-in File Manager | *(Planned, not yet implemented)* |

---

## 🙏 Credits

**Smart Dock** — The original project this is based on and heavily modified from.

The open-source Android community for tools, libraries, and inspiration that made this project possible.

---

## ⚖️ Disclaimer

This project is an independent open-source tool and is not affiliated with or endorsed by any company or organization.

---

<p align="center">Made with by <a href="https://github.com/mrYouki">mrYouki</a></p>
