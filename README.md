# KDE Connect

A high-performance edition of **KDE Connect** providing wireless desktop integration between your computer (Windows & Linux) and Android device.

---

## Features

### 1. Zero-Copy 60 FPS Screen Mirroring & Audio Streaming
- **Ultra-Low Latency Video**: Hardware-accelerated desktop capture streaming up to 60+ FPS over local Wi-Fi or USB ADB.
- **Desktop Speaker Audio**: Real-time stereo audio loopback streaming directly to your phone's speaker or headphones.
- **Bi-directional Phone Mic**: Use your phone as a high-clarity wireless PC microphone (`Phone_Microphone`).
- **Configurable Recording Path**: Select custom storage directories on your PC for recorded stream videos.

### 2. Floating Action Bubble & Quick Actions
- **Smart Edge Docking & Translucency**: Dims to translucent opacity when idle and smoothly docks to screen edges. Remains docked safely outside navigation bars in portrait and landscape modes.
- **Mobi-Style Action Cards**: Instant overlay shortcuts for media controls, mouse pointer, screenshot capture, clipboard, and apps.
- **Over-the-App Media Controller**: Live playback controls (Play/Pause, Next, Previous, Seekbar timeline, and Repeat/Loop) with Windows WinRT / Linux MPRIS integration for Spotify, Brave, Chrome, VLC, and Edge.
- **Hardware Volume & PC Master Sink**: Adjust your computer's master volume directly from the floating overlay or phone hardware volume rocker.

### 3. Continuous Background Clipboard Sync
- **Two-Way Sync**: Automatically synchronizes copied text between your PC and phone.
- **Persistent Out-of-App Capture**: Background synchronization keeps clipboard history active even when the app is minimized or the action button is toggled off.

### 4. Interactive Live Terminal
- **Native Shell Integration**: Interactive PTY shell running PowerShell on Windows and Bash on Linux.
- **Convenient Defaults**: Automatically opens in your user profile home directory.

### 5. Task Manager & Remote System Monitor
- **Live System Metrics**: Real-time CPU usage, RAM utilization, Disk partitions, GPU load, and network transfer rates.
- **Process & Service Control**: View and inspect active processes, background services, and desktop apps with one-tap remote termination.

### 6. File Transfer & Presentation Remote
- **Wireless File Browser**: Browse and transfer files seamlessly with SFTP storage integration.
- **Presentation Pointer**: Slide navigator with precision gyroscope laser pointer.

---

## Downloads & Installation

> [!TIP]
> **Direct Download Link (Installers, Binaries & APKs):**
> [Download KDE Connect Suite (MediaFire)](https://www.mediafire.com/folder/tntbb2umeo9d7/KDEConnect)

---

### A. Linux Installation (Arch, CachyOS, Ubuntu, Debian, Fedora, openSUSE)

#### 1. Clone the Repository
```bash
git clone https://github.com/Vallabh2006/KDEConnect.git
cd KDEConnect
```

#### 2. Run the Automated Installer
```bash
chmod +x installer/install.sh installer/uninstall.sh
./installer/install.sh
```

**What the installer does automatically:**
- Detects your package manager (`pacman`, `apt`, `dnf`, `zypper`).
- Installs required dependencies (GStreamer PipeWire plugins, Python libraries, OpenSSH, SSHFS).
- Configures and enables the systemd user service (`kdeconnect-streamer.service`).
- Configures firewall rules (`ufw` or `firewalld`) for ports 1714-1764, 59001, and 59002.

#### 3. Manage the Background Streamer Service
```bash
# Check service status
systemctl --user status kdeconnect-streamer.service

# Restart service if needed
systemctl --user restart kdeconnect-streamer.service
```

#### 4. Uninstallation (Optional)
```bash
./installer/uninstall.sh
```

---

### B. Windows Installation

#### 1. Download & Run Installer
- Download `KDEConnect-Setup-x64.exe` from the [MediaFire Folder](https://www.mediafire.com/folder/tntbb2umeo9d7/KDEConnect).
- Run the installer. It will automatically bundle VC++ runtime, configure Windows Defender Firewall rules, and register startup services.

#### 2. Launch KDE Connect
- Open KDE Connect from your Start Menu.
- Pair with your Android phone over the same Wi-Fi network.

---

### C. Android App Installation

#### 1. Download APK
- Download `KDEConnect.apk` from the [MediaFire Folder](https://www.mediafire.com/folder/tntbb2umeo9d7/KDEConnect) or install from `apk/KDEConnect.apk`.

#### 2. Install via ADB (Alternative)
```bash
adb install -r apk/KDEConnect.apk
```

#### 3. Grant Permissions
- Open the app and grant **Display over other apps** (for the floating bubble overlay) and **Notification Access** (for media and notification sync).

---

## Network & Firewall Ports

Ensure the following ports are allowed on your local network:

| Port | Protocol | Service / Purpose |
| :--- | :--- | :--- |
| **1714 - 1764** | UDP / TCP | Core KDE Connect discovery, pairing, and notifications |
| **22** | TCP | OpenSSH / SFTP remote filesystem & terminal |
| **59001** | TCP / HTTP | Screen stream (60 FPS MJPEG), Audio loopback, Task Manager API |
| **59002** | TCP | Phone microphone passthrough |

---

## Troubleshooting & FAQ

### 1. Devices Cannot Discover Each Other
- Ensure both the phone and computer are connected to the same Wi-Fi network (or connected via USB tethering/ADB).
- Verify that your network is set to "Private" in Windows Settings and that ports 1714-1764 are not blocked by third-party firewalls or VPNs.

### 2. Screen Mirroring / Audio Stream Shows Disconnected
- Verify the desktop streamer daemon is running:
  - **Linux**: Check service status with `systemctl --user status kdeconnect-streamer.service`.
  - **Windows**: Verify `kdeconnect-streamer.exe` in Task Manager or run `installer/tools/streamer_silent.vbs`.

### 3. Clipboard Not Updating in Background
- Ensure **Display over other apps** permission is granted in Android Settings -> Apps -> KDE Connect. This enables background clipboard capture on modern Android versions.

### 4. ADB Reverse Setup (USB Cable Connection)
- When using USB without Wi-Fi, run:
  ```bash
  adb reverse tcp:59001 tcp:59001
  adb reverse tcp:59002 tcp:59002
  ```

---

## License

This project is licensed under the GNU General Public License (GPLv2 / GPLv3).
