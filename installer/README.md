# KDE Connect Advanced Suite Installer

Automated, cross-distro Linux installer and streamer daemon for the enhanced **KDE Connect** ecosystem.

---

## Quick Start

### 1. Run the Installer
```bash
./install.sh
```

### 2. What the Installer Does
1. **Pre-Flight Environment Detection**: Detects your Desktop Environment (KDE/GNOME/Sway/Hyprland), Display Server (Wayland vs. X11), Audio Server (PipeWire vs. PulseAudio), and Package Manager (`pacman`, `apt`, `dnf`, `zypper`).
2. **Interactive Sudo Authentication**: Prompts for credentials cleanly upfront before background processes or spinners start.
3. **Installs System Dependencies**: Provisions KDE Connect, OpenSSH, Python libraries (`Pillow`, `psutil`, `dbus`, `gobject`), GStreamer plugins, `pipewire-pulse`, `wl-clipboard`, `xclip`, and `sqlite3`.
4. **Configures Firewall**: Opens required ports for KDE Connect (`1714:1764`), Streamer (`59001`), Microphone (`59002`), and SSH (`22`) in `ufw` or `firewalld`.
5. **Deploys Background Streamer**: Installs daemon to `~/.local/bin/kdeconnect-streamer` and enables `kdeconnect-streamer.service` in systemd user mode.
6. **Auto-Configures ADB (Optional)**: Automatically maps reverse ports and deploys client APK if an Android phone is connected via USB.

---

## Service Management

| Action | Command |
| :--- | :--- |
| **Check Streamer Status** | `systemctl --user status kdeconnect-streamer` |
| **Restart Streamer** | `systemctl --user restart kdeconnect-streamer` |
| **Stop Streamer** | `systemctl --user stop kdeconnect-streamer` |
| **View Live Logs** | `journalctl --user -u kdeconnect-streamer -f` |

---

## Uninstallation

To cleanly remove background services, firewall rules, the desktop package, and phone client:
```bash
./uninstall.sh
```

The uninstaller provides an interactive, safe removal process:
- Stops & disables streamer background services and audio sinks.
- Closes custom firewall rules (`59001`, `59002`, `1714:1764`).
- Prompts to uninstall the desktop `kdeconnect` package via your system package manager (`pacman`, `apt`, `dnf`, `zypper`).
- Optionally clears pairing configs in `~/.config/kdeconnect`.
- Optionally uninstalls the client APK from any connected Android device.
