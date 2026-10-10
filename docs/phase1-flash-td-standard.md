# Flash TrebleDroid 13 (standard) — Mac commands

Replaces runbook Step 5 for the **standard** image. The vndklite image hung at the logo under DSU;
the standard image booted, gave root, and passed the touch, audio, Wi-Fi and Bluetooth checks (2026-10-09).
Backup: `archlinux:~/code/or1/backup/20261009-202452` (53/55 partitions verified; the 2 misses are
stock-only views of the preloader, which is covered by `preloader_a/b` and `mmcblk0boot0/1`).

**Wipes stock `system` and all user data.** Every command targets the RAVOZ by serial; the OnePlus
(`d41317c3`) is never addressed. The phone should currently be running the TrebleDroid DSU.

```sh
# 1. Get the image and the stock vbmeta onto the Mac (~640 MB + 8 MB; delete afterwards)
mkdir -p ~/ravoz-flash && cd ~/ravoz-flash
curl -LO https://github.com/TrebleDroid/treble_experimentations/releases/download/ci-20230905/system-td-arm64-ab-vanilla.img.xz
shasum -a 256 system-td-arm64-ab-vanilla.img.xz   # e3bfcc02fd5cfdf358c6d4792d746f8cce49899382ba7ece1df7e3fe72de154e
xz -dk system-td-arm64-ab-vanilla.img.xz          # no xz? brew install xz
shasum -a 256 system-td-arm64-ab-vanilla.img      # 55541b9b263a35f5fa4bb9abd0a626c5b89139bb9a4454bb70ee95cf9246e7a5
scp archlinux:code/or1/backup/20261009-202452/by-name/vbmeta_a.bin .
shasum -a 256 vbmeta_a.bin                        # 297a4cee60a8f3ea8e3e8d32a1bd9073a59bcc6ac90f46d6e9e2ddaab5ea0cd3

# 2. Enter fastbootd (userspace fastboot) and find its serial
adb -s RAV1022V1000265 reboot fastboot
fastboot devices -l                               # note the RAVOZ serial -> FBD
FBD=<serial>
fastboot -s $FBD getvar is-userspace              # yes
fastboot -s $FBD getvar current-slot              # a   (if b: use vbmeta_b below)
fastboot -s $FBD getvar snapshot-update-status    # none; otherwise: fastboot -s $FBD snapshot-update cancel
fastboot -s $FBD gsi wipe                         # removes the DSU install

# 3. Flash system (fastbootd resizes system_a inside super)
fastboot -s $FBD erase system
fastboot -s $FBD flash system system-td-arm64-ab-vanilla.img
#    "Not enough space": fastboot -s $FBD delete-logical-partition product_a   then flash again

# 4. Disable verified boot on the stock vbmeta, wipe data, boot
fastboot -s $FBD reboot bootloader
fastboot devices -l                               # bootloader serial -> FB (often the same)
FB=<serial>
fastboot -s $FB --disable-verity --disable-verification flash vbmeta_a vbmeta_a.bin
fastboot -s $FB -w
fastboot -s $FB reboot
```

First boot can take a few minutes. Expected: TrebleDroid setup screen, and on this host
`adb -P 5038 -s RAV1022V1000265 shell getprop ro.build.fingerprint` ends in `userdebug/test-keys`.

**If it hangs at the logo for more than 5 minutes:** hold Power only for about 15 seconds. If it still won't boot,
enter fastbootd again (Power + Vol Up, choose Fastboot, then `fastboot -s $FB reboot fastboot`) and
send the output to Claude. Restore options are in `docs/phase1-runbook.md` (Rollback): flash another GSI, or
restore `super` + `vbmeta_a` from the backup.
