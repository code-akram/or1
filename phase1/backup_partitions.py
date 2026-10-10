"""Back up every RAVOZ partition except userdata to this host, over the ADB tunnel.

Run while the phone is booted into a rooted DSU (TrebleDroid userdebug) with `adb root`:
    uv run --project ~/code/or1/proto python ~/code/or1/phase1/backup_partitions.py [--only a,b]

Each partition is hashed on the phone, streamed here with dd, hashed again, and compared.
Also saves the eMMC boot areas (preloader) and the primary and backup GPT. Nothing is written
to the phone. Results and hashes go to backup/<timestamp>/manifest.json. Rerun with --out <dir>
to resume: verified partitions are skipped, and USB drops are retried.
"""

import argparse
import hashlib
import json
import time
from pathlib import Path

from or1 import adb

BACKUP_ROOT = Path(__file__).resolve().parents[1] / "backup"
SKIP = {"userdata", "mmcblk0"}
SECTOR = 512
RETRIES = 3


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(1 << 22):
            h.update(chunk)
    return h.hexdigest()


def wait_for_root_device(timeout: float = 300) -> None:
    """After a USB drop, wait for the RAVOZ to come back with root adbd before retrying."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(10)
        try:
            adb.check_device()
            if adb.shell("id -u", timeout=10).strip() == "0":
                return
        except adb.AdbError:
            pass
    raise SystemExit("RAVOZ did not come back as root within 5 minutes; rerun with --out to resume.")


def device_size(dev: str) -> int:
    return int(adb.shell(f"blockdev --getsize64 {dev}").strip())


def dump(name: str, dd_args: str, dev: str, out: Path, expected_size: int) -> dict:
    started = time.time()
    remote = adb.shell(f"dd if={dev} {dd_args} 2>/dev/null | sha256sum", timeout=3600).split()[0]
    adb.exec_out_to_file(f"dd if={dev} {dd_args} 2>/dev/null", str(out), timeout=7200)
    local = sha256_file(out)
    size = out.stat().st_size
    ok = local == remote and size == expected_size
    rec = {"name": name, "device": dev, "dd": dd_args, "bytes": size, "expected_bytes": expected_size,
           "sha256": local, "sha256_on_phone": remote, "ok": ok, "seconds": round(time.time() - started, 1)}
    print(f"{'OK ' if ok else 'BAD'} {name:<22} {size / 2**20:9.1f} MiB  {rec['seconds']:6.1f}s", flush=True)
    return rec


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", help="comma-separated partition names")
    parser.add_argument("--out", type=Path, help="existing backup dir to add to")
    args = parser.parse_args()

    adb.check_device()
    if adb.shell("id -u").strip() != "0":
        raise SystemExit("adbd is not root: run `adb -P 5038 -s RAV1022V1000265 root` on the DSU first.")

    out_dir = args.out or BACKUP_ROOT / time.strftime("%Y%m%d-%H%M%S")
    (out_dir / "by-name").mkdir(parents=True, exist_ok=True)
    (out_dir / "emmc").mkdir(exist_ok=True)
    manifest_path = out_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {"partitions": []}
    manifest.update({
        "fingerprint_running": adb.shell("getprop ro.build.fingerprint").strip(),
        "vendor_fingerprint": adb.shell("getprop ro.vendor.build.fingerprint").strip(),
        "slot": adb.shell("getprop ro.boot.slot_suffix").strip(),
    })

    names = sorted(n for n in adb.shell("ls /dev/block/by-name").split() if n not in SKIP)
    if args.only:
        names = [n for n in names if n in set(args.only.split(","))]
    jobs = [(n, "bs=4M", f"/dev/block/by-name/{n}", out_dir / "by-name" / f"{n}.bin") for n in names]

    if not args.only:
        disk = "/dev/block/mmcblk0"
        sectors = device_size(disk) // SECTOR
        manifest["emmc_user_sectors"] = sectors
        jobs += [
            ("gpt_primary", "bs=512 count=34", disk, out_dir / "emmc" / "gpt_primary.bin"),
            ("gpt_backup", f"bs=512 skip={sectors - 33} count=33", disk, out_dir / "emmc" / "gpt_backup.bin"),
            ("mmcblk0boot0", "bs=4M", "/dev/block/mmcblk0boot0", out_dir / "emmc" / "mmcblk0boot0.bin"),
            ("mmcblk0boot1", "bs=4M", "/dev/block/mmcblk0boot1", out_dir / "emmc" / "mmcblk0boot1.bin"),
        ]

    done = {p["name"] for p in manifest["partitions"] if p.get("ok")}
    total = 0
    for name, dd_args, dev, out in jobs:
        if name in done and out.exists():
            print(f"--  {name:<22} already verified", flush=True)
            continue
        for attempt in range(1, RETRIES + 1):
            try:
                if dd_args == "bs=512 count=34":
                    expected = 34 * SECTOR
                elif dd_args.startswith("bs=512 skip="):
                    expected = 33 * SECTOR
                else:
                    expected = device_size(dev)
                rec = dump(name, dd_args, dev, out, expected)
            except adb.AdbError as e:
                rec = {"name": name, "device": dev, "ok": False, "error": str(e)}
                print(f"ERR {name:<22} attempt {attempt}: {e}", flush=True)
            if rec["ok"]:
                break
            if attempt < RETRIES:
                wait_for_root_device()
        manifest["partitions"] = [p for p in manifest["partitions"] if p["name"] != name] + [rec]
        manifest_path.write_text(json.dumps(manifest, indent=1))
        total += rec.get("bytes", 0)

    bad = [p["name"] for p in manifest["partitions"] if not p.get("ok")]
    print(f"\n{len(jobs) - len([b for b in bad if b in {j[0] for j in jobs}])}/{len(jobs)} verified, "
          f"{total / 2**30:.2f} GiB this run, backup in {out_dir}")
    if bad:
        print("NOT verified:", ", ".join(bad))


if __name__ == "__main__":
    main()
