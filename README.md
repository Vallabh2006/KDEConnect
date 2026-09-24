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

### 1. Floating Action Button & Productivity Shortcuts
- **Idle State, Edge Tucking & Landscape Snapping**: Automatically dims to translucent opacity and smoothly tucks halfway into the screen edge when inactive for 3.5s; responds to orientation changes and automatically stays docked at the far edge in landscape mode.
- **Mobi-Style Linear Vertical Emergence**: Tapping the bubble reveals clean, circular Material3 action cards arranged in a vertical stack with staggered spring animations.
- **Over-the-Display Media & Volume Control Overlay**: Floating draggable media card featuring track seek position slider (0–duration), like/dislike buttons, previous, play/pause, next, loop repeat mode toggle, real-time PC master volume seekbar (0–100%), mute toggle, and quick volume presets (-10, 30%, 60%, 100%, +10).
- **Over-the-Display Remote Pointer & Simultaneous Drag**: Floating touchpad overlay with multi-touch support, tap-to-click, and continuous hold-and-drag mouse buttons (left/right click held down while moving cursor simultaneously).
- **Phone-to-PC Microphone Streaming**: Stream phone mic audio in real-time straight to your computer's virtual microphone (`Phone_Microphone`) over high-speed TCP/HTTP.
- **PC Screenshot to Phone Gallery**: One-tap screenshot capture on PC that automatically streams the PNG image to your phone and saves it straight to your gallery (`Pictures/KDEConnect`).
- **Continuous Background Clipboard Sync**: Active background clipboard polling that automatically captures text copied on the phone and propagates it to the PC clipboard and history without manual intervention.
- **Daily One-Tap Quick Actions**: Media & volume control, microphone streaming, remote pointer, clipboard sync, screenshot-to-gallery, screen lock, screen mirroring, task manager, and terminal.
- **Persistent Background Daemon**: Configured with `stopWithTask="false"` and sticky foreground service so clearing recents never kills the floating button.
- **PC Quick Restart (`Win + C`)**: Instantaneous desktop service restart, PipeWire stream reset, and automatic ADB port forwarding via `/home/vallabh/Scripts/kdeconnect.sh`.

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
adb install -r apk/kdeconnect-android-debug.apk
# If adb is in Android SDK platform-tools:
~/Android/Sdk/platform-tools/adb install -r apk/kdeconnect-android-debug.apk
```

---

## Network & Port Map

| Port | Protocol | Purpose |
| :--- | :--- | :--- |
| **1714 - 1764** | UDP / TCP | Core KDE Connect discovery, pairing, and packet routing |
| **22** | TCP | OpenSSH service (SFTP file browsing & secure remote shell) |
| **59001** | TCP / HTTP | Streamer daemon (Screen mirror, audio stream, Task Manager API, Live Terminal) |
| **59002** | TCP (Raw) | Ultra-low latency phone microphone passthrough (`Phone_Microphone`) |
