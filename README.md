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
  <a href="https://github.com/mrYouki/YoukiDex-Android-Desktop/releases/tag/v2.0">
    <img src="https://img.shields.io/badge/Download-APK-brightgreen?style=for-the-badge&logo=android" alt="Download APK"/>
  </a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-green?style=for-the-badge&logo=android" alt="Android"/>
</p>

---

## ⚠️ Important

Some features require granting elevated permissions via ADB. Without them, certain functions like secure settings or recent apps will not work.

```bash
adb shell pm grant com.youki.dex android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.youki.dex GET_USAGE_STATS allow
```

---

## 📸 Screenshots

<p align="center">
  <img src="https://raw.githubusercontent.com/mrYouki/YoukiDex-Android-Desktop/main/Screenshot_1.png" width="90%"/>
</p>
<p align="center">
  <img src="https://raw.githubusercontent.com/mrYouki/YoukiDex-Android-Desktop/main/Screenshot_2.png" width="90%"/>
</p>

---

## 📥 Download

| Source | Link |
|--------|------|
| 📦 GitHub Releases | [Download APK](https://github.com/mrYouki/YoukiDex-Android-Desktop/releases/tag/v2.0) |
| 🔧 Obtainium | [Add via Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/mrYouki/YoukiDex-Android-Desktop) |

---

## 📋 Table of Contents

- [Features](#-features)
- [Requirements](#-requirements)
- [Permissions](#-permissions)
- [FAQ](#-faq)
- [Credits](#-credits)

---

## ✨ Features

- **Floating Dock** — Persistent customizable dock as a system overlay, always on top of any app
- **No Launcher Swap Needed** — Works on top of any existing launcher via Accessibility Service
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
- **Appearance Theming** — Customize colors, transparency, size, and layout
- **Sound Events** — Optional audio feedback for dock interactions

---

## 🔧 Requirements

| Requirement | Value |
|-------------|-------|
| Minimum Android | 7.0 (API 24) |
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

## ❓ FAQ

**Q: Does it work without setting it as my default launcher?**
Yes! YoukiDEX runs as a system overlay — your current launcher stays untouched.

**Q: Why does the dock disappear after reboot?**
Make sure the Accessibility Service is enabled and set to auto-start in your device's battery settings.

**Q: WRITE_SECURE_SETTINGS isn't working?**
Grant it via ADB:
```bash
adb shell pm grant com.youki.dex android.permission.WRITE_SECURE_SETTINGS
```

---

## 🙏 Credits

**Smart Dock** — The original project this is based on and heavily modified from.

The open-source Android community for tools, libraries, and inspiration that made this project possible.

---

## ⚖️ Disclaimer

This project is an independent open-source tool and is not affiliated with or endorsed by any company or organization.

---

<p align="center">Made with ❤️ by <a href="https://github.com/mrYouki">mrYouki</a></p>
