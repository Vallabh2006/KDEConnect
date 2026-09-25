#!/usr/bin/env python3

import os
import sys
import json
import re
import time
import socket
import io
import queue
import pty
import select
import subprocess
import threading
import glob
import signal
import datetime
import random
import shutil
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from PIL import Image

PORT = 59001
MAX_WIDTH = 1920
MAX_HEIGHT = 1080
JPEG_QUALITY = 80
NUM_WORKERS = 4

class PtyTerminalSession:
    def __init__(self):
        self.master = None
        self.slave = None
        self.proc = None
        self.output_buffer = bytearray()
        self.lock = threading.Lock()
        self.running = False
        self.reader_thread = None

    def start(self, cmd="bash"):
        self.stop()
        try:
            self.master, self.slave = pty.openpty()
            env = os.environ.copy()
            env["TERM"] = "xterm-256color"
            env["LANG"] = "en_US.UTF-8"
            env["LC_ALL"] = "en_US.UTF-8"
            env["PAGER"] = "cat"
            env["COLUMNS"] = "125"
            env["LINES"] = "30"

            self.proc = subprocess.Popen(
                cmd,
                shell=True,
                stdin=self.slave,
                stdout=self.slave,
                stderr=self.slave,
                env=env,
                close_fds=True,
                preexec_fn=os.setsid
            )
            try:
                os.close(self.slave)
            except Exception:
                pass
            self.slave = None
            self.running = True

            self.reader_thread = threading.Thread(target=self._read_loop, daemon=True)
            self.reader_thread.start()
            return True
        except Exception as e:
            print("[!] PTY error: " + str(e))
            return False

    def _read_loop(self):
        while self.running and self.master is not None:
            try:
                r, _, _ = select.select([self.master], [], [], 0.05)
                if r:
                    data = os.read(self.master, 4096)
                    if not data:
                        break
                    with self.lock:
                        self.output_buffer.extend(data)
            except (OSError, ValueError):
                break
        self.running = False

    def write_input(self, text):
        if self.master is not None:
            try:
                os.write(self.master, text.encode("utf-8"))
                return True
            except OSError:
                pass
        return False

    def read_output(self):
        with self.lock:
            out = self.output_buffer.decode("utf-8", errors="replace")
            self.output_buffer.clear()
            return out

    def is_alive(self):
        if not self.proc:
            return False
        return self.proc.poll() is None

    def stop(self):
        self.running = False
        if self.proc:
            try:
                self.proc.terminate()
            except Exception:
                pass
            self.proc = None
        if self.master is not None:
            try:
                os.close(self.master)
            except Exception:
                pass
            self.master = None

global_pty = PtyTerminalSession()
global_pty.start("bash")

def is_valid_clip(text):
    if not text:
        return False
    t = text.strip()
    if not t or t.lower() == "null" or t.startswith("file://") or t.startswith("content://"):
        return False
    return True

def get_laptop_clipboard():
    env = os.environ.copy()
    if "DISPLAY" not in env: env["DISPLAY"] = ":0"
    if "WAYLAND_DISPLAY" not in env: env["WAYLAND_DISPLAY"] = "wayland-0"
    if "XDG_RUNTIME_DIR" not in env: env["XDG_RUNTIME_DIR"] = f"/run/user/{os.getuid()}"
    if "DBUS_SESSION_BUS_ADDRESS" not in env: env["DBUS_SESSION_BUS_ADDRESS"] = f"unix:path=/run/user/{os.getuid()}/bus"

    for cmd in [
        ["wl-paste", "-n", "--type", "text/plain;charset=utf-8"],
        ["wl-paste", "-n"],
        ["xclip", "-selection", "clipboard", "-o"],
        ["qdbus6", "org.kde.klipper", "/klipper", "getClipboardContents"],
        ["qdbus", "org.kde.klipper", "/klipper", "getClipboardContents"]
    ]:
        try:
            res = subprocess.check_output(cmd, env=env, text=True, timeout=1).strip()
            if is_valid_clip(res):
                return res
        except Exception:
            pass
    return ""

def get_laptop_clipboard_history():
    clips = []
    current = get_laptop_clipboard()
    if is_valid_clip(current):
        clips.append(current)
    try:
        out = subprocess.check_output(["qdbus6", "org.kde.klipper", "/klipper", "getClipboardHistoryMenu"], text=True, timeout=1)
        for line in out.splitlines():
            line = line.strip()
            if is_valid_clip(line) and line not in clips:
                clips.append(line)
    except Exception:
        pass
    return clips[:50]

class ClipboardMonitor:
    def __init__(self):
        self.lock = threading.Lock()
        self.cond = threading.Condition(self.lock)
        self.version = 1
        self.current = ""
        self.history = []
        self.last_update = time.time()
        self.running = True
        self.thread = threading.Thread(target=self._watch_loop, daemon=True)
        self.thread.start()

    def update(self, text):
        if not is_valid_clip(text):
            return
        with self.cond:
            safe = text.strip()
            if safe != self.current:
                self.current = safe
                self.version += 1
                self.last_update = time.time()
                if safe in self.history:
                    self.history.remove(safe)
                self.history.insert(0, safe)
                if len(self.history) > 50:
                    self.history = self.history[:50]
                self.cond.notify_all()

    def get(self, wait_version=None, timeout=0.0):
        with self.cond:
            if wait_version is not None and timeout > 0.0:
                if self.version <= wait_version:
                    self.cond.wait(timeout=timeout)
            if not self.current:
                fresh = get_laptop_clipboard()
                if fresh:
                    self.current = fresh
                    if fresh not in self.history:
                        self.history.insert(0, fresh)
            return {
                "version": self.version,
                "current": self.current,
                "clipboard": self.current,
                "history": list(self.history[:50]),
                "timestamp": self.last_update
            }

    def _watch_loop(self):
        try:
            c = get_laptop_clipboard()
            if is_valid_clip(c):
                with self.cond:
                    self.current = c.strip()
                    self.history = get_laptop_clipboard_history()
        except Exception:
            pass
        while self.running:
            try:
                latest = get_laptop_clipboard()
                if is_valid_clip(latest):
                    with self.cond:
                        curr = self.current
                    if latest.strip() != curr:
                        self.update(latest)
            except Exception:
                pass
            time.sleep(0.3)

clipboard_monitor = ClipboardMonitor()

def set_laptop_clipboard(text):
    if not is_valid_clip(text):
        return False
    safe_text = text.strip()
    env = os.environ.copy()
    if "DISPLAY" not in env: env["DISPLAY"] = ":0"
    if "WAYLAND_DISPLAY" not in env: env["WAYLAND_DISPLAY"] = "wayland-0"
    if "XDG_RUNTIME_DIR" not in env: env["XDG_RUNTIME_DIR"] = f"/run/user/{os.getuid()}"
    if "DBUS_SESSION_BUS_ADDRESS" not in env: env["DBUS_SESSION_BUS_ADDRESS"] = f"unix:path=/run/user/{os.getuid()}/bus"

    success = False
    for prog in ["wl-copy", "xclip"]:
        try:
            if prog == "wl-copy" and shutil.which("wl-copy"):
                p = subprocess.Popen(["wl-copy", "--trim-newline"], stdin=subprocess.PIPE, env=env, text=True)
                p.communicate(safe_text, timeout=1)
                if p.returncode == 0:
                    success = True
            elif prog == "xclip" and shutil.which("xclip"):
                p = subprocess.Popen(["xclip", "-selection", "clipboard"], stdin=subprocess.PIPE, env=env, text=True)
                p.communicate(safe_text, timeout=1)
                if p.returncode == 0:
                    success = True
        except Exception:
            pass
    try:
        p = subprocess.Popen(["qdbus6", "org.kde.klipper", "/klipper", "setClipboardContents", safe_text], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        p.wait(timeout=1)
        success = True
    except Exception:
        pass
    clipboard_monitor.update(safe_text)
    return True

def get_laptop_volume():
    env = os.environ.copy()
    if "XDG_RUNTIME_DIR" not in env: env["XDG_RUNTIME_DIR"] = f"/run/user/{os.getuid()}"
    if "DBUS_SESSION_BUS_ADDRESS" not in env: env["DBUS_SESSION_BUS_ADDRESS"] = f"unix:path=/run/user/{os.getuid()}/bus"

    try:
        out = subprocess.check_output(["wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@"], env=env, text=True, timeout=1).strip()
        parts = out.split()
        if len(parts) >= 2 and parts[0] == "Volume:":
            vol = int(round(float(parts[1]) * 100))
            muted = "[MUTED]" in out
            return {"volume": min(100, max(0, vol)), "muted": muted}
    except Exception:
        pass

    try:
        out = subprocess.check_output(["pactl", "get-sink-volume", "@DEFAULT_SINK@"], env=env, text=True, timeout=1)
        import re
        m = re.search(r"(\d+)%", out)
        if m:
            vol = int(m.group(1))
            mute_out = subprocess.check_output(["pactl", "get-sink-mute", "@DEFAULT_SINK@"], env=env, text=True, timeout=1)
            muted = "yes" in mute_out.lower()
            return {"volume": min(100, max(0, vol)), "muted": muted}
    except Exception:
        pass

    try:
        out = subprocess.check_output(["amixer", "sget", "Master"], env=env, text=True, timeout=1)
        import re
        m = re.search(r"\[(\d+)%\]", out)
        if m:
            vol = int(m.group(1))
            muted = "[off]" in out
            return {"volume": min(100, max(0, vol)), "muted": muted}
    except Exception:
        pass

    return {"volume": 50, "muted": False}

def set_laptop_volume(vol_pct=None, mute=None):
    env = os.environ.copy()
    if "XDG_RUNTIME_DIR" not in env: env["XDG_RUNTIME_DIR"] = f"/run/user/{os.getuid()}"
    if "DBUS_SESSION_BUS_ADDRESS" not in env: env["DBUS_SESSION_BUS_ADDRESS"] = f"unix:path=/run/user/{os.getuid()}/bus"

    if vol_pct is not None:
        try:
            val = max(0, min(100, int(vol_pct)))
            frac = val / 100.0
            done = False
            try:
                subprocess.run(["wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", f"{frac:.2f}"], env=env, timeout=1, check=True)
                done = True
            except Exception:
                pass
            if not done:
                try:
                    subprocess.run(["pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{val}%"], env=env, timeout=1, check=True)
                    done = True
                except Exception:
                    pass
            if not done:
                try:
                    subprocess.run(["amixer", "set", "Master", f"{val}%"], env=env, timeout=1)
                except Exception:
                    pass
        except Exception:
            pass

    if mute is not None:
        try:
            m_str = str(mute).lower()
            if m_str == "toggle":
                try:
                    subprocess.run(["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"], env=env, timeout=1)
                except Exception:
                    subprocess.run(["pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle"], env=env, timeout=1)
            else:
                is_m = m_str in ["1", "true", "yes"]
                try:
                    subprocess.run(["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "1" if is_m else "0"], env=env, timeout=1)
                except Exception:
                    subprocess.run(["pactl", "set-sink-mute", "@DEFAULT_SINK@", "1" if is_m else "0"], env=env, timeout=1)
        except Exception:
            pass

    return get_laptop_volume()

def get_installed_cachyos_apps():
    apps = []
    seen = set()
    desktop_paths = glob.glob("/usr/share/applications/*.desktop") + glob.glob(os.path.expanduser("~/.local/share/applications/*.desktop")) + glob.glob("/var/lib/flatpak/exports/share/applications/*.desktop")
    for dp in desktop_paths:
        try:
            name = None
            exec_cmd = ""
            icon = ""
            categories = ""
            pkg_type = "Pacman"
            if "flatpak" in dp:
                pkg_type = "Flatpak"
            with open(dp, errors="ignore") as f:
                for line in f:
                    if line.startswith("Name=") and not name:
                        name = line.strip().split("=", 1)[1]
                    elif line.startswith("Exec=") and not exec_cmd:
                        exec_cmd = line.strip().split("=", 1)[1]
                    elif line.startswith("Icon=") and not icon:
                        icon = line.strip().split("=", 1)[1]
                    elif line.startswith("Categories=") and not categories:
                        categories = line.strip().split("=", 1)[1]
                    elif line.startswith("NoDisplay=true"):
                        name = None
                        break
            if name and name not in seen:
                seen.add(name)
                apps.append({
                    "name": name,
                    "package": os.path.basename(dp).replace(".desktop", ""),
                    "source": pkg_type,
                    "exec": exec_cmd,
                    "icon": icon,
                    "categories": categories,
                    "is_running": False,
                    "cpu": 0.0,
                    "mem_mb": 0,
                    "data_rx_mb": 0,
                    "data_tx_mb": 0
                })
        except Exception:
            pass

    try:
        paru_out = subprocess.check_output(["pacman", "-Qm"], text=True, timeout=1.5)
        for line in paru_out.strip().splitlines():
            parts = line.split()
            if parts:
                pkg_name = parts[0]
                if pkg_name not in seen:
                    seen.add(pkg_name)
                    apps.append({
                        "name": pkg_name,
                        "package": pkg_name,
                        "source": "Paru (AUR)",
                        "exec": pkg_name,
                        "icon": "",
                        "categories": "AUR",
                        "is_running": False,
                        "cpu": 0.0,
                        "mem_mb": 0,
                        "data_rx_mb": 0,
                        "data_tx_mb": 0
                    })
    except Exception:
        pass
    return apps


def get_live_token_stats():
    brain_dir = os.path.expanduser("~/.gemini/antigravity-ide/brain")
    pattern = os.path.join(brain_dir, "*", ".system_generated", "logs", "transcript_full.jsonl")
    transcripts = glob.glob(pattern)
    if not transcripts:
        return {"error": "No active conversation found"}
    transcripts.sort(key=lambda x: os.path.getmtime(x), reverse=True)
    latest_transcript = transcripts[0]
    conv_id = os.path.basename(os.path.dirname(os.path.dirname(os.path.dirname(latest_transcript))))
    
    total_tokens = 0
    total_chars = 0
    total_steps = 0
    user_tokens = 0
    assistant_tokens = 0
    tool_tokens = 0
    thinking_tokens = 0
    
    with open(latest_transcript, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            total_steps += 1
            try:
                data = json.loads(line)
                content = data.get("content", "") or ""
                thinking = data.get("thinking", "") or ""
                tool_calls = data.get("tool_calls", []) or []
                source = data.get("source", "")
                stype = data.get("type", "")

                c_tok = int(len(content) / 3.8) if content else 0
                t_tok = int(len(thinking) / 3.8) if thinking else 0
                tc_tok = int(len(json.dumps(tool_calls)) / 3.8) if tool_calls else 0
                
                step_tok = c_tok + t_tok + tc_tok
                total_tokens += step_tok
                total_chars += len(content) + len(thinking)
                thinking_tokens += t_tok
                
                if source == "USER_EXPLICIT" or stype == "USER_INPUT":
                    user_tokens += c_tok
                elif source == "MODEL" or stype == "PLANNER_RESPONSE":
                    assistant_tokens += c_tok + tc_tok
                else:
                    tool_tokens += c_tok
            except Exception:
                pass
                
    return {
        "session_id": conv_id,
        "steps": total_steps,
        "total_tokens": total_tokens,
        "total_chars": total_chars,
        "user_tokens": user_tokens,
        "assistant_tokens": assistant_tokens,
        "tool_tokens": tool_tokens,
        "thinking_tokens": thinking_tokens,
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
    }

def get_task_manager_stats():

    try:
        load1, load5, load15 = os.getloadavg()
        cpu_count = os.cpu_count() or 1
        cpu_pct = min(100.0, (load1 / cpu_count) * 100.0)
    except Exception:
        load1 = load5 = load15 = cpu_pct = 0.0
        cpu_count = 1

    cpu_model = "x86_64 CPU"
    try:
        with open("/proc/cpuinfo") as f:
            for line in f:
                if "model name" in line:
                    cpu_model = line.split(":", 1)[1].strip()
                    break
    except Exception:
        pass

    cpu_temp = 0.0
    for p in ["/sys/class/thermal/thermal_zone0/temp", "/sys/class/hwmon/hwmon1/temp1_input", "/sys/class/hwmon/hwmon2/temp1_input"]:
        if os.path.exists(p):
            try:
                with open(p) as f:
                    t = float(f.read().strip())
                    if t > 1000: t /= 1000.0
                    cpu_temp = round(t, 1)
                    break
            except Exception:
                pass

    mem_total = mem_avail = mem_free = mem_cached = mem_buffers = swap_total = swap_free = 0
    try:
        with open("/proc/meminfo") as f:
            for l in f:
                if "MemTotal:" in l: mem_total = int(l.split()[1]) // 1024
                elif "MemAvailable:" in l: mem_avail = int(l.split()[1]) // 1024
                elif "MemFree:" in l: mem_free = int(l.split()[1]) // 1024
                elif "Cached:" in l: mem_cached = int(l.split()[1]) // 1024
                elif "Buffers:" in l: mem_buffers = int(l.split()[1]) // 1024
                elif "SwapTotal:" in l: swap_total = int(l.split()[1]) // 1024
                elif "SwapFree:" in l: swap_free = int(l.split()[1]) // 1024
    except Exception:
        pass
    mem_used = max(0, mem_total - mem_avail)
    swap_used = max(0, swap_total - swap_free)

    disks = []
    try:
        df_out = subprocess.check_output(["df", "-kP"], text=True, timeout=1)
        for line in df_out.strip().splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 6 and (parts[5] == "/" or parts[5].startswith("/home") or parts[5].startswith("/mnt") or parts[5].startswith("/data")):
                total_gb = round(int(parts[1]) / (1024*1024), 1)
                used_gb = round(int(parts[2]) / (1024*1024), 1)
                free_gb = round(int(parts[3]) / (1024*1024), 1)
                pct = int(parts[4].replace("%", ""))
                disks.append({
                    "mount": parts[5],
                    "fs": parts[0],
                    "total_gb": total_gb,
                    "used_gb": used_gb,
                    "free_gb": free_gb,
                    "percent": pct
                })
    except Exception:
        pass

    gpu_info = {"name": "", "util": 0.0, "mem_used_mb": 0, "mem_total_mb": 0}
    try:
        nv_out = subprocess.check_output(["nvidia-smi", "--query-gpu=utilization.gpu,memory.used,memory.total,name", "--format=csv,noheader,nounits"], text=True, timeout=1).strip()
        if nv_out:
            parts = [p.strip() for p in nv_out.split(",")]
            if len(parts) >= 4:
                gpu_info = {
                    "util": float(parts[0]),
                    "mem_used_mb": int(parts[1]),
                    "mem_total_mb": int(parts[2]),
                    "name": parts[3]
                }
    except Exception:
        pass

    uptime_str = "--"
    boot_time_str = "--"
    try:
        with open("/proc/uptime") as f:
            uptime_sec = int(float(f.readline().split()[0]))
            days = uptime_sec // 86400
            hours = (uptime_sec % 86400) // 3600
            mins = (uptime_sec % 3600) // 60
            uptime_str = f"{days}d {hours}h {mins}m" if days else f"{hours}h {mins}m"
            boot_epoch = time.time() - uptime_sec
            boot_time_str = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(boot_epoch))
    except Exception:
        pass

    net_rx = net_tx = 0
    interfaces = []
    try:
        with open("/proc/net/dev") as f:
            for line in f:
                if ":" in line and not line.strip().startswith("lo:"):
                    iface_name = line.split(":")[0].strip()
                    parts = line.split(":")[1].split()
                    rx_mb = int(parts[0]) // (1024 * 1024)
                    tx_mb = int(parts[8]) // (1024 * 1024)
                    net_rx += rx_mb
                    net_tx += tx_mb
                    interfaces.append({"name": iface_name, "rx_mb": rx_mb, "tx_mb": tx_mb})
    except Exception:
        pass

    os_name_detected = "Linux"
    try:
        if os.path.exists("/etc/os-release"):
            with open("/etc/os-release") as f:
                for line in f:
                    if line.startswith("PRETTY_NAME="):
                        os_name_detected = line.split("=", 1)[1].strip().strip('"')
                        break
                    elif line.startswith("NAME=") and os_name_detected == "Linux":
                        os_name_detected = line.split("=", 1)[1].strip().strip('"')
    except Exception:
        pass

    uname = os.uname()
    kernel_str = uname.release
    hostname_str = uname.nodename

    procs = []
    running_names = {}
    try:
        ps_out = subprocess.check_output(["ps", "-eo", "pid,ppid,user,%cpu,rss,stat,comm,args", "--sort=-%cpu"], text=True, timeout=1.5)
        for line in ps_out.strip().splitlines()[1:]: # Include all running processes
            parts = line.split(None, 7)
            if len(parts) >= 7:
                pid = int(parts[0])
                try: ppid = int(parts[1])
                except: ppid = 0
                user = parts[2]
                try: cpu = float(parts[3])
                except: cpu = 0.0
                try: mem_mb = int(parts[4]) // 1024
                except: mem_mb = 0
                state = parts[5]
                comm = parts[6]
                args = parts[7] if len(parts) > 7 else comm
                rx_mb = analytics_engine.get_proc_rx(pid, comm)
                tx_mb = analytics_engine.get_proc_tx(pid, comm)
                procs.append({
                    "pid": pid,
                    "ppid": ppid,
                    "user": user,
                    "cpu": cpu,
                    "mem_mb": mem_mb,
                    "state": state,
                    "name": comm,
                    "cmd": args,
                    "data_rx_mb": rx_mb,
                    "data_tx_mb": tx_mb,
                    "data_total_mb": round(rx_mb + tx_mb, 2)
                })
                running_names[comm.lower()] = {"cpu": cpu, "mem": mem_mb}
    except Exception:
        pass

    services = []
    try:
        sc_out = subprocess.check_output(["systemctl", "list-units", "--type=service", "--all", "--no-pager", "--no-legend"], text=True, timeout=1.5)
        sc_user = ""
        try:
            sc_user = subprocess.check_output(["systemctl", "--user", "list-units", "--type=service", "--all", "--no-pager", "--no-legend"], text=True, timeout=1.5)
        except Exception:
            pass
        seen_units = set()
        for line in (sc_out + "\n" + sc_user).strip().splitlines():
            clean_line = line.strip().lstrip("●*×+? \t")
            parts = clean_line.split(None, 4)
            if len(parts) >= 4:
                unit = parts[0]
                if unit in seen_units:
                    continue
                seen_units.add(unit)
                load = parts[1]
                active = parts[2]
                sub = parts[3]
                desc = parts[4] if len(parts) > 4 else unit
                s_rx = analytics_engine.get_service_rx(unit)
                s_tx = analytics_engine.get_service_tx(unit)
                services.append({
                    "unit": unit,
                    "load": load,
                    "active": active,
                    "sub": sub,
                    "desc": desc,
                    "is_system": not ("user@" in unit or "plasma-" in unit),
                    "data_rx_mb": s_rx,
                    "data_tx_mb": s_tx,
                    "data_total_mb": round(s_rx + s_tx, 2)
                })
    except Exception:
        pass

    apps = get_installed_cachyos_apps()
    for app in apps:
        app_name = app["name"].lower()
        app_pkg = app["package"].lower()
        for r_comm, r_info in running_names.items():
            if r_comm in app_name or r_comm in app_pkg or app_name in r_comm:
                app["cpu"] = r_info["cpu"]
                app["mem_mb"] = r_info["mem"]
                app["is_running"] = True
                app_rx = analytics_engine.get_app_rx(app_pkg)
                app_tx = analytics_engine.get_app_tx(app_pkg)
                app["data_rx_mb"] = app_rx
                app["data_tx_mb"] = app_tx
                app["data_total_mb"] = round(app_rx + app_tx, 2)
                app["active_screen_sec"] = analytics_engine.get_app_active_sec(app_pkg)
                app["background_sec"] = analytics_engine.get_app_bg_sec(app_pkg)
                app["battery_percent"] = analytics_engine.get_app_battery_pct(app_pkg)
                break

    return {
        "cpu": round(cpu_pct, 1),
        "cpu_temp": cpu_temp,
        "load1": round(load1, 2),
        "load5": round(load5, 2),
        "load15": round(load15, 2),
        "mem_used": mem_used,
        "mem_total": mem_total,
        "mem_free": mem_free,
        "mem_cached": mem_cached,
        "swap_used": swap_used,
        "swap_total": swap_total,
        "disks": disks,
        "gpu": gpu_info,
        "uptime": uptime_str,
        "boot_time": boot_time_str,
        "cpu_model": cpu_model,
        "cpu_cores": cpu_count,
        "os_name": os_name_detected,
        "kernel": kernel_str,
        "hostname": hostname_str,
        "net_rx": net_rx,
        "net_tx": net_tx,
        "interfaces": interfaces,
        "processes": procs,
        "services": services,
        "apps": apps
    }

def execute_task_manager_action(cmd_str):
    try:
        user_env = os.environ.copy()
        if "DISPLAY" not in user_env: user_env["DISPLAY"] = ":0"
        if "WAYLAND_DISPLAY" not in user_env: user_env["WAYLAND_DISPLAY"] = "wayland-0"
        if "XDG_RUNTIME_DIR" not in user_env: user_env["XDG_RUNTIME_DIR"] = f"/run/user/{os.getuid()}"

        parts = cmd_str.strip().split()
        if not parts:
            return "Empty command"
        action = parts[0].lower()

        if action == "kill":
            pids = [int(p) for p in parts[1:] if p.lstrip('-+').isdigit() and not p.startswith('-')]
            sig = 9
            for p in parts[1:]:
                if p in ["-9", "9"]: sig = 9
                elif p in ["-15", "15", "-TERM"]: sig = 15
                elif p in ["-STOP", "STOP"]: sig = 19
                elif p in ["-CONT", "CONT"]: sig = 18
            for pid in pids:
                try:
                    os.kill(pid, sig)
                except Exception:
                    subprocess.run(["kill", f"-{sig}", str(pid)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return f"Process {pids} signaled with {sig}"

        elif action in ["killall", "kill_app", "terminate", "terminate_app", "pkill"] and len(parts) > 1:
            target = " ".join(parts[1:])
            candidates = set()
            raw_target = target.strip()
            candidates.add(raw_target)
            candidates.add(raw_target.lower())
            for p in parts[1:]:
                candidates.add(p)
                candidates.add(p.lower())
                clean_p = p.replace(".desktop", "")
                candidates.add(clean_p)
                candidates.add(clean_p.lower())
                if "." in clean_p:
                    candidates.add(clean_p.split(".")[-1])
                    candidates.add(clean_p.split(".")[-1].lower())

            try:
                desktop_paths = glob.glob("/usr/share/applications/*.desktop") + glob.glob(os.path.expanduser("~/.local/share/applications/*.desktop")) + glob.glob("/var/lib/flatpak/exports/share/applications/*.desktop")
                for dp in desktop_paths:
                    fname = os.path.basename(dp).replace(".desktop", "")
                    if any(c.lower() == fname.lower() or c.lower() in fname.lower() for c in list(candidates)):
                        with open(dp, errors="ignore") as f:
                            for line in f:
                                if line.startswith("Exec="):
                                    exe_part = line.strip().split("=", 1)[1].split()[0]
                                    exe_bin = os.path.basename(exe_part)
                                    candidates.add(exe_bin)
                                    candidates.add(exe_bin.lower())
                                elif line.startswith("Name="):
                                    app_n = line.strip().split("=", 1)[1]
                                    candidates.add(app_n.lower())
            except Exception:
                pass

            killed_pids = []

            try:
                ps_out = subprocess.check_output(["ps", "-eo", "pid,comm,args"], text=True, timeout=1.0)
                for line in ps_out.strip().splitlines()[1:]:
                    line_parts = line.split(None, 2)
                    if len(line_parts) >= 2:
                        pid = int(line_parts[0])
                        comm = line_parts[1].lower()
                        args = line_parts[2].lower() if len(line_parts) > 2 else comm
                        for c in list(candidates):
                            c_low = c.lower().strip()
                            if not c_low or len(c_low) < 2:
                                continue
                            if comm == c_low or (len(c_low) >= 4 and (c_low in comm or comm in c_low)) or f"/{c_low}" in args or f"bin/{c_low}" in args:
                                try:
                                    os.kill(pid, signal.SIGKILL)
                                    killed_pids.append(pid)
                                except Exception:
                                    pass
            except Exception:
                pass

            for c in list(candidates):
                if len(c) >= 2:
                    try:
                        subprocess.run(["killall", "-9", c], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=0.5)
                        subprocess.run(["pkill", "-9", "-x", c], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=0.5)
                    except Exception:
                        pass

            for c in list(candidates):
                try:
                    subprocess.run(["flatpak", "kill", c], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=0.5)
                except Exception:
                    pass

            return f"Terminated {target} (Killed PIDs: {killed_pids})"

        elif action in ["kill_parent", "killparent"] and len(parts) > 1:
            target_pid = int(parts[1])
            target_ppid = int(parts[2]) if len(parts) > 2 and parts[2].isdigit() else 0
            if target_ppid <= 1:
                try:
                    with open(f"/proc/{target_pid}/stat") as f:
                        stat_parts = f.read().split()
                        target_ppid = int(stat_parts[3])
                except Exception:
                    pass
            if target_ppid > 1:
                try:
                    os.kill(target_ppid, signal.SIGKILL)
                except Exception:
                    subprocess.run(["kill", "-9", str(target_ppid)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return f"Terminated parent PID {target_ppid} (Child PID: {target_pid})"
            else:
                return f"Parent PID {target_ppid} cannot be killed (system/root PID)"

        elif action == "launch" and len(parts) > 1:
            pkg = parts[1]
            try:
                subprocess.Popen(["gtk-launch", pkg], env=user_env, start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return f"Launched {pkg} via gtk-launch"
            except Exception:
                pass
            try:
                cmd = " ".join(parts[1:])
                subprocess.Popen(cmd, shell=True, env=user_env, start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return f"Launched {pkg}"
            except Exception as e:
                return f"Launch error: {e}"

        elif action in ["start", "stop", "restart", "enable", "disable"] and len(parts) > 1:
            svc = parts[1]
            res = subprocess.run(["systemctl", "--user", "--no-block", "--no-ask-password", action, svc], capture_output=True, text=True, timeout=1.0)
            if res.returncode == 0:
                return f"Service {svc} {action} queued (user)"
            res_sys = subprocess.run(["systemctl", "--no-block", "--no-ask-password", action, svc], capture_output=True, text=True, timeout=1.0)
            if res_sys.returncode == 0:
                return f"Service {svc} {action} queued (system)"
            err_msg = res_sys.stderr.strip().split("\n")[0] if res_sys.stderr else (res.stderr.strip().split("\n")[0] if res.stderr else "failed")
            return f"Service {svc} {action}: {err_msg}"

        elif action == "service" and len(parts) > 2:
            subaction = parts[1]
            svc = parts[2]
            res = subprocess.run(["systemctl", "--user", "--no-block", "--no-ask-password", subaction, svc], capture_output=True, text=True, timeout=1.0)
            if res.returncode == 0:
                return f"Service {svc} {subaction} queued (user)"
            res_sys = subprocess.run(["systemctl", "--no-block", "--no-ask-password", subaction, svc], capture_output=True, text=True, timeout=1.0)
            if res_sys.returncode == 0:
                return f"Service {svc} {subaction} queued (system)"
            err_msg = res_sys.stderr.strip().split("\n")[0] if res_sys.stderr else (res.stderr.strip().split("\n")[0] if res.stderr else "failed")
            return f"Service {svc} {subaction}: {err_msg}"

        else:
            subprocess.Popen(cmd_str, shell=True, env=user_env, start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return f"Executed: {cmd_str}"
    except Exception as e:
        return f"Error: {e}"

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def setup_virtual_mic():
    try:
        subprocess.run(["pactl", "unload-module", "module-null-sink"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["pactl", "unload-module", "module-remap-source"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(0.1)
        subprocess.run([
            "pactl", "load-module", "module-null-sink",
            "sink_name=PhoneMicSink",
            "sink_properties=device.description=PhoneMicSink"
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run([
            "pactl", "load-module", "module-remap-source",
            "source_name=Phone_Microphone",
            "master=PhoneMicSink.monitor",
            "source_properties=device.description='Phone_Microphone'"
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print("[+] Virtual Microphone 'Phone_Microphone' ready in PipeWire/PulseAudio.")
    except Exception as e:
        print(f"[!] Virtual Mic setup notice: {e}")

setup_virtual_mic()
def start_tcp_mic_server(port=59002):
    def tcp_mic_worker():
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("0.0.0.0", port))
            sock.listen(5)
            while stream_state.running:
                try:
                    conn, addr = sock.accept()
                    def handle_mic_conn(c):
                        try:
                            proc = subprocess.Popen(
                                ["pacat", "-p", "-d", "PhoneMicSink", "--raw", "--channels=1", "--rate=44100", "--format=s16le", "--latency-msec=30"],
                                stdin=subprocess.PIPE,
                                stderr=subprocess.DEVNULL
                            )
                            while stream_state.running:
                                data = c.recv(2048)
                                if not data: break
                                proc.stdin.write(data)
                                proc.stdin.flush()
                        except Exception:
                            pass
                        finally:
                            try: proc.terminate()
                            except Exception: pass
                            try: c.close()
                            except Exception: pass
                    t = threading.Thread(target=handle_mic_conn, args=(conn,), daemon=True)
                    t.start()
                except Exception:
                    pass
        except Exception as e:
            print(f"[!] TCP Mic Server note: {e}")

    t = threading.Thread(target=tcp_mic_worker, daemon=True, name="TcpMicServer")
    t.start()



import sqlite3

class AnalyticsEngine:
    def __init__(self, db_path="/dev/shm/task_manager_analytics.db"):
        self.db_path = db_path
        self._init_db()
        self.prev_io = {}
        self.proc_stats = {}
        self.app_stats = {}
        self.svc_stats = {}
        self._desktop_apps_cache = {}
        self._load_desktop_apps()
        self._sample_live_system_io()
        self.running = True
        self.thread = threading.Thread(target=self._sampling_loop, daemon=True)
        self.thread.start()

    def _load_desktop_apps(self):
        try:
            for dp in glob.glob("/usr/share/applications/*.desktop") + glob.glob(os.path.expanduser("~/.local/share/applications/*.desktop")) + glob.glob("/var/lib/flatpak/exports/share/applications/*.desktop"):
                pkg = os.path.basename(dp).replace(".desktop", "")
                name = pkg
                exec_cmd = ""
                icon = ""
                with open(dp, errors="ignore") as f:
                    for line in f:
                        if line.startswith("Name=") and name == pkg:
                            name = line.strip().split("=", 1)[1]
                        elif line.startswith("Exec=") and not exec_cmd:
                            exec_cmd = line.strip().split("=", 1)[1]
                        elif line.startswith("Icon=") and not icon:
                            icon = line.strip().split("=", 1)[1]
                self._desktop_apps_cache[pkg.lower()] = {"name": name, "exec": exec_cmd, "icon": icon}
        except Exception:
            pass

    def _init_db(self):
        try:
            conn = sqlite3.connect(self.db_path)
            cur = conn.cursor()
            cur.execute("""
                CREATE TABLE IF NOT EXISTS samples (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp INTEGER,
                    slot_5m TEXT,
                    slot_hour TEXT,
                    slot_day TEXT,
                    entity_id TEXT,
                    name TEXT,
                    category TEXT,
                    data_rx_mb REAL,
                    data_tx_mb REAL,
                    active_sec INTEGER,
                    bg_sec INTEGER,
                    battery_mah REAL
                )
            """)
            cur.execute("CREATE INDEX IF NOT EXISTS idx_time ON samples(timestamp)")
            cur.execute("CREATE INDEX IF NOT EXISTS idx_cat ON samples(category)")
            conn.commit()
            conn.close()
        except Exception as e:
            print("[!] Analytics DB init error:", e)

    def _sample_live_system_io(self):
        try:
            now = int(time.time())
            dt = datetime.datetime.fromtimestamp(now)
            slot_5m = dt.strftime("%Y-%m-%d %H:%M")
            slot_hour = dt.strftime("%Y-%m-%d %H:00")
            slot_day = dt.strftime("%Y-%m-%d")

            socket_pid_net = {}
            try:
                ss_out = subprocess.check_output(["ss", "-t", "-u", "-a", "-p", "-i", "-n"], text=True, timeout=1.5)
                current_users = []
                for line in ss_out.splitlines():
                    if line.startswith("tcp") or line.startswith("udp"):
                        current_users = re.findall(r'"([^"]+)",pid=(\d+)', line)
                    elif current_users and ("bytes_sent:" in line or "bytes_received:" in line or "bytes_acked:" in line):
                        bs_m = re.search(r"bytes_sent:(\d+)", line)
                        br_m = re.search(r"bytes_received:(\d+)", line)
                        bs = int(bs_m.group(1)) if bs_m else 0
                        br = int(br_m.group(1)) if br_m else 0
                        for _, pid_s in current_users:
                            pid_i = int(pid_s)
                            if pid_i not in socket_pid_net:
                                socket_pid_net[pid_i] = {"rx": 0, "tx": 0}
                            socket_pid_net[pid_i]["tx"] += bs
                            socket_pid_net[pid_i]["rx"] += br
            except Exception:
                pass

            apps_map = {}
            services_map = {}
            procs_map = {}

            for p in glob.glob("/proc/[0-9]*"):
                pid_str = os.path.basename(p)
                try:
                    pid_int = int(pid_str)
                    with open(f"{p}/comm") as f:
                        comm = f.read().strip()
                    cgroup = ""
                    if os.path.exists(f"{p}/cgroup"):
                        with open(f"{p}/cgroup") as f:
                            cgroup = f.read().strip()

                    net_rx = 0
                    net_tx = 0
                    if pid_int in socket_pid_net:
                        net_rx = socket_pid_net[pid_int]["rx"]
                        net_tx = socket_pid_net[pid_int]["tx"]
                    else:
                        try:
                            if os.path.exists(f"{p}/io"):
                                with open(f"{p}/io") as f:
                                    for line in f:
                                        if line.startswith("rchar:"): net_rx = int(line.split()[1]) // 10
                                        elif line.startswith("wchar:"): net_tx = int(line.split()[1]) // 10
                        except Exception:
                            pass

                    rx_mb = round(net_rx / (1024.0 * 1024.0), 2)
                    tx_mb = round(net_tx / (1024.0 * 1024.0), 2)
                    tot_mb = round(rx_mb + tx_mb, 2)

                    cg_clean = cgroup.replace("\\x2d", "-").replace("\x2d", "-")

                    is_app = False
                    app_pkg = comm.lower()
                    app_name = None
                    for pkg, dinfo in self._desktop_apps_cache.items():
                        if pkg in comm.lower() or comm.lower() in pkg or dinfo["name"].lower() in comm.lower():
                            is_app = True
                            app_pkg = pkg
                            app_name = dinfo["name"]
                            break
                    if "app-" in cg_clean:
                        is_app = True
                        if not app_name:
                            m = re.search(r"app-([^\.@-]+)", cg_clean)
                            if m:
                                app_name = m.group(1).replace("_", " ").title()
                                app_pkg = m.group(1).lower()

                    p_cur = procs_map.get(comm.lower(), {"name": comm, "rx": 0.0, "tx": 0.0, "tot": 0.0, "active": 0, "bg": 0})
                    p_cur["rx"] = round(p_cur["rx"] + rx_mb, 2)
                    p_cur["tx"] = round(p_cur["tx"] + tx_mb, 2)
                    p_cur["tot"] = round(p_cur["tot"] + tot_mb, 2)
                    p_cur["active"] += 5
                    procs_map[comm.lower()] = p_cur

                    if is_app:
                        aname = app_name or comm.title()
                        a_cur = apps_map.get(app_pkg, {"name": aname, "rx": 0.0, "tx": 0.0, "tot": 0.0, "active": 0, "bg": 0})
                        a_cur["rx"] = round(a_cur["rx"] + rx_mb, 2)
                        a_cur["tx"] = round(a_cur["tx"] + tx_mb, 2)
                        a_cur["tot"] = round(a_cur["tot"] + tot_mb, 2)
                        a_cur["active"] += 5
                        a_cur["bg"] += 5
                        apps_map[app_pkg] = a_cur

                    svcs = [s for s in re.findall(r"([a-zA-Z0-9_\-@\.]+\.service)", cg_clean) if not s.startswith("user@")]
                    if svcs:
                        raw_sname = svcs[-1]
                        sname = re.sub(r"@[a-f0-9]+", "", raw_sname)
                        sname = re.sub(r"@autostart", "", sname)
                        if not sname.startswith("app-"):
                            s_cur = services_map.get(sname, {"name": sname, "rx": 0.0, "tx": 0.0, "tot": 0.0, "active": 0, "bg": 0})
                            s_cur["rx"] = round(s_cur["rx"] + rx_mb, 2)
                            s_cur["tx"] = round(s_cur["tx"] + tx_mb, 2)
                            s_cur["tot"] = round(s_cur["tot"] + tot_mb, 2)
                            s_cur["bg"] += 5
                            services_map[sname] = s_cur
                except Exception:
                    pass

            self.app_stats = apps_map
            self.svc_stats = services_map
            self.proc_stats = procs_map

            conn = sqlite3.connect(self.db_path)
            cur = conn.cursor()

            for pkg, ainfo in apps_map.items():
                if ainfo["tot"] > 0:
                    cur.execute("""
                        INSERT OR REPLACE INTO samples (id, timestamp, slot_5m, slot_hour, slot_day, entity_id, name, category, data_rx_mb, data_tx_mb, active_sec, bg_sec, battery_mah)
                        VALUES (
                            (SELECT id FROM samples WHERE entity_id = ? AND category = ? LIMIT 1),
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                        )
                    """, (pkg, "App", now, slot_5m, slot_hour, slot_day, pkg, ainfo["name"], "App", ainfo["rx"], ainfo["tx"], ainfo["active"], ainfo["bg"], round(ainfo["tot"] * 0.05, 2)))

            for sname, sinfo in services_map.items():
                if sinfo["tot"] > 0:
                    cur.execute("""
                        INSERT OR REPLACE INTO samples (id, timestamp, slot_5m, slot_hour, slot_day, entity_id, name, category, data_rx_mb, data_tx_mb, active_sec, bg_sec, battery_mah)
                        VALUES (
                            (SELECT id FROM samples WHERE entity_id = ? AND category = ? LIMIT 1),
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                        )
                    """, (sname, "Service", now, slot_5m, slot_hour, slot_day, sname, sinfo["name"], "Service", sinfo["rx"], sinfo["tx"], sinfo["active"], sinfo["bg"], round(sinfo["tot"] * 0.05, 2)))

            for pcomm, pinfo in procs_map.items():
                if pinfo["tot"] > 0:
                    cur.execute("""
                        INSERT OR REPLACE INTO samples (id, timestamp, slot_5m, slot_hour, slot_day, entity_id, name, category, data_rx_mb, data_tx_mb, active_sec, bg_sec, battery_mah)
                        VALUES (
                            (SELECT id FROM samples WHERE entity_id = ? AND category = ? LIMIT 1),
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                        )
                    """, (pcomm, "Process", now, slot_5m, slot_hour, slot_day, pcomm, pinfo["name"], "Process", pinfo["rx"], pinfo["tx"], pinfo["active"], pinfo["bg"], round(pinfo["tot"] * 0.05, 2)))

            conn.commit()
            conn.close()
        except Exception as e:
            print("[!] Real IO sampling error:", e)

    def _sampling_loop(self):
        while self.running:
            try:
                self._sample_live_system_io()
                time.sleep(3.0)
            except Exception:
                time.sleep(3.0)

    def get_proc_rx(self, pid, comm):
        return self.proc_stats.get(comm.lower(), {}).get("rx", 0.0)

    def get_proc_tx(self, pid, comm):
        return self.proc_stats.get(comm.lower(), {}).get("tx", 0.0)

    def get_app_rx(self, pkg):
        return self.app_stats.get(pkg.lower(), {}).get("rx", 0.0)

    def get_app_tx(self, pkg):
        return self.app_stats.get(pkg.lower(), {}).get("tx", 0.0)

    def get_app_active_sec(self, pkg):
        return self.app_stats.get(pkg.lower(), {}).get("active", 0)

    def get_app_bg_sec(self, pkg):
        return self.app_stats.get(pkg.lower(), {}).get("bg", 0)

    def get_app_battery_pct(self, pkg):
        return self.app_stats.get(pkg.lower(), {}).get("batt", 0.0)

    def get_service_rx(self, unit):
        return self.svc_stats.get(unit.lower(), {}).get("rx", 0.0)

    def get_service_tx(self, unit):
        return self.svc_stats.get(unit.lower(), {}).get("tx", 0.0)

    def query_analytics(self, metric="data", category="apps", timeframe="1d", start_date="", end_date=""):
        conn = sqlite3.connect(self.db_path)
        cur = conn.cursor()
        now = int(time.time())

        now_dt = datetime.datetime.fromtimestamp(now)
        if timeframe == "1h":
            start_ts = now - 3600
            tf_factor = 1.0 / 24.0
        elif timeframe == "12h":
            start_ts = now - (12 * 3600)
            tf_factor = 0.5
        elif timeframe == "1d":

            start_of_day = now_dt.replace(hour=0, minute=0, second=0, microsecond=0)
            start_ts = int(start_of_day.timestamp())
            elapsed_sec = max(60.0, (now_dt - start_of_day).total_seconds())
            tf_factor = elapsed_sec / 86400.0
        elif timeframe == "1w":
            start_ts = now - (7 * 86400)
            tf_factor = 7.0
        elif timeframe in ["1m", "month", "all", "30d"]:

            start_of_month = now_dt.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
            start_ts = int(start_of_month.timestamp())
            elapsed_days = max(1.0, (now_dt - start_of_month).total_seconds() / 86400.0)
            tf_factor = elapsed_days
        elif timeframe == "custom" and start_date and end_date:
            try:
                st_dt = datetime.datetime.strptime(start_date, "%Y-%m-%d")
                en_dt = datetime.datetime.strptime(end_date, "%Y-%m-%d") + datetime.timedelta(days=1)
                start_ts = int(st_dt.timestamp())
                now = int(en_dt.timestamp())
                num_days = max(1, (en_dt - st_dt).days)
                tf_factor = float(num_days)
            except Exception:
                start_of_day = now_dt.replace(hour=0, minute=0, second=0, microsecond=0)
                start_ts = int(start_of_day.timestamp())
                tf_factor = 1.0
        else:
            start_of_month = now_dt.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
            start_ts = int(start_of_month.timestamp())
            elapsed_days = max(1.0, (now_dt - start_of_month).total_seconds() / 86400.0)
            tf_factor = elapsed_days

        if metric == "active_time":
            val_col = "active_sec"
        elif metric == "bg_time":
            val_col = "bg_sec"
        elif metric == "battery":
            val_col = "battery_mah"
        else:
            val_col = "(data_rx_mb + data_tx_mb)"

        cat_clause = ""
        cat_params = [start_ts, now]
        if category == "apps":
            cat_clause = "AND category = 'App'"
        elif category == "services":
            cat_clause = "AND category = 'Service'"
        elif category == "processes":
            cat_clause = "AND category = 'Process'"

        cur.execute(f"SELECT SUM({val_col}) FROM samples WHERE timestamp >= ? AND timestamp <= ? {cat_clause}", cat_params)
        tot_row = cur.fetchone()
        tot = (tot_row[0] if tot_row and tot_row[0] is not None else 0.0)

        if metric == "data":
            phys_rx = 0
            phys_tx = 0
            try:
                with open("/proc/net/dev") as f:
                    for line in f:
                        if ":" in line:
                            iface = line.split(":")[0].strip()
                            if iface.startswith(("lo", "docker", "br-", "veth")):
                                continue
                            parts = line.split(":")[1].split()
                            phys_rx += int(parts[0])
                            phys_tx += int(parts[8])
                base_daily_net = (phys_rx + phys_tx) / (1024.0 * 1024.0)
                tot = base_daily_net * tf_factor
            except Exception:
                pass
        else:
            tot = tot * tf_factor

        cur.execute(f"""
            SELECT entity_id, name, category, SUM({val_col}) as total
            FROM samples
            WHERE timestamp >= ? AND timestamp <= ? {cat_clause}
            GROUP BY entity_id, name, category
            ORDER BY total DESC
        """, cat_params)
        raw_items = cur.fetchall()
        
        if category == "all":
            seen_names = set()
            deduped = []
            for r in raw_items:
                clean_n = r[1].lower()
                if clean_n not in seen_names:
                    seen_names.add(clean_n)
                    deduped.append(r)
            raw_items = deduped

        known_eids = {r[0].lower() for r in raw_items}
        merged_raw = list(raw_items)

        if category in ["all", "apps"]:
            try:
                for app in get_installed_cachyos_apps():
                    pkg_id = app["package"].lower()
                    if pkg_id not in known_eids:
                        merged_raw.append((app["package"], app["name"], "App", 0.0))
                        known_eids.add(pkg_id)
            except Exception:
                pass

        if category in ["processes"]:
            try:
                ps_out = subprocess.check_output(["ps", "-eo", "comm"], text=True, timeout=1.0)
                seen_comms = set()
                for line in ps_out.strip().splitlines()[1:]:
                    comm = line.strip()
                    if comm and comm not in seen_comms and comm.lower() not in known_eids:
                        seen_comms.add(comm)
                        merged_raw.append((comm, comm, "Process", 0.0))
                        known_eids.add(comm.lower())
            except Exception:
                pass

        if category in ["services"]:
            try:
                system_units = subprocess.check_output(["systemctl", "list-units", "--type=service", "--all", "--no-pager", "--no-legend"], text=True, timeout=1.0)
                user_units = ""
                try:
                    user_units = subprocess.check_output(["systemctl", "--user", "list-units", "--type=service", "--all", "--no-pager", "--no-legend"], text=True, timeout=1.0)
                except Exception:
                    pass
                for line in (system_units + user_units).splitlines():
                    parts = line.split(None, 4)
                    if parts:
                        unit = parts[0].strip()
                        desc = parts[4].strip() if len(parts) > 4 else unit
                        if unit.lower() not in known_eids:
                            merged_raw.append((unit, f"{unit} ({desc[:30]})" if desc != unit else unit, "Service", 0.0))
                            known_eids.add(unit.lower())
            except Exception:
                pass

        items = []
        for eid, name, cat, val in merged_raw:
            val = round(val * tf_factor, 2)
            pct = (val / tot * 100.0) if tot > 0 else 0.0
            if metric in ["active_time", "bg_time"]:
                hrs = int(val // 3600)
                mins = int((val % 3600) // 60)
                disp_v = f"{hrs}h {mins}m" if hrs > 0 else f"{mins}m"
            elif metric == "battery":
                disp_v = f"{round(val, 1)} mAh"
            else:
                if val >= 1024:
                    disp_v = f"{round(val / 1024.0, 2)} GB"
                else:
                    disp_v = f"{round(val, 1)} MB"

            items.append({
                "id": f"{cat.lower()}_{eid}",
                "name": name,
                "category": cat,
                "value": round(val, 2),
                "display_value": disp_v,
                "percent": round(pct, 1),
                "icon": eid
            })

        top_10 = [it for it in items if it["value"] > 0][:10]
        bars = []
        for top_item in top_10:
            b_val = top_item["value"]
            if metric in ["active_time", "bg_time"]:
                b_val = round(b_val / 60.0, 1)
            else:
                b_val = round(b_val, 1)

            short_name = top_item["name"].split()[0][:8]
            bars.append({
                "label": short_name,
                "timestamp": 0,
                "value": b_val,
                "secondary_value": top_item["value"]
            })

        if metric in ["active_time", "bg_time"]:
            tot_hrs = int(tot // 3600)
            tot_mins = int((tot % 3600) // 60)
            tot_disp = f"{tot_hrs}h {tot_mins}m" if tot_hrs > 0 else f"{tot_mins}m"
        elif metric == "battery":
            tot_disp = f"{round(tot, 1)} mAh"
        else:
            if tot >= 1024:
                tot_disp = f"{round(tot / 1024.0, 2)} GB"
            else:
                tot_disp = f"{round(tot, 1)} MB"

        return {
            "metric": metric,
            "category": category,
            "timeframe": timeframe,
            "total_value": round(tot, 2),
            "total_display": tot_disp,
            "bars": bars,
            "items": items
        }


analytics_engine = AnalyticsEngine()

class StreamState:
    def __init__(self):
        self.running = True
        self.active_clients = 0
        self.engine_mode = "High-Speed Screen Engine"
        self.frame_condition = threading.Condition()
        self.ticket = 0
        self.latest_frame = None

    def push_frame(self, frame_bytes):
        with self.frame_condition:
            self.latest_frame = frame_bytes
            self.ticket += 1
            self.frame_condition.notify_all()

    def wait_for_frame(self, last_ticket, timeout=0.1):
        with self.frame_condition:
            if self.ticket == last_ticket:
                self.frame_condition.wait(timeout=timeout)
            return self.ticket, self.latest_frame

stream_state = StreamState()
start_tcp_mic_server(59002)

def get_desktop_env():
    env = os.environ.copy()
    uid = os.getuid()
    runtime_dir = env.get("XDG_RUNTIME_DIR", f"/run/user/{uid}")
    env["XDG_RUNTIME_DIR"] = runtime_dir
    
    if "DBUS_SESSION_BUS_ADDRESS" not in env:
        env["DBUS_SESSION_BUS_ADDRESS"] = f"unix:path={runtime_dir}/bus"
        
    if "WAYLAND_DISPLAY" not in env:
        for w in ["wayland-0", "wayland-1", "wayland-2"]:
            if os.path.exists(os.path.join(runtime_dir, w)):
                env["WAYLAND_DISPLAY"] = w
                break
        if "WAYLAND_DISPLAY" not in env:
            env["WAYLAND_DISPLAY"] = "wayland-0"
            
    if "DISPLAY" not in env:
        env["DISPLAY"] = ":0"
    if "XDG_CURRENT_DESKTOP" not in env:
        env["XDG_CURRENT_DESKTOP"] = "KDE"
    if "XDG_SESSION_TYPE" not in env:
        env["XDG_SESSION_TYPE"] = "wayland" if os.path.exists(os.path.join(runtime_dir, env.get("WAYLAND_DISPLAY", "wayland-0"))) else "x11"
    if "QT_QPA_PLATFORM" not in env:
        env["QT_QPA_PLATFORM"] = "wayland;xcb"
    return env

def capture_desktop_screenshot(output_path):
    env = get_desktop_env()
    try:
        if os.path.exists(output_path):
            os.remove(output_path)
    except Exception:
        pass

    if shutil.which("spectacle"):
        try:
            res = subprocess.run(["spectacle", "-b", "-n", "-o", output_path], env=env, timeout=5, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if res.returncode == 0 and os.path.exists(output_path) and os.path.getsize(output_path) > 0:
                return True
        except Exception:
            pass

    if shutil.which("grim"):
        try:
            res = subprocess.run(["grim", output_path], env=env, timeout=5, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if res.returncode == 0 and os.path.exists(output_path) and os.path.getsize(output_path) > 0:
                return True
        except Exception:
            pass

    if shutil.which("gnome-screenshot"):
        try:
            res = subprocess.run(["gnome-screenshot", "-f", output_path], env=env, timeout=5, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if res.returncode == 0 and os.path.exists(output_path) and os.path.getsize(output_path) > 0:
                return True
        except Exception:
            pass

    for tool in [["import", "-window", "root", output_path], ["scrot", output_path]]:
        if shutil.which(tool[0]):
            try:
                res = subprocess.run(tool, env=env, timeout=5, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                if res.returncode == 0 and os.path.exists(output_path) and os.path.getsize(output_path) > 0:
                    return True
            except Exception:
                pass

    return os.path.exists(output_path) and os.path.getsize(output_path) > 0

class AudioBroadcaster:
    def __init__(self, source_cmd):
        self.source_cmd = source_cmd
        self.clients = []
        self.lock = threading.Lock()
        self.running = False
        self.proc = None
        self.thread = None

    def register(self, q):
        with self.lock:
            self.clients.append(q)
            if len(self.clients) == 1:
                self.running = True
                self.thread = threading.Thread(target=self._capture_loop, daemon=True)
                self.thread.start()

    def unregister(self, q):
        with self.lock:
            if q in self.clients:
                self.clients.remove(q)
            if len(self.clients) == 0:
                self.running = False
                if self.proc:
                    try:
                        self.proc.terminate()
                    except Exception:
                        pass
                    self.proc = None

    def _capture_loop(self):
        env = get_desktop_env()
        while self.running:
            proc = None
            try:
                proc = subprocess.Popen(
                    self.source_cmd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                    bufsize=4096,
                    env=env
                )
                self.proc = proc
                while self.running and proc.poll() is None:
                    data = proc.stdout.read(2048)
                    if not data:
                        break
                    with self.lock:
                        if not self.clients:
                            break
                        for q in list(self.clients):
                            try:
                                q.put_nowait(data)
                            except queue.Full:
                                pass
            except Exception:
                time.sleep(1)
            finally:
                if proc:
                    try:
                        proc.terminate()
                    except Exception:
                        pass
                self.proc = None
            if not self.running or not self.clients:
                break
            time.sleep(0.5)

speaker_broadcaster = AudioBroadcaster([
    "parec", "-d", "@DEFAULT_MONITOR@",
    "--raw", "--channels=2", "--rate=44100", "--format=s16le", "--latency-msec=30"
])

laptop_mic_broadcaster = AudioBroadcaster([
    "parec", "-d", "@DEFAULT_SOURCE@",
    "--raw", "--channels=2", "--rate=44100", "--format=s16le", "--latency-msec=30"
])

try:
    import gi
    gi.require_version("Gst", "1.0")
    from gi.repository import Gst, GLib
    Gst.init(None)
    import dbus, dbus.mainloop.glib
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
except Exception as _init_e:
    print(f"[!] Gst/DBus global init warning: {_init_e}")

class PipeWirePortalEngine:
    def __init__(self):
        self.running = True
        self.pipeline = None
        self.is_active = False
        self.is_starting = False
        self.last_frame_time = 0
        self.loop = None
        self.session_handle = None
        self.bus = None
        self.lock = threading.Lock()
        self.worker_thread = None

        # Clean up any stale session token files on init to prevent FPS degradation across reboots
        token_file = Path.home() / ".config" / "kdeconnect-streamer" / "portal_token.json"
        if token_file.exists():
            try:
                token_file.unlink()
            except Exception:
                pass

    def start(self):
        with self.lock:
            if self.is_active or self.is_starting:
                return
            self.is_starting = True
        t = threading.Thread(target=self._run_portal_session, daemon=True)
        self.worker_thread = t
        t.start()

    def stop(self):
        with self.lock:
            self.is_active = False
            self.is_starting = False
            if self.session_handle and self.bus:
                try:
                    sess_obj = self.bus.get_object("org.freedesktop.portal.Desktop", self.session_handle)
                    sess_iface = dbus.Interface(sess_obj, "org.freedesktop.portal.Session")
                    sess_iface.Close()
                except Exception:
                    pass
                self.session_handle = None
            if self.pipeline:
                try:
                    self.pipeline.set_state(Gst.State.NULL)
                except Exception:
                    pass
                self.pipeline = None
            if self.loop and self.loop.is_running():
                try:
                    self.loop.quit()
                except Exception:
                    pass
            stream_state.latest_frame = None

    def _run_portal_session(self):
        import dbus
        from gi.repository import Gst, GLib

        bus = dbus.SessionBus()
        self.bus = bus
        loop = GLib.MainLoop()
        self.loop = loop

        portal = bus.get_object("org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop")
        screencast = dbus.Interface(portal, "org.freedesktop.portal.ScreenCast")

        session_token = f"pw_sess_{int(time.time())}"
        select_token = f"sel_{int(time.time())}"
        start_token = f"start_{int(time.time())}"
        sender = bus.get_unique_name().replace(".", "_").lstrip(":")

        session_handle = [None]
        signal_matches = []

        def cleanup_signals():
            for m in signal_matches:
                try:
                    m.remove()
                except Exception:
                    pass
            signal_matches.clear()

        def on_new_sample(sink):
            sample = sink.emit("pull-sample")
            if sample:
                buf = sample.get_buffer()
                success, map_info = buf.map(Gst.MapFlags.READ)
                if success:
                    data = bytes(map_info.data)
                    buf.unmap(map_info)
                    self.is_active = True
                    self.last_frame_time = time.time()
                    stream_state.push_frame(data)
            return Gst.FlowReturn.OK

        def on_session_created(response, results):
            if response != 0:
                print(f"[!] Portal Session create rejected: {response}")
                with self.lock:
                    self.is_starting = False
                    self.is_active = False
                loop.quit()
                return
            session_handle[0] = str(results.get("session_handle"))
            self.session_handle = session_handle[0]

            # Do NOT persist restore tokens across reboots; always prompt fresh screen share dialog
            opts = {
                "types": dbus.UInt32(1),
                "multiple": dbus.Boolean(False),
                "handle_token": select_token,
                "cursor_mode": dbus.UInt32(2),
                "persist_mode": dbus.UInt32(0)
            }
            screencast.SelectSources(session_handle[0], opts)

        def on_sources_selected(response, results):
            if response != 0:
                print(f"[!] Portal Source select rejected: {response}")
                with self.lock:
                    self.is_starting = False
                    self.is_active = False
                loop.quit()
                return
            screencast.Start(
                session_handle[0],
                "",
                {
                    "handle_token": start_token,
                    "cursor_mode": dbus.UInt32(2)
                }
            )

        def on_started(response, results):
            if response != 0:
                print(f"[!] Portal Screencast start rejected: {response}")
                with self.lock:
                    self.is_starting = False
                    self.is_active = False
                loop.quit()
                return
            streams = results.get("streams", [])
            if streams:
                node_id = int(streams[0][0])
                fd_obj = screencast.OpenPipeWireRemote(session_handle[0], {})
                fd = fd_obj.take()
                pipe_str = f"pipewiresrc name=src fd={fd} path={node_id} do-timestamp=true keepalive-time=1000 ! queue max-size-buffers=2 leaky=downstream ! videoconvert n-threads=4 ! videoscale n-threads=4 method=bilinear ! video/x-raw,width=1280,height=800 ! queue max-size-buffers=2 leaky=downstream ! jpegenc quality=80 idct-method=float ! appsink name=sink emit-signals=true max-buffers=1 drop=true"
                self.pipeline = Gst.parse_launch(pipe_str)
                gst_bus = self.pipeline.get_bus()
                gst_bus.add_signal_watch()
                def on_gst_msg(b, msg):
                    if msg.type in (Gst.MessageType.ERROR, Gst.MessageType.EOS):
                        print(f"[!] Gst stream stopped: {msg.type}")
                        self.is_active = False
                        if self.pipeline:
                            try:
                                self.pipeline.set_state(Gst.State.NULL)
                            except Exception:
                                pass
                        loop.quit()
                gst_bus.connect("message", on_gst_msg)
                sink = self.pipeline.get_by_name("sink")
                sink.connect("new-sample", on_new_sample)
                self.pipeline.set_state(Gst.State.PLAYING)
                with self.lock:
                    self.is_active = True
                    self.is_starting = False
                print("[+] PipeWire 60 FPS GPU Screencast Active!")

        m1 = bus.add_signal_receiver(
            on_session_created,
            signal_name="Response",
            path=f"/org/freedesktop/portal/desktop/request/{sender}/{session_token}",
            dbus_interface="org.freedesktop.portal.Request"
        )
        m2 = bus.add_signal_receiver(
            on_sources_selected,
            signal_name="Response",
            path=f"/org/freedesktop/portal/desktop/request/{sender}/{select_token}",
            dbus_interface="org.freedesktop.portal.Request"
        )
        m3 = bus.add_signal_receiver(
            on_started,
            signal_name="Response",
            path=f"/org/freedesktop/portal/desktop/request/{sender}/{start_token}",
            dbus_interface="org.freedesktop.portal.Request"
        )
        signal_matches.extend([m1, m2, m3])

        screencast.CreateSession({
            "session_handle_token": session_token,
            "handle_token": session_token
        })

        try:
            loop.run()
        finally:
            cleanup_signals()
            with self.lock:
                self.is_active = False
                self.is_starting = False
                if self.pipeline:
                    try:
                        self.pipeline.set_state(Gst.State.NULL)
                    except Exception:
                        pass
                    self.pipeline = None

class FastWaylandCaptureEngine:
    def __init__(self, portal_engine):
        self.portal_engine = portal_engine
        self.running = True
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    def _loop(self):
        tmp_path = "/dev/shm/stream_tmp.jpg"
        ready_path = "/dev/shm/stream_ready.jpg"
        grim_bin = shutil.which("grim")
        spectacle_bin = shutil.which("spectacle")
        while self.running and stream_state.running:
            if stream_state.active_clients > 0 and not self.portal_engine.is_active:
                try:
                    if grim_bin:
                        subprocess.run(
                            [grim_bin, "-t", "jpeg", "-q", "70", "-s", "1.5", tmp_path],
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL,
                            timeout=1.0
                        )
                    elif spectacle_bin:
                        subprocess.run(
                            [spectacle_bin, "-b", "-n", "-o", tmp_path],
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL,
                            timeout=1.5
                        )
                    if os.path.exists(tmp_path):
                        os.replace(tmp_path, ready_path)
                        with open(ready_path, "rb") as f:
                            frame_data = f.read()
                        if len(frame_data) > 1000:
                            stream_state.push_frame(frame_data)
                except Exception:
                    time.sleep(0.02)
            else:
                time.sleep(0.05)

portal_engine = PipeWirePortalEngine()
fallback_engine = FastWaylandCaptureEngine(portal_engine)

class SystemLifecycleMonitor:
    def __init__(self, portal_engine):
        self.portal_engine = portal_engine
        self.running = True
        self.thread = threading.Thread(target=self._monitor_loop, daemon=True)
        self.thread.start()

    def _setup_dbus_signals(self):
        try:
            import dbus
            sbus = dbus.SessionBus()
            def on_screensaver_active(active):
                if bool(active):
                    print("[*] Screen locked (ScreenSaver ActiveChanged) -> terminating screencast session")
                    self._terminate_stream()

            sbus.add_signal_receiver(
                on_screensaver_active,
                signal_name="ActiveChanged",
                dbus_interface="org.freedesktop.ScreenSaver"
            )
            sbus.add_signal_receiver(
                on_screensaver_active,
                signal_name="ActiveChanged",
                dbus_interface="org.kde.ScreenSaver"
            )
        except Exception as e:
            print(f"[!] DBus session signal setup note: {e}")

        try:
            import dbus
            sysbus = dbus.SystemBus()
            def on_sleep(is_sleeping):
                if bool(is_sleeping):
                    print("[*] System preparing for sleep -> terminating screencast session")
                    self._terminate_stream()

            def on_lock():
                print("[*] System session locked -> terminating screencast session")
                self._terminate_stream()

            def on_nm_state(state):
                if int(state) < 40:
                    print(f"[*] Network disconnected (state={state}) -> terminating screencast session")
                    self._terminate_stream()

            sysbus.add_signal_receiver(
                on_sleep,
                signal_name="PrepareForSleep",
                dbus_interface="org.freedesktop.login1.Manager"
            )
            sysbus.add_signal_receiver(
                on_lock,
                signal_name="Lock",
                dbus_interface="org.freedesktop.login1.Session"
            )
            sysbus.add_signal_receiver(
                on_nm_state,
                signal_name="StateChanged",
                dbus_interface="org.freedesktop.NetworkManager"
            )
        except Exception as e:
            print(f"[!] DBus system signal setup note: {e}")

    def _terminate_stream(self):
        stream_state.active_clients = 0
        self.portal_engine.stop()

    def _check_screensaver_locked(self):
        try:
            import dbus
            sbus = dbus.SessionBus()
            ss = sbus.get_object("org.freedesktop.ScreenSaver", "/org/freedesktop/ScreenSaver")
            ss_iface = dbus.Interface(ss, "org.freedesktop.ScreenSaver")
            return bool(ss_iface.GetActive())
        except Exception:
            return False

    def _check_network_connected(self):
        ip = get_local_ip()
        return ip != "127.0.0.1" and bool(ip)

    def _monitor_loop(self):
        self._setup_dbus_signals()
        while self.running and stream_state.running:
            time.sleep(2)
            if self.portal_engine.is_active or self.portal_engine.is_starting or stream_state.active_clients > 0:
                if self._check_screensaver_locked():
                    print("[*] Screen lock detected by watchdog -> terminating screencast session")
                    self._terminate_stream()
                    continue

                if not self._check_network_connected():
                    print("[*] Network offline detected by watchdog -> terminating screencast session")
                    self._terminate_stream()
                    continue

lifecycle_monitor = SystemLifecycleMonitor(portal_engine)


def get_mpris_status(req_player=""):
    env = os.environ.copy()
    if "XDG_RUNTIME_DIR" not in env: env["XDG_RUNTIME_DIR"] = f"/run/user/{os.getuid()}"
    if "DBUS_SESSION_BUS_ADDRESS" not in env: env["DBUS_SESSION_BUS_ADDRESS"] = f"unix:path=/run/user/{os.getuid()}/bus"

    players = []
    try:
        out = subprocess.check_output(["busctl", "--user", "list"], env=env, text=True, timeout=1)
        for line in out.splitlines():
            parts = line.split()
            if parts and parts[0].startswith("org.mpris.MediaPlayer2."):
                if "kdeconnect" not in parts[0]:
                    players.append(parts[0])
    except Exception:
        pass

    player_map = {}
    for full_name in players:
        short = full_name.replace("org.mpris.MediaPlayer2.", "")
        player_map[short] = full_name

    selected_full = ""
    selected_short = ""

    if req_player:
        for k, v in player_map.items():
            if req_player.lower() in k.lower() or req_player.lower() in v.lower():
                selected_short = k
                selected_full = v
                break

    if not selected_full and player_map:
        for k, v in player_map.items():
            try:
                st = subprocess.check_output(["busctl", "--user", "get-property", v, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "PlaybackStatus"], env=env, text=True, timeout=1).strip()
                if "Playing" in st:
                    selected_short = k
                    selected_full = v
                    break
            except Exception:
                pass

    if not selected_full and player_map:
        selected_short = list(player_map.keys())[0]
        selected_full = player_map[selected_short]

    res = {
        "players": list(player_map.keys()),
        "selected_player": selected_short,
        "title": "",
        "artist": "",
        "album": "",
        "status": "Stopped",
        "position": 0,
        "length": 0,
        "position_sec": 0,
        "length_sec": 0
    }

    if selected_full:
        try:
            st = subprocess.check_output(["busctl", "--user", "get-property", selected_full, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "PlaybackStatus"], env=env, text=True, timeout=1).strip()
            m = re.search(r's\s+"([^"]+)"', st)
            if m:
                res["status"] = m.group(1)
        except Exception:
            pass

        try:
            pos_out = subprocess.check_output(["busctl", "--user", "get-property", selected_full, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "Position"], env=env, text=True, timeout=1).strip()
            m = re.search(r'x\s+(\d+)', pos_out)
            if m:
                res["position"] = int(m.group(1))
        except Exception:
            pass

        try:
            meta_out = subprocess.check_output(["busctl", "--user", "get-property", selected_full, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "Metadata"], env=env, text=True, timeout=1).strip()
            
            m_title = re.search(r'"xesam:title"\s+s\s+"([^"]+)"', meta_out)
            if m_title:
                res["title"] = m_title.group(1).encode('utf-8').decode('unicode_escape', errors='ignore')

            m_art = re.search(r'"xesam:artist"\s+as\s+\d+\s+"([^"]+)"', meta_out)
            if m_art:
                res["artist"] = m_art.group(1).encode('utf-8').decode('unicode_escape', errors='ignore')

            m_alb = re.search(r'"xesam:album"\s+s\s+"([^"]+)"', meta_out)
            if m_alb:
                res["album"] = m_alb.group(1).encode('utf-8').decode('unicode_escape', errors='ignore')

            m_len = re.search(r'"mpris:length"\s+[xt]\s+(\d+)', meta_out)
            if m_len:
                res["length"] = int(m_len.group(1))
        except Exception as e:
            pass

        if not res["artist"]:
            for v in player_map.values():
                if "plasma-browser-integration" in v:
                    try:
                        meta = subprocess.check_output(["busctl", "--user", "get-property", v, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "Metadata"], env=env, text=True, timeout=1).strip()
                        m_art = re.search(r'"xesam:artist"\s+as\s+\d+\s+"([^"]+)"', meta)
                        if m_art:
                            res["artist"] = m_art.group(1).encode('utf-8').decode('unicode_escape', errors='ignore')
                    except Exception:
                        pass

    res["position_sec"] = int(res["position"] / 1000000) if res["position"] > 0 else 0
    res["length_sec"] = int(res["length"] / 1000000) if res["length"] > 0 else 0
    return res

def perform_mpris_action(act, req_player="", pos=0):
    env = os.environ.copy()
    if "XDG_RUNTIME_DIR" not in env: env["XDG_RUNTIME_DIR"] = f"/run/user/{os.getuid()}"
    if "DBUS_SESSION_BUS_ADDRESS" not in env: env["DBUS_SESSION_BUS_ADDRESS"] = f"unix:path=/run/user/{os.getuid()}/bus"

    players = []
    try:
        out = subprocess.check_output(["busctl", "--user", "list"], env=env, text=True, timeout=1)
        for line in out.splitlines():
            parts = line.split()
            if parts and parts[0].startswith("org.mpris.MediaPlayer2."):
                if "kdeconnect" not in parts[0]:
                    players.append(parts[0])
    except Exception:
        pass

    target_service = ""
    if req_player:
        if req_player.startswith("org.mpris.MediaPlayer2."):
            target_service = req_player
        else:
            for p in players:
                if req_player.lower() in p.lower():
                    target_service = p
                    break

    if not target_service and players:
        target_service = players[0]

    if not target_service:
        return False

    cmd = []
    if act == "play_pause":
        cmd = ["busctl", "--user", "call", target_service, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "PlayPause"]
    elif act == "play":
        cmd = ["busctl", "--user", "call", target_service, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "Play"]
    elif act == "pause":
        cmd = ["busctl", "--user", "call", target_service, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "Pause"]
    elif act == "next":
        cmd = ["busctl", "--user", "call", target_service, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "Next"]
    elif act in ["previous", "prev"]:
        cmd = ["busctl", "--user", "call", target_service, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "Previous"]
    elif act == "seek":
        try:
            meta = subprocess.check_output(["busctl", "--user", "get-property", target_service, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "Metadata"], env=env, text=True, timeout=1).strip()
            m_tid = re.search(r'"mpris:trackid"\s+[os]\s+"([^"]+)"', meta)
            track_id = m_tid.group(1) if m_tid else "/org/mpris/MediaPlayer2/CurrentTrack"
            if not track_id.startswith("/"):
                track_id = "/" + track_id.replace(":", "/").replace("-", "_")
            microsecs = int(float(pos) * 1000000)
            cmd = ["busctl", "--user", "call", target_service, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "SetPosition", "ox", track_id, str(microsecs)]
            r_seek = subprocess.run(cmd, env=env, timeout=1, capture_output=True)
            if r_seek.returncode == 0:
                return True
            cmd2 = ["busctl", "--user", "call", target_service, "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", "SetPosition", "sx", track_id, str(microsecs)]
            r_seek2 = subprocess.run(cmd2, env=env, timeout=1, capture_output=True)
            if r_seek2.returncode == 0:
                return True
        except Exception:
            return False

    if cmd:
        try:
            subprocess.run(cmd, env=env, timeout=1, check=True)
            return True
        except Exception:
            if "brave" in target_service or "browser" in target_service:
                try:
                    alt = ["busctl", "--user", "call", "org.mpris.MediaPlayer2.plasma-browser-integration", "/org/mpris/MediaPlayer2", "org.mpris.MediaPlayer2.Player", cmd[5]]
                    subprocess.run(alt, env=env, timeout=1, check=True)
                    return True
                except Exception:
                    pass
    return False

class StreamHandler(BaseHTTPRequestHandler):
    def do_HEAD(self):
        parsed_url = urlparse(self.path)
        path = parsed_url.path
        if path in ['/app.apk', '/kdeconnect.apk']:
            apk_path = 'build/outputs/apk/debug/kdeconnect-android-debug.apk'
            if os.path.exists(apk_path):
                self.send_response(200)
                self.send_header('Content-Type', 'application/vnd.android.package-archive')
                self.send_header('Content-Length', str(os.path.getsize(apk_path)))
                self.end_headers()
            else:
                self.send_response(404)
                self.end_headers()
        elif path in ['/audio.pcm', '/audio.wav', '/audio.mp3', '/audio']:
            self.send_response(200)
            self.send_header('Content-Type', 'audio/l16; rate=44100; channels=2')
            self.end_headers()
        else:
            self.send_response(200)
            self.end_headers()

    def do_POST(self):
        parsed_url = urlparse(self.path)
        path = parsed_url.path
        client_ip = self.client_address[0]

        if path == "/upload_recording":
            try:
                content_len = int(self.headers.get("Content-Length", 0))
                filename = self.headers.get("X-Filename", f"kdeconnect_recording_{int(time.time())}.mp4")
                video_dir = Path.home() / "Videos" / "Kdeconnect"
                video_dir.mkdir(parents=True, exist_ok=True)
                dest = video_dir / os.path.basename(filename)
                with open(dest, "wb") as f:
                    remaining = content_len
                    while remaining > 0:
                        chunk = self.rfile.read(min(remaining, 65536))
                        if not chunk:
                            break
                        f.write(chunk)
                        remaining -= len(chunk)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps({"success": True, "path": str(dest)}).encode("utf-8"))
                return
            except Exception:
                self.send_response(500)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                return

        elif path in ['/audio.pcm', '/audio.wav', '/audio.mp3', '/audio']:
            self.send_response(200)
            self.send_header('Content-Type', 'audio/l16; rate=44100; channels=2')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
            self.end_headers()

            q = queue.Queue(maxsize=100)
            speaker_broadcaster.register(q)
            try:
                while stream_state.running:
                    try:
                        chunk = q.get(timeout=0.5)
                        self.wfile.write(chunk)
                    except queue.Empty:
                        continue
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                speaker_broadcaster.unregister(q)

        elif path in ["/capture_screenshot", "/screenshot.png", "/screenshot"]:
            ss_path = "/tmp/kdeconnect_screenshot.png"
            success = capture_desktop_screenshot(ss_path)
            if success and os.path.exists(ss_path) and os.path.getsize(ss_path) > 0:
                with open(ss_path, "rb") as f:
                    img_data = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(img_data)))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(img_data)
            else:
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps({"error": "Failed to capture screenshot"}).encode("utf-8"))

        elif path == "/media_status":
            req_player = query_params.get("player", [""])[0].strip()
            res = get_mpris_status(req_player)
            vol_data = get_laptop_volume()
            res["volume"] = vol_data.get("volume", 50)
            res["muted"] = vol_data.get("muted", False)
            resp = json.dumps(res).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(resp)

        elif path == "/media_action":
            act = query_params.get("action", [""])[0].strip()
            req_player = query_params.get("player", [""])[0].strip()
            pos = query_params.get("position", ["0"])[0]
            perform_mpris_action(act, req_player, pos)
            res = get_mpris_status(req_player)
            vol_data = get_laptop_volume()
            res["volume"] = vol_data.get("volume", 50)
            res["muted"] = vol_data.get("muted", False)
            resp = json.dumps(res).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(resp)

        elif path == "/clipboard":
            ok = set_laptop_clipboard(body)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps({"success": ok, "current": body, "clipboard": body}).encode("utf-8"))

        elif path in ["/volume", "/set_volume"]:
            level = None
            mute = None
            try:
                if body:
                    data = json.loads(body)
                    level = data.get("level") or data.get("volume")
                    mute = data.get("mute")
            except Exception:
                pass
            res = set_laptop_volume(vol_pct=level, mute=mute)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(json.dumps(res).encode("utf-8"))

        elif path in ["/mic", "/mic_stream"]:
            self.send_response(200)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            play_proc = None
            try:
                play_proc = subprocess.Popen(
                    ["pacat", "-p", "-d", "PhoneMicSink", "--raw", "--channels=1", "--rate=44100", "--format=s16le", "--latency-msec=40"],
                    stdin=subprocess.PIPE,
                    stderr=subprocess.DEVNULL
                )
                while stream_state.running:
                    line = self.rfile.readline()
                    if not line:
                        break
                    line_str = line.strip().split(b";")[0]
                    if not line_str:
                        continue
                    try:
                        chunk_len = int(line_str, 16)
                    except ValueError:
                        play_proc.stdin.write(line)
                        play_proc.stdin.flush()
                        continue
                    if chunk_len == 0:
                        break
                    remaining = chunk_len
                    while remaining > 0 and stream_state.running:
                        to_read = min(remaining, 4096)
                        data = self.rfile.read(to_read)
                        if not data:
                            break
                        play_proc.stdin.write(data)
                        remaining -= len(data)
                    play_proc.stdin.flush()
                    self.rfile.read(2)
            except Exception:
                pass
            finally:
                if play_proc:
                    try:
                        play_proc.terminate()
                    except Exception:
                        pass
        else:
            self.send_response(404)
            self.end_headers()

    def do_GET(self):
        client_ip = self.client_address[0]
        parsed_url = urlparse(self.path)
        path = parsed_url.path
        query_params = parse_qs(parsed_url.query)

        target_fps = 0
        if "fps" in query_params:
            try:
                fps_val = query_params["fps"][0]
                if fps_val.lower() not in ["max", "unlimited", "auto", "120"]:
                    target_fps = int(fps_val)
            except Exception:
                target_fps = 0

        if path == "/list_files":
            req_path = query_params.get("path", ["~"])[0]
            if req_path in ["~", ".", ""]:
                req_path = os.path.expanduser("~")
            else:
                req_path = os.path.abspath(os.path.expanduser(req_path))

            try:
                items = []
                if os.path.exists(req_path) and os.path.isdir(req_path):
                    for entry in os.scandir(req_path):
                        try:
                            stat = entry.stat(follow_symlinks=False)
                            is_d = entry.is_dir(follow_symlinks=True)
                            items.append({
                                "name": entry.name,
                                "fullPath": entry.path,
                                "isDirectory": is_d,
                                "size": stat.st_size if not is_d else 0,
                                "lastModified": int(stat.st_mtime * 1000)
                            })
                        except Exception:
                            pass
                    items.sort(key=lambda x: (not x["isDirectory"], x["name"].lower()))

                resp = json.dumps({"success": True, "path": req_path, "items": items}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(resp)))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(resp)
            except Exception as e:
                resp = json.dumps({"success": False, "error": str(e), "items": []}).encode("utf-8")
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(resp)))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(resp)

        elif path == "/download_file":
            req_path = query_params.get("path", [""])[0]
            req_path = os.path.abspath(os.path.expanduser(req_path))

            if not os.path.exists(req_path):
                self.send_response(404)
                self.end_headers()
                return

            if os.path.isdir(req_path):

                folder_name = os.path.basename(req_path.rstrip("/")) or "folder"
                self.send_response(200)
                self.send_header("Content-Type", "application/zip")
                self.send_header("Content-Disposition", f'attachment; filename="{folder_name}.zip"')
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()

                import zipfile
                zip_buffer = io.BytesIO()
                with zipfile.ZipFile(zip_buffer, "w", zipfile.ZIP_DEFLATED) as zf:
                    for root, dirs, files in os.walk(req_path):
                        for file in files:
                            fp = os.path.join(root, file)
                            try:
                                arc = os.path.relpath(fp, req_path)
                                zf.write(fp, arc)
                            except Exception:
                                pass
                self.wfile.write(zip_buffer.getvalue())
            else:
                file_size = os.path.getsize(req_path)
                fname = os.path.basename(req_path)
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(file_size))
                self.send_header("Content-Disposition", f'attachment; filename="{fname}"')
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()

                with open(req_path, "rb") as f:
                    while True:
                        chunk = f.read(65536)
                        if not chunk:
                            break
                        self.wfile.write(chunk)

        elif path in ["/", "/stream.mjpeg", "/stream"]:
            portal_engine.start()
            try:
                self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self.request.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 65536)
            except Exception:
                pass
            self.send_response(200)
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=--jpgboundary")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
            self.end_headers()

            stream_state.active_clients += 1
            frame_count = 0
            start_time = time.time()
            last_sent_time = 0
            last_ticket = 0
            min_interval = 1.0 / float(target_fps) if target_fps > 0 else 0.0

            try:
                while stream_state.running:
                    t_frame_start = time.time()
                    ticket, frame = stream_state.wait_for_frame(last_ticket, timeout=0.2)
                    if frame and (ticket > last_ticket or (time.time() - last_sent_time >= 0.5)):
                        boundary = b"--jpgboundary\r\nContent-Type: image/jpeg\r\nContent-Length: " + str(len(frame)).encode('utf-8') + b"\r\n\r\n"
                        self.wfile.write(boundary)
                        self.wfile.write(frame)
                        self.wfile.write(b"\r\n")
                        self.wfile.flush()

                        if ticket > last_ticket:
                            last_ticket = ticket
                        last_sent_time = time.time()
                        frame_count += 1

                    t_spent = time.time() - t_frame_start
                    if min_interval > t_spent:
                        time.sleep(min_interval - t_spent)
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception as e:
                print(f"[!] Stream error: {e}")
            finally:
                stream_state.active_clients = max(0, stream_state.active_clients - 1)

        elif path in ["/capture_screenshot", "/screenshot.png", "/screenshot"]:
            ss_path = "/tmp/kdeconnect_screenshot.png"
            success = capture_desktop_screenshot(ss_path)
            if success and os.path.exists(ss_path) and os.path.getsize(ss_path) > 0:
                with open(ss_path, "rb") as f:
                    img_data = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(img_data)))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(img_data)
            else:
                self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps({"error": "Failed to capture screenshot"}).encode("utf-8"))

        elif path == "/media_status":
            req_player = query_params.get("player", [""])[0].strip()
            res = get_mpris_status(req_player)
            vol_data = get_laptop_volume()
            res["volume"] = vol_data.get("volume", 50)
            res["muted"] = vol_data.get("muted", False)
            resp = json.dumps(res).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(resp)

        elif path == "/media_action":
            act = query_params.get("action", [""])[0].strip()
            req_player = query_params.get("player", [""])[0].strip()
            pos = query_params.get("position", ["0"])[0]
            perform_mpris_action(act, req_player, pos)
            res = get_mpris_status(req_player)
            vol_data = get_laptop_volume()
            res["volume"] = vol_data.get("volume", 50)
            res["muted"] = vol_data.get("muted", False)
            resp = json.dumps(res).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(resp)

        elif path == "/clipboard":
            wait_ver = None
            if "version" in query_params:
                try:
                    wait_ver = int(query_params["version"][0])
                except Exception:
                    pass
            timeout_sec = 0.0
            if "timeout" in query_params:
                try:
                    timeout_sec = min(float(query_params["timeout"][0]), 10.0)
                except Exception:
                    pass
            data = clipboard_monitor.get(wait_version=wait_ver, timeout=timeout_sec)
            resp = json.dumps(data).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(resp)

        elif path in ["/volume", "/get_volume"]:
            data = get_laptop_volume()
            resp = json.dumps(data).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(resp)

        elif path == "/set_volume":
            level = None
            if "level" in query_params:
                try:
                    level = int(query_params["level"][0])
                except Exception:
                    pass
            elif "volume" in query_params:
                try:
                    level = int(query_params["volume"][0])
                except Exception:
                    pass
            mute = None
            if "mute" in query_params:
                mute = query_params["mute"][0]
            data = set_laptop_volume(vol_pct=level, mute=mute)
            resp = json.dumps(data).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(resp)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(resp)

        elif path == "/terminal_poll":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            out = global_pty.read_output()
            self.wfile.write(out.encode("utf-8"))

        
        elif path == "/token_usage":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            stats = get_live_token_stats()
            self.wfile.write(json.dumps(stats).encode("utf-8"))

        elif path in ["/tokens", "/tokens.html"]:
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            html = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Antigravity Live Token Tracker</title>
<style>
body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; padding: 24px; margin: 0; }
.card { max-width: 600px; margin: 0 auto; background: #1e293b; border-radius: 16px; padding: 24px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); }
h1 { font-size: 20px; color: #38bdf8; margin-top: 0; display: flex; align-items: center; justify-content: space-between; }
.badge { font-size: 12px; background: #0284c7; color: white; padding: 4px 10px; border-radius: 9999px; }
.big-stat { font-size: 38px; font-weight: bold; color: #4ade80; margin: 16px 0 4px 0; }
.sub-stat { color: #94a3b8; font-size: 14px; margin-bottom: 20px; }
.stat-row { display: flex; justify-content: space-between; padding: 10px 0; border-bottom: 1px solid #334155; font-size: 14px; }
.stat-row:last-child { border-bottom: none; }
.stat-val { font-weight: 600; color: #f1f5f9; }
.footer { font-size: 12px; color: #64748b; margin-top: 20px; text-align: center; }
</style>
</head>
<body>
<div class="card">
  <h1>⚡ Antigravity Token Counter <span class="badge">LIVE</span></h1>
  <div class="big-stat" id="total-tokens">Loading...</div>
  <div class="sub-stat" id="total-details">Connecting...</div>
  <div class="stat-row"><span>User Prompts:</span><span class="stat-val" id="user-tok">-</span></div>
  <div class="stat-row"><span>Assistant & Tool Calls:</span><span class="stat-val" id="asst-tok">-</span></div>
  <div class="stat-row"><span>Execution Outputs:</span><span class="stat-val" id="tool-tok">-</span></div>
  <div class="stat-row"><span>Model Thinking:</span><span class="stat-val" id="think-tok">-</span></div>
  <div class="stat-row"><span>Trajectory Steps:</span><span class="stat-val" id="steps-val">-</span></div>
  <div class="footer" id="last-update">Auto-refreshing every 2s</div>
</div>
<script>
async function update() {
  try {
    const res = await fetch("/token_usage");
    const data = await res.json();
    if (data.total_tokens !== undefined) {
      document.getElementById("total-tokens").innerText = data.total_tokens.toLocaleString() + " tokens";
      document.getElementById("total-details").innerText = (data.total_tokens / 1000000).toFixed(2) + "M tokens • " + (data.total_chars / (1024*1024)).toFixed(2) + " MB text";
      document.getElementById("user-tok").innerText = data.user_tokens.toLocaleString();
      document.getElementById("asst-tok").innerText = data.assistant_tokens.toLocaleString();
      document.getElementById("tool-tok").innerText = data.tool_tokens.toLocaleString();
      document.getElementById("think-tok").innerText = data.thinking_tokens.toLocaleString();
      document.getElementById("steps-val").innerText = data.steps.toLocaleString();
      document.getElementById("last-update").innerText = "Last updated: " + data.timestamp + " (Auto-refreshing)";
    }
  } catch(e) {}
}
setInterval(update, 2000);
update();
</script>
</body>
</html>"""
            self.wfile.write(html.encode("utf-8"))

        elif path == "/task_manager_analytics":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            metric = query_params.get("metric", ["data"])[0]
            category = query_params.get("category", ["apps"])[0]
            timeframe = query_params.get("timeframe", ["1d"])[0]
            start_date = query_params.get("start_date", [""])[0]
            end_date = query_params.get("end_date", [""])[0]
            analytics_data = analytics_engine.query_analytics(metric, category, timeframe, start_date, end_date)
            self.wfile.write(json.dumps(analytics_data).encode("utf-8"))

        elif path == "/task_manager_stats":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            stats_data = get_task_manager_stats()
            self.wfile.write(json.dumps(stats_data).encode("utf-8"))

        elif path in ["/task_manager_action", "/action"]:
            cmd = query_params.get("cmd", [""])[0]
            if not cmd and self.headers.get('Content-Length'):
                try:
                    length = int(self.headers.get('Content-Length'))
                    cmd = self.rfile.read(length).decode('utf-8')
                except Exception:
                    pass
            res = execute_task_manager_action(cmd)
            res_b = res.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(res_b)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            try:
                self.wfile.write(res_b)
            except Exception:
                pass

        elif path in ["/app.apk", "/kdeconnect.apk"]:
            apk_path = "build/outputs/apk/debug/kdeconnect-android-debug.apk"
            if os.path.exists(apk_path):
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.android.package-archive")
                self.send_header("Content-Length", str(os.path.getsize(apk_path)))
                self.end_headers()
                with open(apk_path, "rb") as f:
                    self.wfile.write(f.read())
            else:
                self.send_response(404)
                self.end_headers()

        elif path == "/frame.jpg":
            frame = stream_state.latest_frame
            if frame:
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(frame)))
                self.end_headers()
                self.wfile.write(frame)
            else:
                self.send_response(503)
                self.end_headers()

        elif path == "/stop":
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"OK")
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        return

class ReusableThreadingHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True

def main():
    local_ip = get_local_ip()
    print("==================================================================")
    print("  KDE CONNECT REAL PTY & ULTRA-FAST SCREEN/AUDIO SERVER (CACHYOS)")
    print("==================================================================")
    print(f"  * PTY Terminal : http://{local_ip}:{PORT}/terminal_poll | /terminal_input")
    print(f"  * Clipboard    : http://{local_ip}:{PORT}/clipboard (Wayland wl-clipboard)")
    print(f"  * Task Manager : http://{local_ip}:{PORT}/task_manager_stats")
    print(f"  * Video Stream : http://{local_ip}:{PORT}/stream.mjpeg")
    print(f"  * Speaker Audio: http://{local_ip}:{PORT}/audio.pcm")
    print(f"  * Mic Stream   : http://{local_ip}:{PORT}/mic_stream")
    print("==================================================================")
    print("Ready! Waiting for phone to connect...\n")

    try:
        server = ReusableThreadingHTTPServer(("0.0.0.0", PORT), StreamHandler)
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n\nStopping server...")
        stream_state.running = False
        speaker_broadcaster.running = False
        server.server_close()
    except Exception as e:
        print(f"\n[ERROR] Failed to start server: {e}")

if __name__ == "__main__":
    main()