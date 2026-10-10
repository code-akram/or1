#!/usr/bin/env python3
"""Puts out/bootanimation.zip and out/shutdownanimation.zip into the phone's system partition, at /product/media.

The running system can't be written: its ext4 has shared_blocks (never mountable read-write) and its dm device is
read-only, and `adb remount`'s overlay doesn't mount at boot on this GSI (mounted late, it hides phh's su). So:
  1. copy the flashed TrebleDroid image and add both zips with debugfs (root:root 0644, system_file label);
  2. diff it against the original: a handful of 4K blocks (superblock, descriptors, bitmaps, inodes, dir, data);
  3. map each block through system_a's live dm table onto super (mmcblk0p40);
  4. on the phone, patch_blocks.sh checks every target still holds the original bytes, then writes and re-reads.
The phone's system_a must be bit-identical to the image (it is unless something wrote to it); step 4 refuses
otherwise. A reboot picks the files up. Needs adb root and debugfs/e2fsck (e2fsprogs) on this host.

Usage: python3 brand/install_bootanim.py downloads/td-ci-20230905/system-td-arm64-ab-vanilla.img [--write]
Without --write it stops after the on-phone check. Exits early when the phone already has both zips as built; on a
phone patched from another build it refuses at block 0 (debugfs stamps the superblock with the current time).
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


def build_patch(stock: Path, image: Path) -> None:
    extents = dm_extents()
    for old in WORK.glob("blk_*.new"):
        old.unlink()
    lines = []
    with open(stock, "rb") as a, open(image, "rb") as b:
        block = 0
        while chunk_a := a.read(BLOCK):
            chunk_b = b.read(BLOCK)
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
    build_patch(stock, image)
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
