#!/usr/bin/env python3
"""Puts out/bootanimation.zip and out/shutdownanimation.zip into the phone's system partition, at /product/media.

The running system can't be written: its ext4 has shared_blocks (never mountable read-write) and its dm device is
read-only, and `adb remount`'s overlay doesn't mount at boot on this GSI (mounted late, it hides phh's su). So:
  1. copy the flashed TrebleDroid image and add both zips with debugfs (root:root 0644, system_file label);
  2. find what the phone's system_a holds now: the stock image, plus whatever an earlier run patched (it hashes
     system_a per MiB on the phone, read from super through the dm table, and pulls the MiBs that differ);
  3. diff that against the new image: a handful of 4K blocks (superblock, descriptors, bitmaps, inodes, dir, data);
  4. map each block through system_a's live dm table onto super (mmcblk0p40);
  5. on the phone, patch_blocks.sh checks every target still holds the bytes read in step 2, then writes and re-reads.
Afterwards system_a is bit-identical to the new image on the eMMC (until a reboot, reading system_a itself returns
blocks 0 and 1 as the mounted ext4 holds them, the old ones). A reboot picks the files up. Needs adb root and
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
SUPER = "/dev/block/mmcblk0p40"
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


def read_super(extents: list[tuple[int, int, int]], start: int, end: int) -> str:
    """A shell command printing system_a's sectors [start, end), read from super through the dm table (4K-aligned)."""
    parts = []
    for first, last, dev in extents:
        lo, hi = max(start, first), min(end, last)
        if lo < hi:
            parts.append(f"dd if={SUPER} bs={BLOCK} skip={(dev + lo - first) // 8} count={(hi - lo) // 8} 2>/dev/null")
    return "{ " + "; ".join(parts) + "; }"


def phone_chunks(stock: Path, extents: list[tuple[int, int, int]]) -> dict[int, bytes]:
    """The MiBs of the phone's system_a that differ from the stock image. Read from super after a cache drop: while
    system_a is mounted, reading it returns blocks 0 and 1 as the running ext4 holds them, stale after a patch."""
    size = stock.stat().st_size
    count = -(-size // CHUNK)
    sectors = lambda i: (i * CHUNK // 512, min((i + 1) * CHUNK, size) // 512)
    script = WORK / "hash.sh"
    script.write_text(f"blockdev --flushbufs {SUPER}; echo 3 > /proc/sys/vm/drop_caches\n" + "".join(
        f"{read_super(extents, *sectors(i))} | sha256sum\n" for i in range(count)))
    adb("push", str(script), f"{PHONE_DIR}/hash.sh")
    on_phone = [line.split()[0] for line in adb("shell", f"sh {PHONE_DIR}/hash.sh").splitlines()]
    assert len(on_phone) == count, f"{len(on_phone)} of {count} hashes"
    differ = {}
    with open(stock, "rb") as f:
        for i, digest in enumerate(on_phone):
            data = f.read(CHUNK)
            if hashlib.sha256(data).hexdigest() != digest:
                differ[i] = subprocess.run(ADB + ["exec-out", read_super(extents, *sectors(i))],
                                           check=True, capture_output=True).stdout
                assert hashlib.sha256(differ[i]).hexdigest() == digest, f"MiB {i} changed while reading"
    print(f"system_a differs from the stock image in {len(differ)} MiB")
    return differ


def build_patch(stock: Path, extents: list[tuple[int, int, int]], phone: dict[int, bytes], image: Path) -> None:
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
    adb("shell", f"rm -rf {PHONE_DIR}; mkdir -p {PHONE_DIR}")
    extents = dm_extents()
    build_patch(stock, extents, phone_chunks(stock, extents), image)
    files = [HERE / "patch_blocks.sh", WORK / "patch.txt", *sorted(WORK.glob("blk_*.new"))]
    adb("push", *map(str, files), PHONE_DIR)
    mode = "write" if "--write" in sys.argv else "check"
    result = subprocess.run(ADB + ["shell", f"sh {PHONE_DIR}/patch_blocks.sh {mode}; s=$?; rm -rf {PHONE_DIR}; exit $s"],
                            capture_output=True, text=True)
    print(result.stdout + result.stderr, end="")
    sys.exit(result.returncode)


if __name__ == "__main__":
    main()
