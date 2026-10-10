# Boot screens

From power-on to the chat, the RAVOZ shows nothing but the or1 wordmark: lowercase, square pixel blocks, off-white
(`#ECEAE4`, the app's text colour) on pure black, centred, the same frame at every stage. All three letters are 5
blocks tall, the height of the status bar's digits; the r ends in a one-block drop.

```
### ###  #
# # # # ##
# # #    #
# # #    #
### #    #
```

`make_boot.py` draws it (20 px per block) and builds everything from the stock logo partition, standard library only:

```sh
python3 brand/make_boot.py backup/20261009-202452/by-name/logo.bin   # -> brand/out/
```

## The stages

| Stage | What drew it | Now | How |
|---|---|---|---|
| "Orange state" warning (~5 s) | LK, for the unlocked bootloader | unchanged | LK is signature-checked by the preloader; patching it risks a brick |
| RAVOZ logo | `logo` partition, image 0 | or1 | `out/logo.bin`, flashed from LK fastboot (below) |
| Kernel logo | `logo` partition, images 2 and 38 | or1 | same file |
| Android boot animation | AOSP's default "android" shimmer (no zip on this GSI) | or1, static | `out/bootanimation.zip` in `/product/media` (`install_bootanim.py`) |
| Lock screen (wallpaper, clock) | Keyguard | gone | `locksettings set-disabled true` (`provision.sh`) |
| Wallpaper (shown behind "Phone is starting…") | framework default | black | `DebugReceiver --es wallpaper black` (`provision.sh`) |
| Shutdown | default | or1 | `out/shutdownanimation.zip` |
| Off-mode charging | `logo` partition, images 1, 3–37, 39–59 | pixel battery | `out/logo.bin` (below) |

The chat is pure black too (`Palette.Background`, and the window background so the first frame is black), with no
splash icon.

## Charging screens

When the phone charges while off, LK draws the charging screen from the same `logo` partition: a background, a fill
box (x 270–449, y 707–1024) that it fills with the level frames, the "wave" frames and the fill line, and the percentage
above it from the digit tiles. `make_boot.py` redraws all of them in the wordmark's blocks (10 px): a pixel battery
outline in dim grey on black, off-white fill, one accent (`#D97757`) block marching along the fill's top edge, and the
percentage in the pixel digits. Low battery is the empty outline with one accent cell; full is the filled battery with
"100%". `out/charging.png` is a contact sheet of all 60 images.

## Status bar

SystemUI draws no clock or icons (`Lockdown`: `DISABLE_CLOCK`, `DISABLE_SYSTEM_INFO`, `DISABLE2_SYSTEM_ICONS`); the
app draws its own bar just below it, clear of the camera notch (`StatusRail.kt`), in the same pixel font: `or1 ···`
on the left, `21:14 87 ▂▃▄▅` on the right. The three blocks after the wordmark march in the accent colour while the
agent works; the battery is its number, bright while charging and grey otherwise; Wi-Fi shows `x` when not connected.

## Logo partition (needs the Mac)

`logo` is an MTK logo image: a 512-byte header, then 60 zlib-compressed 720x1600 BGRA images (boot logo, kernel logo,
the charging screens). `make_boot.py` redraws all 60, at their stock sizes, and keeps the partition's size. The eMMC
write-protects `logo` while Android runs ("write protection violation", like `boot_a`), so it can only be flashed from
LK's fastboot, on the Mac:

```sh
scp archlinux:code/or1/brand/out/logo.bin ~/or1-logo.bin
shasum -a 256 ~/or1-logo.bin          # e2b5035d3681e7a0... (sha256sum brand/out/logo.bin here)
adb -s RAV1022V1000265 reboot bootloader
fastboot devices -l                   # note the RAVOZ's fastboot serial; never run fastboot without -s
fastboot -s <serial> flash logo ~/or1-logo.bin
fastboot -s <serial> reboot
```

To undo: flash `backup/20261009-202452/by-name/logo.bin` the same way.

## Boot animation (from here, over adb)

The system partition can't be written while Android runs: its ext4 has `shared_blocks` (never mountable read-write),
its dm device is read-only, and `adb remount`'s overlay doesn't come up at boot on this GSI (mounted late, it hides
phh's `su`, which breaks the agent's root). So `install_bootanim.py` adds both zips to a copy of the flashed TrebleDroid
image with `debugfs`, finds the 4K blocks that changed (8: superblock, group descriptors, bitmaps, inodes, the
directory, the data), maps them through `system_a`'s dm table onto `super`, and `patch_blocks.sh` writes them on the
phone, refusing unless every target block still holds the original bytes, and re-reading each after a cache drop.
Run again after an earlier install, it first hashes `system_a` per MiB on the phone (read from `super` through the dm
table) and diffs against what is there.
While `system_a` is mounted, reading it shows blocks 0 and 1 (superblock, group descriptors) as the running ext4 holds
them, i.e. stale after a patch; check them through `super`, or after a reboot.

```sh
python3 brand/install_bootanim.py downloads/td-ci-20230905/system-td-arm64-ab-vanilla.img           # check only
python3 brand/install_bootanim.py downloads/td-ci-20230905/system-td-arm64-ab-vanilla.img --write   # then reboot
```

Checked on 2026-10-10: `system_a` read back bit-identical to the patched image (sha256 `521417bb…`), the boot
animation loaded from `/product/media`, `fsck` clean, and the agent's root (`phhsu_daemon`) intact. To undo, flash the
stock TrebleDroid image to `system_a` from fastbootd (`docs/phase1-runbook.md`). A full backup of `super` is in
`backup/20261009-202452/by-name/super.bin`.
