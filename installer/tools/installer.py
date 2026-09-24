import os
import sys
import time
import shutil
import subprocess
import threading
import itertools
from pathlib import Path

C_RESET   = "\033[0m"
C_BOLD    = "\033[1m"
C_DIM     = "\033[2m"
C_RED     = "\033[31m"
C_GREEN   = "\033[32m"
C_YELLOW  = "\033[33m"
C_BLUE    = "\033[34m"
C_CYAN    = "\033[36m"
C_WHITE   = "\033[37m"

SPINNER_BRAILLE = ["-", "\\", "|", "/"]
CHECK_ICON      = f"{C_GREEN}[OK]{C_RESET}"
CROSS_ICON      = f"{C_RED}[FAIL]{C_RESET}"
WARN_ICON       = f"{C_YELLOW}[WARN]{C_RESET}"
INFO_ICON       = f"{C_CYAN}[INFO]{C_RESET}"
ARROW_ICON      = f"{C_CYAN}->{C_RESET}"

PKG_MAP = {
    "pacman": {
        "kdeconnect": "kdeconnect",
        "sshfs": "sshfs",
        "openssh": "openssh",
        "python": "python",
        "python-pillow": "python-pillow",
        "python-dbus": "python-dbus",
        "python-gobject": "python-gobject",
        "python-psutil": "python-psutil",
        "gst-base": "gst-plugins-base",
        "gst-good": "gst-plugins-good",
        "gst-bad": "gst-plugins-bad",
        "gst-pipewire": "gst-plugin-pipewire",
        "pipewire": "pipewire",
        "pipewire-pulse": "pipewire-pulse",
        "pulse-utils": "libpulse",
        "adb": "android-tools",
        "wl-clipboard": "wl-clipboard",
        "xclip": "xclip",
        "sqlite": "sqlite",
        "libnotify": "libnotify",
        "grim": "grim"
    },
    "apt": {
        "kdeconnect": "kdeconnect",
        "sshfs": "sshfs",
        "openssh-server": "openssh-server",
        "openssh-client": "openssh-client",
        "python": "python3",
        "python-pillow": "python3-pil",
        "python-dbus": "python3-dbus",
        "python-gobject": "python3-gi",
        "python-psutil": "python3-psutil",
        "gst-base": "gstreamer1.0-plugins-base",
        "gst-good": "gstreamer1.0-plugins-good",
        "gst-bad": "gstreamer1.0-plugins-bad",
        "gst-pipewire": "gstreamer1.0-pipewire",
        "gst-tools": "gstreamer1.0-tools",
        "gst-x": "gstreamer1.0-x",
        "pipewire": "pipewire",
        "pipewire-pulse": "pipewire-pulse",
        "pulse-utils": "pulseaudio-utils",
        "adb": "adb",
        "wl-clipboard": "wl-clipboard",
        "xclip": "xclip",
        "sqlite": "sqlite3",
        "libnotify": "libnotify-bin",
        "grim": "grim"
    },
    "dnf": {
        "kdeconnect": "kdeconnect",
        "sshfs": "fuse-sshfs",
        "openssh-server": "openssh-server",
        "openssh-clients": "openssh-clients",
        "python": "python3",
        "python-pillow": "python3-pillow",
        "python-dbus": "python3-dbus",
        "python-gobject": "python3-gobject",
        "python-psutil": "python3-psutil",
        "gst-base": "gstreamer1-plugins-base",
        "gst-good": "gstreamer1-plugins-good",
        "gst-bad": "gstreamer1-plugins-bad-free",
        "gst-pipewire": "pipewire-gstreamer",
        "pipewire": "pipewire",
        "pipewire-pulse": "pipewire-pulseaudio",
        "pulse-utils": "pulseaudio-utils",
        "adb": "android-tools",
        "wl-clipboard": "wl-clipboard",
        "xclip": "xclip",
        "sqlite": "sqlite",
        "libnotify": "libnotify",
        "grim": "grim"
    },
    "zypper": {
        "kdeconnect": "kdeconnect-kde",
        "sshfs": "sshfs",
        "openssh": "openssh",
        "python": "python3",
        "python-pillow": "python3-Pillow",
        "python-dbus": "python3-dbus-python",
        "python-gobject": "python3-gobject",
        "python-psutil": "python3-psutil",
        "gst-base": "gstreamer-plugins-base",
        "gst-good": "gstreamer-plugins-good",
        "gst-bad": "gstreamer-plugins-bad",
        "gst-pipewire": "pipewire-plugin-gstreamer",
        "pipewire": "pipewire",
        "pipewire-pulse": "pipewire-pulseaudio",
        "pulse-utils": "pulseaudio-utils",
        "adb": "android-tools",
        "wl-clipboard": "wl-clipboard",
        "xclip": "xclip",
        "sqlite": "sqlite3",
        "libnotify": "libnotify-tools",
        "grim": "grim"
    }
}

class DynamicSpinner:
    def __init__(self, message="Processing..."):
        self.message = message
        self.running = False
        self.thread = None

    def _spin(self):
        for char in itertools.cycle(SPINNER_BRAILLE):
            if not self.running:
                break
            sys.stdout.write(f"\r  {C_CYAN}{char}{C_RESET} {self.message}   ")
            sys.stdout.flush()
            time.sleep(0.08)

    def start(self):
        self.running = True
        self.thread = threading.Thread(target=self._spin, daemon=True)
        self.thread.start()

    def stop(self, success=True, custom_msg=None):
        self.running = False
        if self.thread:
            self.thread.join()
        sys.stdout.write("\r\033[K")
        icon = CHECK_ICON if success else CROSS_ICON
        msg = custom_msg or self.message
        print(f"  {icon} {msg}")
        sys.stdout.flush()

def get_adb_bin():
    if shutil.which('adb'):
        return 'adb'
    sdk_adb = Path.home() / 'Android' / 'Sdk' / 'platform-tools' / 'adb'
    if sdk_adb.exists():
        return str(sdk_adb)
    return 'adb'

def run_cmd(cmd, check=False, shell=True):
    try:
        res = subprocess.run(
            cmd,
            shell=shell,
            text=True,
            capture_output=True,
            check=check
        )
        return res.returncode, res.stdout, res.stderr
    except Exception as e:
        return -1, "", str(e)

def print_banner():
    print(f"\n{C_BOLD}=== KDE Connect Setup ==={C_RESET}")
    print(f"{C_DIM}Installer for Linux desktop daemon, tools & dependencies{C_RESET}\n")

def print_uninstall_banner():
    print(f"\n{C_BOLD}=== KDE Connect Uninstaller ==={C_RESET}")
    print(f"{C_DIM}Removes streamer daemons, audio sinks, firewall rules & apps{C_RESET}\n")

def detect_package_manager():
    managers = ["pacman", "apt", "dnf", "zypper"]
    for mgr in managers:
        if shutil.which(mgr):
            return mgr
    return "unknown"

def detect_display_server():
    wayland_display = os.environ.get("WAYLAND_DISPLAY")
    xdg_session_type = os.environ.get("XDG_SESSION_TYPE", "").lower()
    if wayland_display or xdg_session_type == "wayland":
        return "Wayland"
    return "X11"

def detect_audio_server():
    if shutil.which("pipewire"):
        code, out, _ = run_cmd("pactl info 2>/dev/null || true")
        if "PipeWire" in out or "pipewire" in out:
            return "PipeWire"
    if shutil.which("pulseaudio"):
        return "PulseAudio"
    return "ALSA"

def detect_desktop_env():
    de = os.environ.get("XDG_CURRENT_DESKTOP", "")
    if not de:
        de = os.environ.get("DESKTOP_SESSION", "Unknown")
    return de

def is_pkg_installed(pkg_name, pkg_mgr):
    if pkg_mgr == "pacman":
        code, _, _ = run_cmd(f"pacman -Q {pkg_name} 2>/dev/null")
        return code == 0
    elif pkg_mgr == "apt":
        code, out, _ = run_cmd(f"dpkg -s {pkg_name} 2>/dev/null")
        return code == 0 and "Status: install ok installed" in out
    elif pkg_mgr == "dnf":
        code, _, _ = run_cmd(f"rpm -q {pkg_name} 2>/dev/null")
        return code == 0
    elif pkg_mgr == "zypper":
        code, _, _ = run_cmd(f"rpm -q {pkg_name} 2>/dev/null")
        return code == 0
    return False

def check_python_module(module_name):
    code, _, _ = run_cmd(f"{sys.executable} -c 'import {module_name}' 2>/dev/null")
    return code == 0

def perform_preflight_checks(pkg_mgr):
    print(f"  {INFO_ICON} Environment detected:")
    print(f"    - Desktop Env      : {detect_desktop_env()}")
    print(f"    - Display Server   : {detect_display_server()}")
    print(f"    - Audio Engine     : {detect_audio_server()}")
    print(f"    - Package Manager  : {pkg_mgr.upper() if pkg_mgr != 'unknown' else 'Generic'}")
    print()

    missing = []
    if pkg_mgr in PKG_MAP:
        mapping = PKG_MAP[pkg_mgr]
        for key, sys_pkg in mapping.items():
            if not is_pkg_installed(sys_pkg, pkg_mgr):
                if key == "python-pillow" and check_python_module("PIL"):
                    continue
                if key == "python-psutil" and check_python_module("psutil"):
                    continue
                if key == "python-dbus" and check_python_module("dbus"):
                    continue
                if key == "python-gobject" and check_python_module("gi"):
                    continue
                missing.append(sys_pkg)

    if not missing:
        print(f"  {CHECK_ICON} All system dependencies & GStreamer packages are already installed.")
    else:
        print(f"  {WARN_ICON} Missing {len(missing)} required package(s): {', '.join(missing)}")
    
    print()
    return missing

def authenticate_sudo():
    code, _, _ = run_cmd("sudo -n true 2>/dev/null")
    if code != 0:
        print(f"  {INFO_ICON} Sudo authentication required for administrative actions.")
        sys.stdout.flush()
        try:
            subprocess.run(["sudo", "-v"], check=True)
            print()
        except Exception:
            print(f"  {CROSS_ICON} Sudo authentication failed. Exiting.")
            sys.exit(1)

def install_packages(packages, pkg_mgr):
    if not packages:
        return True, ""
    if pkg_mgr == "pacman":
        cmd = f"sudo pacman -S --noconfirm --needed {' '.join(packages)}"
    elif pkg_mgr == "apt":
        cmd = f"sudo apt-get update -qq && sudo apt-get install -y {' '.join(packages)}"
    elif pkg_mgr == "dnf":
        cmd = f"sudo dnf install -y {' '.join(packages)}"
    elif pkg_mgr == "zypper":
        cmd = f"sudo zypper --non-interactive install {' '.join(packages)}"
    else:
        return False, f"Unsupported package manager: {pkg_mgr}"
    code, out, err = run_cmd(cmd)
    if code != 0:
        err_msg = err.strip() or out.strip()
        lines = [l for l in err_msg.splitlines() if l.strip()]
        last_err = lines[-1] if lines else f"Exit code {code}"
        return False, last_err
    return True, ""

def step_configure_ssh():
    spinner = DynamicSpinner("Configuring and starting OpenSSH service...")
    spinner.start()
    code, _, _ = run_cmd("sudo systemctl enable --now sshd 2>/dev/null || sudo systemctl enable --now ssh 2>/dev/null")
    if code == 0:
        spinner.stop(True, "OpenSSH service active and enabled on boot.")
    else:
        spinner.stop(False, "Could not start OpenSSH service. Please verify SSH configuration manually.")

def step_configure_firewall():
    spinner = DynamicSpinner("Checking and configuring local firewall rules...")
    spinner.start()
    configured = []
    if shutil.which("ufw"):
        code, out, _ = run_cmd("sudo ufw status 2>/dev/null")
        if code == 0 and "Status: active" in out:
            run_cmd("sudo ufw allow 1714:1764/udp 2>/dev/null")
            run_cmd("sudo ufw allow 1714:1764/tcp 2>/dev/null")
            run_cmd("sudo ufw allow 59001/tcp 2>/dev/null")
            run_cmd("sudo ufw allow 59002/tcp 2>/dev/null")
            run_cmd("sudo ufw allow ssh 2>/dev/null")
            configured.append("UFW")
            
    if shutil.which("firewall-cmd"):
        code, out, _ = run_cmd("sudo firewall-cmd --state 2>/dev/null")
        if code == 0 and "running" in out:
            run_cmd("sudo firewall-cmd --permanent --zone=public --add-service=kdeconnect 2>/dev/null || sudo firewall-cmd --permanent --zone=public --add-port=1714-1764/udp --add-port=1714-1764/tcp 2>/dev/null")
            run_cmd("sudo firewall-cmd --permanent --zone=public --add-port=59001/tcp --add-port=59002/tcp 2>/dev/null")
            run_cmd("sudo firewall-cmd --permanent --zone=public --add-service=ssh 2>/dev/null")
            run_cmd("sudo firewall-cmd --reload 2>/dev/null")
            configured.append("firewalld")
            
    if configured:
        spinner.stop(True, f"Firewall ports opened for KDE Connect, SSH & Streamer ({', '.join(configured)}).")
    else:
        spinner.stop(True, "Firewall checked (no blocking active firewall detected).")

def step_configure_clipboard_defaults():
    spinner = DynamicSpinner("Setting clipboard defaults (auto-share = OFF)...")
    spinner.start()
    config_base = Path.home() / ".config" / "kdeconnect"
    if config_base.exists():
        for dev_dir in config_base.iterdir():
            if dev_dir.is_dir():
                clip_conf = dev_dir / "kdeconnect_clipboard_config"
                try:
                    lines = []
                    if clip_conf.exists():
                        lines = clip_conf.read_text().splitlines()
                    new_lines = [l for l in lines if not l.startswith("autoShare=") and not l.startswith("sendPassword=")]
                    if "[General]" not in new_lines:
                        new_lines.insert(0, "[General]")
                    new_lines.append("autoShare=false")
                    clip_conf.write_text("\n".join(new_lines) + "\n")
                except Exception:
                    pass
    spinner.stop(True, "Clipboard auto-share defaulted to OFF.")

def step_apply_update_shield(pkg_mgr):
    if pkg_mgr == "pacman":
        pacman_conf = Path("/etc/pacman.conf")
        if pacman_conf.exists():
            try:
                code_grep, out, _ = run_cmd("grep -E '^IgnorePkg.*kdeconnect' /etc/pacman.conf")
                if code_grep != 0:
                    spinner = DynamicSpinner("Applying Arch/CachyOS package hold...")
                    spinner.start()
                    run_cmd(r"sudo sed -i '/^\[options\]/a IgnorePkg = kdeconnect' /etc/pacman.conf 2>/dev/null || true")
                    spinner.stop(True, "Package hold applied.")
            except Exception:
                pass
    elif pkg_mgr == "apt":
        run_cmd("sudo apt-mark hold kdeconnect 2>/dev/null || true")

def step_remove_update_shield(pkg_mgr):
    if pkg_mgr == "pacman":
        pacman_conf = Path("/etc/pacman.conf")
        if pacman_conf.exists():
            try:
                run_cmd("sudo sed -i '/IgnorePkg = kdeconnect/d' /etc/pacman.conf 2>/dev/null || true")
            except Exception:
                pass
    elif pkg_mgr == "apt":
        run_cmd("sudo apt-mark unhold kdeconnect 2>/dev/null || true")

def step_deploy_streamer(project_root):
    spinner = DynamicSpinner("Deploying KDE Connect Streamer Daemon...")
    spinner.start()
    
    bin_dir = Path.home() / ".local" / "bin"
    bin_dir.mkdir(parents=True, exist_ok=True)
    target_bin = bin_dir / "kdeconnect-streamer"
    
    possible_sources = [
        Path(__file__).resolve().parent / "screen_streamer.py",
        project_root / "tools" / "screen_streamer.py",
        project_root / "installer" / "tools" / "screen_streamer.py",
        project_root.parent / "installer" / "tools" / "screen_streamer.py",
        project_root / "2_installer" / "tools" / "screen_streamer.py",
        Path.cwd() / "installer" / "tools" / "screen_streamer.py"
    ]
    source_script = None
    for p in possible_sources:
        if p.exists():
            source_script = p
            break
    
    if not source_script:
        spinner.stop(False, "Source script screen_streamer.py not found.")
        return False
    
    shutil.copy2(source_script, target_bin)
    target_bin.chmod(0o755)
    
    systemd_user_dir = Path.home() / ".config" / "systemd" / "user"
    systemd_user_dir.mkdir(parents=True, exist_ok=True)
    service_file = systemd_user_dir / "kdeconnect-streamer.service"
    
    service_content = f"""[Unit]
Description=KDE Connect Advanced Screen & Audio Streamer Daemon
After=graphical-session.target pipewire.service wireplumber.service
PartOf=graphical-session.target

[Service]
Type=simple
ExecStart={sys.executable} {target_bin}
Restart=always
RestartSec=3
Environment="PYTHONUNBUFFERED=1"
PassEnvironment=DBUS_SESSION_BUS_ADDRESS PATH DISPLAY WAYLAND_DISPLAY XDG_RUNTIME_DIR XDG_CURRENT_DESKTOP XDG_SESSION_TYPE

[Install]
WantedBy=graphical-session.target default.target
"""
    with open(service_file, "w") as f:
        f.write(service_content)

    run_cmd("systemctl --user daemon-reload")
    run_cmd("systemctl --user enable --now kdeconnect-streamer.service")
    run_cmd("systemctl --user restart kdeconnect-streamer.service 2>/dev/null || true")
    
    run_cmd("systemctl --user enable --now kdeconnect.service 2>/dev/null || true")
    run_cmd("kdeconnectd >/dev/null 2>&1 &")
    
    spinner.stop(True, f"Streamer installed to {target_bin} & enabled in systemd.")
    return True

def step_setup_adb():
    spinner = DynamicSpinner("Configuring ADB reverse port forwarding (59001, 59002)...")
    spinner.start()
    
    code, out, _ = run_cmd(f"{get_adb_bin()} devices")
    devices = []
    if code == 0:
        for line in out.strip().splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                devices.append(parts[0])
    
    if devices:
        for dev in devices:
            run_cmd(f"{get_adb_bin()} -s {dev} reverse tcp:59001 tcp:59001")
            run_cmd(f"{get_adb_bin()} -s {dev} reverse tcp:59002 tcp:59002")
        spinner.stop(True, f"ADB Ports forwarded for {len(devices)} device(s): {', '.join(devices)}.")
    else:
        spinner.stop(True, "ADB ports mapped.")

def step_install_apk(project_root):
    possible_apk_paths = [
        Path(__file__).resolve().parent / "app-debug.apk",
        project_root / "apk" / "KDEConnect.apk",
        project_root.parent / "apk" / "KDEConnect.apk",
        project_root / "apk" / "kdeconnect-android-debug.apk",
        project_root.parent / "apk" / "kdeconnect-android-debug.apk",
        project_root / "android" / "build" / "outputs" / "apk" / "debug" / "kdeconnect-android-debug.apk",
        project_root.parent / "android" / "build" / "outputs" / "apk" / "debug" / "kdeconnect-android-debug.apk",
        project_root / "tools" / "app-debug.apk",
        Path.cwd() / "apk" / "KDEConnect.apk"
    ]
    apk_path = None
    for p in possible_apk_paths:
        if p.exists():
            apk_path = p
            break
            
    if apk_path:
        spinner = DynamicSpinner("Checking for connected phone to deploy APK...")
        spinner.start()
        code, out, _ = run_cmd(f"{get_adb_bin()} devices")
        devices = []
        if code == 0:
            for line in out.strip().splitlines()[1:]:
                parts = line.split()
                if len(parts) >= 2 and parts[1] == "device":
                    devices.append(parts[0])
        if devices:
            dev = devices[0]
            spinner.message = f"Installing updated Android APK to {dev}..."
            run_cmd(f"{get_adb_bin()} -s {dev} install -r '{apk_path}'")
            spinner.stop(True, f"Android client APK installed to {dev}.")
        else:
            spinner.stop(True, f"Client APK ready at {apk_path}.")

def step_uninstall_streamer():
    spinner = DynamicSpinner("Stopping & disabling streamer background service...")
    spinner.start()
    run_cmd("systemctl --user stop kdeconnect-streamer.service 2>/dev/null || true")
    run_cmd("systemctl --user disable kdeconnect-streamer.service 2>/dev/null || true")
    run_cmd("pkill -9 -f kdeconnect-streamer 2>/dev/null || true")
    
    service_file = Path.home() / ".config" / "systemd" / "user" / "kdeconnect-streamer.service"
    if service_file.exists():
        try:
            service_file.unlink()
        except Exception:
            pass
            
    run_cmd("systemctl --user daemon-reload 2>/dev/null || true")
    run_cmd("systemctl --user reset-failed 2>/dev/null || true")
    spinner.stop(True, "Streamer service removed and stopped.")

    bin_file = Path.home() / ".local" / "bin" / "kdeconnect-streamer"
    if bin_file.exists():
        try:
            bin_file.unlink()
        except Exception:
            pass

    for shm_file in Path("/dev/shm").glob("task_manager_analytics.db*"):
        try:
            shm_file.unlink()
        except Exception:
            pass

    data_dir = Path.home() / ".local" / "share" / "kdeconnect-streamer"
    if data_dir.exists():
        try:
            shutil.rmtree(data_dir, ignore_errors=True)
        except Exception:
            pass

    config_dir = Path.home() / ".config" / "kdeconnect-streamer"
    if config_dir.exists():
        try:
            shutil.rmtree(config_dir, ignore_errors=True)
        except Exception:
            pass

    pkg_mgr = detect_package_manager()
    step_remove_update_shield(pkg_mgr)

def step_uninstall_audio():
    spinner = DynamicSpinner("Cleaning virtual audio sinks & PipeWire loopbacks...")
    spinner.start()
    run_cmd("pactl unload-module module-null-sink 2>/dev/null || true")
    run_cmd("pactl unload-module module-remap-source 2>/dev/null || true")
    spinner.stop(True, "Virtual audio sinks cleared.")

def step_uninstall_adb():
    spinner = DynamicSpinner("Removing ADB reverse port forwarding...")
    spinner.start()
    run_cmd(f"{get_adb_bin()} reverse --remove tcp:59001 2>/dev/null || true")
    run_cmd(f"{get_adb_bin()} reverse --remove tcp:59002 2>/dev/null || true")
    spinner.stop(True, "ADB reverse port forwardings removed.")

def step_uninstall_firewall():
    spinner = DynamicSpinner("Cleaning firewall rules...")
    spinner.start()
    cleaned = []
    if shutil.which("ufw"):
        code, out, _ = run_cmd("sudo ufw status 2>/dev/null")
        if code == 0 and "Status: active" in out:
            run_cmd("sudo ufw delete allow 59001/tcp 2>/dev/null || true")
            run_cmd("sudo ufw delete allow 59002/tcp 2>/dev/null || true")
            run_cmd("sudo ufw delete allow 1714:1764/udp 2>/dev/null || true")
            run_cmd("sudo ufw delete allow 1714:1764/tcp 2>/dev/null || true")
            cleaned.append("UFW")
            
    if shutil.which("firewall-cmd"):
        code, out, _ = run_cmd("sudo firewall-cmd --state 2>/dev/null")
        if code == 0 and "running" in out:
            run_cmd("sudo firewall-cmd --permanent --zone=public --remove-port=59001/tcp --remove-port=59002/tcp 2>/dev/null || true")
            run_cmd("sudo firewall-cmd --permanent --zone=public --remove-service=kdeconnect 2>/dev/null || sudo firewall-cmd --permanent --zone=public --remove-port=1714-1764/udp --remove-port=1714-1764/tcp 2>/dev/null || true")
            run_cmd("sudo firewall-cmd --reload 2>/dev/null || true")
            cleaned.append("firewalld")
            
    if cleaned:
        spinner.stop(True, f"Firewall ports closed ({', '.join(cleaned)}).")
    else:
        spinner.stop(True, "Firewall rules cleaned.")

def step_uninstall_pc_package(pkg_mgr):
    is_installed = False
    pkg_name = "kdeconnect"
    if pkg_mgr in PKG_MAP:
        pkg_name = PKG_MAP[pkg_mgr].get("kdeconnect", "kdeconnect")
        is_installed = is_pkg_installed(pkg_name, pkg_mgr)
    
    if not is_installed and shutil.which("kdeconnectd"):
        is_installed = True
        
    if not is_installed:
        return
        
    try:
        ans = input(f"\n  {ARROW_ICON} Uninstall KDE Connect desktop package ({pkg_name}) from this PC? [y/N]: ").strip().lower()
        if ans in ["y", "yes"]:
            authenticate_sudo()
            spinner = DynamicSpinner(f"Uninstalling desktop package {pkg_name}...")
            spinner.start()
            
            run_cmd("systemctl --user stop kdeconnect.service 2>/dev/null || true")
            run_cmd("systemctl --user disable kdeconnect.service 2>/dev/null || true")
            run_cmd("pkill -9 -f kdeconnectd 2>/dev/null || true")
            run_cmd("pkill -9 -f kdeconnect-indicator 2>/dev/null || true")
            run_cmd("pkill -9 -f kdeconnect-app 2>/dev/null || true")
            
            if pkg_mgr == "pacman":
                cmd = f"sudo pacman -R --noconfirm {pkg_name}"
            elif pkg_mgr == "apt":
                cmd = f"sudo apt-get remove -y {pkg_name}"
            elif pkg_mgr == "dnf":
                cmd = f"sudo dnf remove -y {pkg_name}"
            elif pkg_mgr == "zypper":
                cmd = f"sudo zypper --non-interactive remove {pkg_name}"
            else:
                cmd = None
                
            if cmd:
                code, out, err = run_cmd(cmd)
                if code == 0:
                    spinner.stop(True, f"Desktop package {pkg_name} uninstalled.")
                else:
                    spinner.stop(False, f"Failed to uninstall {pkg_name}: {err.strip() or out.strip()}")
            else:
                spinner.stop(False, "Unsupported package manager for package removal.")
                
            ans_cfg = input(f"  {ARROW_ICON} Remove KDE Connect configuration and pairing history (~/.config/kdeconnect)? [y/N]: ").strip().lower()
            if ans_cfg in ["y", "yes"]:
                user_conf = Path.home() / ".config" / "kdeconnect"
                user_data = Path.home() / ".local" / "share" / "kdeconnect"
                if user_conf.exists():
                    shutil.rmtree(user_conf, ignore_errors=True)
                if user_data.exists():
                    shutil.rmtree(user_data, ignore_errors=True)
                print(f"  {CHECK_ICON} KDE Connect user configuration and pairing data cleared.")
    except (KeyboardInterrupt, EOFError):
        pass

def step_uninstall_phone_apk():
    code, out, _ = run_cmd(f"{get_adb_bin()} devices")
    devices = []
    if code == 0:
        for line in out.strip().splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                devices.append(parts[0])
    
    if devices:
        try:
            ans = input(f"\n  {ARROW_ICON} Uninstall KDE Connect Android App from connected device ({', '.join(devices)})? [y/N]: ").strip().lower()
            if ans in ["y", "yes"]:
                for dev in devices:
                    spinner = DynamicSpinner(f"Uninstalling Android APK from {dev}...")
                    spinner.start()
                    run_cmd(f"{get_adb_bin()} -s {dev} uninstall org.kde.kdeconnect_tp.debug 2>/dev/null || {get_adb_bin()} -s {dev} uninstall org.kde.kdeconnect_tp 2>/dev/null")
                    spinner.stop(True, f"Android app removed from {dev}.")
        except (KeyboardInterrupt, EOFError):
            pass

def uninstall_main():
    print_uninstall_banner()
    
    try:
        user_input = input(f"  {ARROW_ICON} Proceed with uninstallation? [Y/n]: ").strip().lower()
    except (KeyboardInterrupt, EOFError):
        print("\n  Uninstallation aborted.")
        sys.exit(0)
        
    if user_input and user_input not in ["y", "yes"]:
        print("\n  Uninstallation cancelled.")
        sys.exit(0)

    pkg_mgr = detect_package_manager()
    authenticate_sudo()
    print()
    step_uninstall_streamer()
    step_uninstall_audio()
    step_uninstall_adb()
    step_uninstall_firewall()
    step_uninstall_pc_package(pkg_mgr)
    step_uninstall_phone_apk()
    print(f"\n  {CHECK_ICON} Uninstallation complete.\n")

def print_help():
    print(f"\n{C_BOLD}Usage:{C_RESET} installer.py [OPTIONS]")
    print("\nOptions:")
    print("  -h, --help       Show this help message and exit")
    print("  -u, --uninstall  Uninstall services, audio sinks, and firewall rules")
    print()

def main():
    if "-h" in sys.argv or "--help" in sys.argv:
        print_help()
        return

    if "--uninstall" in sys.argv or "-u" in sys.argv:
        uninstall_main()
        return

    print_banner()
    project_root = Path(__file__).resolve().parent.parent
    pkg_mgr = detect_package_manager()
    
    missing_pkgs = perform_preflight_checks(pkg_mgr)

    try:
        user_input = input(f"  {ARROW_ICON} Proceed with installation & setup? [Y/n]: ").strip().lower()
    except (KeyboardInterrupt, EOFError):
        print("\n  Installation aborted.")
        sys.exit(0)
        
    if user_input and user_input not in ["y", "yes"]:
        print("\n  Installation cancelled.")
        sys.exit(0)
        
    authenticate_sudo()
    print()
    if missing_pkgs:
        spinner_pkgs = DynamicSpinner(f"Installing {len(missing_pkgs)} system package(s)...")
        spinner_pkgs.start()
        ok, err_msg = install_packages(missing_pkgs, pkg_mgr)
        if ok:
            spinner_pkgs.stop(True, "System dependencies installed.")
        else:
            spinner_pkgs.stop(False, f"Package installation failed: {err_msg}")
    else:
        spinner_pkgs = DynamicSpinner("Verifying system packages...")
        spinner_pkgs.start()
        time.sleep(0.2)
        spinner_pkgs.stop(True, "All system dependencies ready.")
        
    step_configure_ssh()
    step_configure_firewall()
    step_configure_clipboard_defaults()
    step_deploy_streamer(project_root)
    step_setup_adb()
    step_install_apk(project_root)
    
    print(f"\n  {CHECK_ICON} Setup completed successfully.\n")

if __name__ == "__main__":
    main()
