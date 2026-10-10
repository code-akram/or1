"""ADB access to the RAVOZ test phone, and only to it.

The Mac's ADB server is forwarded to 127.0.0.1:5038 and also has the user's
OnePlus (d41317c3) attached. Every command here is pinned to the RAVOZ serial,
and nothing runs unless the tunnel is already listening: invoking `adb -P 5038`
without it would start a local server on that port and block the tunnel.
"""

import os
import socket
import subprocess

ADB = os.environ.get("OR1_ADB", "/home/akram/.local/share/android/platform-tools/adb")
PORT = 5038
SERIAL = "RAV1022V1000265"


class AdbError(RuntimeError):
    pass


def _tunnel_up() -> bool:
    try:
        with socket.create_connection(("127.0.0.1", PORT), timeout=2):
            return True
    except OSError:
        return False


def _run(args: list[str], timeout: float, binary: bool = False) -> bytes | str:
    if not _tunnel_up():
        raise AdbError(f"ADB tunnel on port {PORT} is down; not running adb (it would start a local server).")
    try:
        proc = subprocess.run(
            [ADB, "-P", str(PORT), "-s", SERIAL, *args],
            capture_output=True,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        raise AdbError(f"adb {' '.join(args)[:80]} timed out after {timeout}s")
    if proc.returncode != 0 and not proc.stdout:
        raise AdbError(proc.stderr.decode(errors="replace").strip() or f"adb exited {proc.returncode}")
    return proc.stdout if binary else proc.stdout.decode(errors="replace")


def shell(cmd: str, timeout: float = 30) -> str:
    """Run a command in the device shell (uid shell on a user build)."""
    return _run(["shell", cmd], timeout)


def exec_out(cmd: str, timeout: float = 30) -> bytes:
    """Run a command and return raw stdout bytes (for screencap, file reads)."""
    return _run(["exec-out", cmd], timeout, binary=True)


def exec_out_to_file(cmd: str, path: str, timeout: float) -> None:
    """Stream a command's raw stdout into a local file (for multi-GB partition dumps)."""
    if not _tunnel_up():
        raise AdbError(f"ADB tunnel on port {PORT} is down; not running adb (it would start a local server).")
    with open(path, "wb") as f:
        try:
            proc = subprocess.run([ADB, "-P", str(PORT), "-s", SERIAL, "exec-out", cmd],
                                  stdout=f, stderr=subprocess.PIPE, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise AdbError(f"exec-out {cmd[:80]} timed out after {timeout}s")
    if proc.returncode != 0:
        raise AdbError(proc.stderr.decode(errors="replace").strip() or f"adb exited {proc.returncode}")


def check_device() -> None:
    """Fail unless the RAVOZ is listed and authorized on the tunnelled server."""
    if not _tunnel_up():
        raise AdbError(f"ADB tunnel on port {PORT} is down.")
    out = subprocess.run([ADB, "-P", str(PORT), "devices"], capture_output=True, timeout=15).stdout.decode()
    for line in out.splitlines():
        parts = line.split()
        if parts[:1] == [SERIAL]:
            if parts[1] != "device":
                raise AdbError(f"{SERIAL} is '{parts[1]}', not 'device' (authorize USB debugging?)")
            return
    raise AdbError(f"{SERIAL} is not connected to the Mac's ADB server.")
