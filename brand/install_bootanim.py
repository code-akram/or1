#!/usr/bin/env python3
"""Puts out/bootanimation.zip and out/shutdownanimation.zip into the phone's system partition, at /product/media.

The running system can't be written: its ext4 has shared_blocks (never mountable read-write) and its dm device is
read-only, and `adb remount`'s overlay doesn't mount at boot on this GSI (mounted late, it hides phh's su). So:
  1. copy the flashed TrebleDroid image and add both zips with debugfs (root:root 0644, system_file label);
  2. find what the phone's system_a holds now: the stock image, plus whatever an earlier run patched (it hashes
     system_a per MiB on the phone and pulls the MiBs that differ from the image, about two minutes);
  3. diff that against the new image: a handful of 4K blocks (superblock, descriptors, bitmaps, inodes, dir, data);
  4. map each block through system_a's live dm table onto super (mmcblk0p40);
  5. on the phone, patch_blocks.sh checks every target still holds the bytes read in step 2, then writes and re-reads.
Afterwards system_a is bit-identical to the new image (read through super: until a reboot, reading system_a itself
returns blocks 0 and 1 as the mounted ext4 holds them, the old ones). A reboot picks the files up. Needs adb root and
debugfs/e2fsck (e2fsprogs) on this host.

Usage: python3 brand/install_bootanim.py downloads/td-ci-20230905/system-td-arm64-ab-vanilla.img [--write]
Without --write it stops after the on-phone check. Exits early when the phone already has both zips as built.
"""
import hashlib
import re
import shutil
import subprocess
import sys
from pathlib import Path

ADB = [str(Path.home() / ".local/share/android/platform-tools/adb"), "-P", "5038", "-s", "RAV1022V1000265"]
HERE = Path(__file__).parent
OUT = HERE / "out"
WORK = OUT / "sys"
PHONE_DIR = "/data/local/tmp/or1-patch"
BLOCK = 4096
CHUNK = 1 << 20
SYSTEM = "/dev/block/mapper/system_a"
LABEL = b"u:object_r:system_file:s0\0"


def adb(*args: str) -> str:
    return subprocess.run(ADB + list(args), check=True, capture_output=True, text=True).stdout


def build_image(stock: Path) -> Path:
    WORK.mkdir(parents=True, exist_ok=True)
    image = WORK / "system-or1.img"
    shutil.copyfile(stock, image)
    (WORK / "label").write_bytes(LABEL)
    for name in ("bootanimation.zip", "shutdownanimation.zip"):
        script = "\n".join([
            "cd /system/product/media",
            f"write {OUT / name} {name}",
            f"set_inode_field {name} uid 0",
            f"set_inode_field {name} gid 0",
            f"set_inode_field {name} mode 0100644",
            f"ea_set -f {WORK / 'label'} {name} security.selinux",
        ])
        subprocess.run(["debugfs", "-w", "-f", "-", str(image)], input=script, check=True, capture_output=True, text=True)
    subprocess.run(["e2fsck", "-fn", str(image)], check=True, capture_output=True)
    return image


def dm_extents() -> list[tuple[int, int, int]]:
    """system_a's linear targets as (start sector, end sector, sector on super)."""
    table = adb("shell", "dmctl table system_a")
    extents = [tuple(map(int, m)) for m in re.findall(r"(\d+)-(\d+): linear, \S+ (\d+)", table)]
    assert extents, table
    return extents


def phone_chunks(stock: Path) -> dict[int, bytes]:
    """The MiBs of the phone's system_a that differ from the stock image, read after a cache drop."""
    count = -(-stock.stat().st_size // CHUNK)
    script = (f"echo 3 > /proc/sys/vm/drop_caches; i=0; while [ $i -lt {count} ]; do "
              f"dd if={SYSTEM} bs={CHUNK} skip=$i count=1 2>/dev/null | sha256sum; i=$((i+1)); done")
    on_phone = [line.split()[0] for line in adb("shell", script).splitlines()]
    assert len(on_phone) == count, f"{len(on_phone)} of {count} hashes"
    differ = {}
    with open(stock, "rb") as f:
        for i, digest in enumerate(on_phone):
            data = f.read(CHUNK)
            if hashlib.sha256(data).hexdigest() != digest:
                differ[i] = subprocess.run(ADB + ["exec-out", f"dd if={SYSTEM} bs={CHUNK} skip={i} count=1 2>/dev/null"],
                                           check=True, capture_output=True).stdout[:len(data)]
                assert hashlib.sha256(differ[i]).hexdigest() == digest, f"MiB {i} changed while reading"
    print(f"system_a differs from the stock image in {len(differ)} MiB")
    return differ


def build_patch(stock: Path, phone: dict[int, bytes], image: Path) -> None:
    extents = dm_extents()
    for old in WORK.glob("blk_*.new"):
        old.unlink()
    lines = []
    with open(stock, "rb") as a, open(image, "rb") as b:
        block = 0
        while chunk_a := a.read(BLOCK):
            chunk_b = b.read(BLOCK)
            if (i := block * BLOCK // CHUNK) in phone:
                offset = block * BLOCK % CHUNK
                chunk_a = phone[i][offset:offset + BLOCK]
            if chunk_a != chunk_b:
                sector = block * BLOCK // 512
                [(start, _, dev)] = [e for e in extents if e[0] <= sector < e[1]]
                (WORK / f"blk_{block}.new").write_bytes(chunk_b)
                lines.append(f"{block} {dev + sector - start} {hashlib.sha256(chunk_a).hexdigest()} "
                             f"{hashlib.sha256(chunk_b).hexdigest()}")
            block += 1
    (WORK / "patch.txt").write_text("\n".join(lines) + "\n")
    print(f"{len(lines)} changed blocks")


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        sys.exit(__doc__)
    stock = Path(args[0])
    on_phone = adb("shell", "sha256sum /product/media/bootanimation.zip /product/media/shutdownanimation.zip 2>/dev/null || true")
    if all(hashlib.sha256((OUT / n).read_bytes()).hexdigest() in on_phone for n in ("bootanimation.zip", "shutdownanimation.zip")):
        print("already installed: /product/media has both animations")
        return
    image = build_image(stock)
    adb("root")
    subprocess.run(ADB + ["wait-for-device"], check=True)
    build_patch(stock, phone_chunks(stock), image)
    adb("shell", f"rm -rf {PHONE_DIR}; mkdir -p {PHONE_DIR}")
    files = [HERE / "patch_blocks.sh", WORK / "patch.txt", *sorted(WORK.glob("blk_*.new"))]
    adb("push", *map(str, files), PHONE_DIR)
    mode = "write" if "--write" in sys.argv else "check"
    result = subprocess.run(ADB + ["shell", f"sh {PHONE_DIR}/patch_blocks.sh {mode}; s=$?; rm -rf {PHONE_DIR}; exit $s"],
                            capture_output=True, text=True)
    print(result.stdout + result.stderr, end="")
    sys.exit(result.returncode)


if __name__ == "__main__":
    main()
