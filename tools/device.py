"""Drive the debug build on a USB device joined to the camera hotspot.

Usage: python tools/device.py <command> [args]
  ui                          list on-screen texts and descriptions
  tap <label>                 tap the element whose text or description is <label>
  connect                     tap Connect and wait for the session handshake
  download <id> <kind> <kib>  queue a record (id/kind accept 0x.. hex)
  cancel <id> <kind>          cancel a queued or running record
  log [n]                     last n lines of files/debug.log (default 40)
  grep <regex>                debug.log lines matching <regex>
  header <name-fragment>      RIFF size and byte size of a saved clip
  screenshot <path>           save a PNG screenshot
"""

import re
import subprocess
import sys
import time

PKG = "com.juanm.easydashcam"
SVC = f"{PKG}/.core.download.DownloadService"
ACTION = f"{PKG}.action"
CLIPS = "/sdcard/Movies/EasyDashcam"


def adb(*args: str) -> str:
    return subprocess.run(
        ["adb", *args], capture_output=True, text=True, check=False
    ).stdout


def shell(*args: str) -> str:
    return adb("shell", *args)


def ui() -> list[tuple[str, str, tuple[int, int, int, int]]]:
    shell("uiautomator", "dump", "/sdcard/ui.xml")
    out = []
    for node in re.findall(r"<node [^>]*>", shell("cat", "/sdcard/ui.xml")):
        text = re.search(r' text="([^"]*)"', node).group(1)
        desc = re.search(r'content-desc="([^"]*)"', node).group(1)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if text or desc:
            out.append((text, desc, tuple(map(int, b.groups()))))
    return out


def tap(label: str) -> bool:
    for text, desc, (x1, y1, x2, y2) in ui():
        if label in (text, desc):
            shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            return True
    return False


def log(n: int = 40) -> str:
    return adb("exec-out", "run-as", PKG, "tail", "-n", str(n), "files/debug.log")


def wait_for(pattern: str, timeout_s: float) -> bool:
    end = time.time() + timeout_s
    while time.time() < end:
        if re.search(pattern, log(10)):
            return True
        time.sleep(0.5)
    return False


def num(s: str) -> int:
    return int(s, 0)


def main() -> None:
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "ui":
        for entry in ui():
            print(entry)
    elif cmd == "tap":
        sys.exit(0 if tap(args[0]) else 1)
    elif cmd == "connect":
        if not tap("Connect"):
            sys.exit("no Connect button on screen")
        sys.exit(0 if wait_for(r"TX cmd=0x0800", 20) else "handshake not seen in 20 s")
    elif cmd == "download":
        rid, kind, kib = num(args[0]), num(args[1]), num(args[2])
        print(
            shell(
                "am",
                "start-foreground-service",
                "-n",
                SVC,
                "-a",
                f"{ACTION}.DOWNLOAD",
                "--ei",
                "id",
                str(rid),
                "--ei",
                "kind",
                str(kind),
                "--el",
                "size",
                str(kib),
            )
        )
    elif cmd == "cancel":
        uid = (num(args[0]) << 8) | num(args[1])
        print(
            shell(
                "am",
                "start-service",
                "-n",
                SVC,
                "-a",
                f"{ACTION}.CANCEL",
                "--ei",
                "id",
                str(uid),
            )
        )
    elif cmd == "log":
        print(log(int(args[0]) if args else 40))
    elif cmd == "grep":
        print("\n".join(l for l in log(4000).splitlines() if re.search(args[0], l)))
    elif cmd == "header":
        name = shell(f"ls -t {CLIPS} | grep -m1 '{args[0]}'").strip()
        if not name:
            sys.exit(f"no saved clip matches {args[0]}")
        raw = shell("xxd", "-p", "-l", "8", f"{CLIPS}/{name}").strip()
        size = shell("stat", "-c", "%s", f"{CLIPS}/{name}").strip()
        riff = (
            int.from_bytes(bytes.fromhex(raw)[4:8], "little") + 8
            if raw.startswith("52494646")
            else None
        )
        print(f"{name} riff_size={riff} file_size={size}")
    elif cmd == "screenshot":
        with open(args[0], "wb") as f:
            f.write(
                subprocess.run(
                    ["adb", "exec-out", "screencap", "-p"],
                    capture_output=True,
                    check=True,
                ).stdout
            )
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
