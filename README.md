# KDE Connect Enhanced Suite

A polished, high-performance edition of **KDE Connect** featuring native Android client enhancements, streamlined menu navigation, real-time desktop streaming, remote administration tools, and an automated Linux installer.

---

## Main Menu Layout & Features

The Android client features a clean, curated 2-column action grid organized logically for everyday productivity:

| Column 1 | Column 2 |
| :--- | :--- |
| **Files**<br>Remote file browser & wireless transfer | **Clipboard**<br>Two-way sync, history & custom clip composer |
| **Stream**<br>Zero-copy screen mirroring & bidirectional audio | **Presentation**<br>Slide control & precision gyroscope laser pointer |
| **Task Manager**<br>Live CPU/GPU/RAM metrics & process inspector | **Live Terminal**<br>Interactive PTY shell & remote execution |
| **Logs**<br>Live network packet inspector & diagnostics | **Multimedia**<br>Remote media playback & default audio output volume |

---

## Key Highlights

### 1. Floating Action Button & Shortcuts
- **Customizable Overlay**: Fast floating bubble accessible from anywhere on the phone to trigger remote mousepad, clipboard sync, media controls, presentation, screen mirror, or custom commands.
- **Drag-to-Close Dismissal**: Drag down to a highlighted bottom dismissal target to easily dismiss the floating button with haptic feedback.
- **Quick Settings & Drawer Tile**: Toggle on/off seamlessly via the Android Quick Settings tile or the app navigation drawer.
- **Permission Management**: Guided setup for "Display over other apps" overlay permission.

### 2. Multimedia Default Output Volume Control
- Volume slider and hardware volume keys in the Multimedia screen automatically adjust the **currently active computer audio output sink** (Master / Speakers / Headphones) by default, with seamless fallback to player volume.

### 3. Fixed Adaptive Icon & UI Polish
- Calibrated Android adaptive icon safe-zone margins so the emblem is completely intact on all launcher masks (circular, squircle, pebble) without cut-off corners.

### 4. Automated Cross-Distro Linux Installer & Uninstaller
- **Broad Distro Support**: Works out of the box on Arch/CachyOS (`pacman`), Ubuntu/Debian (`apt`), Fedora (`dnf`), and openSUSE (`zypper`).
- **Interactive Sudo Handling**: Prompts for credentials cleanly upfront without terminal output flicker or background spinner interference.
- **Comprehensive Uninstaller**: `./installer/uninstall.sh` cleanly stops background daemons, cleans firewall rules, prompts to remove the desktop package, and clears local pairing data.

---

## Quick Setup

### 1. Linux Desktop Host Setup
Run the automated installer on your Linux PC:
```bash
./installer/install.sh
```

To uninstall services and restore system defaults:
```bash
./installer/uninstall.sh
```

### 2. Android Client Installation
Transfer and install the APK on your device:
- [`apk/KDEConnect.apk`](apk/KDEConnect.apk)
- [`apk/kdeconnect-android-debug.apk`](apk/kdeconnect-android-debug.apk)

Or install directly via USB / ADB:
```bash
adb install -r apk/KDEConnect.apk
```

---

## Network & Port Map

| Port | Protocol | Purpose |
| :--- | :--- | :--- |
| **1714 - 1764** | UDP / TCP | Core KDE Connect discovery, pairing, and packet routing |
| **22** | TCP | OpenSSH service (SFTP file browsing & secure remote shell) |
| **59001** | TCP / HTTP | Streamer daemon (Screen mirror, audio stream, Task Manager API, Live Terminal) |
| **59002** | TCP (Raw) | Ultra-low latency phone microphone passthrough (`Phone_Microphone`) |
