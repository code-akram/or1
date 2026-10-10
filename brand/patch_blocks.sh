#!/system/bin/sh
# Writes the changed 4K blocks of the system image straight into super (mmcblk0p40).
# mode=check: only verifies every target block still holds the original bytes; mode=write: checks, writes, re-reads.
set -e
cd "$(dirname "$0")"
dev=/dev/block/mmcblk0p40
mode=$1
# Reads go through the page cache (this dd's O_DIRECT returns nothing), so drop it to read what's on the eMMC.
fresh() { blockdev --flushbufs $dev; echo 3 > /proc/sys/vm/drop_caches; }
fresh
while read b sector old new; do
  cur=$(dd if=$dev bs=512 skip=$sector count=8 2>/dev/null | sha256sum | cut -d' ' -f1)
  if [ "$cur" = "$new" ]; then echo "block $b: already patched"; continue; fi
  [ "$cur" = "$old" ] || { echo "block $b: UNEXPECTED content at sector $sector, stopping"; exit 1; }
  echo "block $b: original content confirmed at sector $sector"
done < patch.txt
[ "$mode" = write ] || exit 0
while read b sector old new; do
  dd if=blk_$b.new of=$dev bs=512 seek=$sector count=8 conv=notrunc,fsync 2>/dev/null
  fresh
  cur=$(dd if=$dev bs=512 skip=$sector count=8 2>/dev/null | sha256sum | cut -d' ' -f1)
  [ "$cur" = "$new" ] && echo "block $b: written and verified" || { echo "block $b: VERIFY FAILED"; exit 1; }
done < patch.txt
