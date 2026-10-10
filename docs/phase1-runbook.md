# Phase 1 runbook: RAVOZ V1, from stock + DSU to a rooted TrebleDroid Android 13 GSI

Research date: 2026-10-09. Nothing in this document was run against the phone. Every step is meant to be run by hand on the Mac.

> **Safety rule for every command.** The Mac's ADB server also has the OnePlus 10 Pro attached (`d41317c3`). Always pass `-s RAV1022V1000265` to `adb` and `-s <fastboot-serial>` to `fastboot` (step 3.1 explains how to find it). Never run `adb kill-server`, `adb reconnect`, `adb usb`, `adb tcpip`, or any `fastboot` command without `-s` (a bare `fastboot -w` is the dangerous one). mtkclient talks to MediaTek USB IDs (VID `0x0E8D`) directly through libusb, so it cannot reach the OnePlus.

---

## 0. Recommendation (TL;DR)

**GSI:** TrebleDroid **ci-20230905** (android-13.0.0_r73, August 2023 SPL). This is the final and fixed TrebleDroid Android 13 build. The previous release, ci-20230830, broke boot.

| Variant | URL | Size (.xz) | Unpacked |
|---|---|---|---|
| **`system-td-arm64-ab-vndklite-vanilla.img.xz`** (recommended) | https://github.com/TrebleDroid/treble_experimentations/releases/download/ci-20230905/system-td-arm64-ab-vndklite-vanilla.img.xz | 629,373,872 B | 1,949,241,344 B (raw ext4, read-write capable) |
| `system-td-arm64-ab-vanilla.img.xz` (fallback) | https://github.com/TrebleDroid/treble_experimentations/releases/download/ci-20230905/system-td-arm64-ab-vanilla.img.xz | 641,469,308 B | 1,931,804,672 B (ext4 with shared_blocks, which mounts read-only) |

Release page: https://github.com/TrebleDroid/treble_experimentations/releases/tag/ci-20230905

**Why vndklite on a non-lite (VNDK 31) vendor?** The vndklite image has had `e2fsck -E unshare_blocks` run on it, so `/system` can be remounted read-write. That is what a priv-app install needs. phhusson states that non-vndklite devices can run vndklite variants. The non-lite image can only be "written" through `adb remount` overlayfs.

**What you get (verified from the build scripts, not yet on the device):**
- **Build type `userdebug`.** TD's `build.sh` runs `lunch treble_arm64_bvS-userdebug`. As a result, `ro.debuggable=1` and **`adb root` works**.
- **`S` means superuser is included.** `generate.sh` adds the `phh-su` and `me.phh.superuser` packages. `su` is a phh-su daemon (`service sudaemon /system/bin/phh-su --daemon`), and the Superuser app shows grant prompts to apps. From the shell, use `adb root` and skip `su`. The touchscreen is unreliable, so grant prompts are a problem anyway.
- **Signed with AOSP public test-keys.** Neither `build.sh` nor TD's `platform_build` fork contains a signing step, so you get the default `build/make/target/product/security/*` keys. **You can therefore platform-sign the agent app.** Confirm on the device in step 6.3.
- **ADB is unauthenticated (`ro.adb.secure=0` in `base.mk`) and `persist.sys.usb.config=adb`.** Anyone with a USB cable gets a root shell, and adb also comes up during a bootloop, which helps recovery. "Securize" (Phh Treble Settings → Misc) deletes su, spoofs `ro.debuggable=0`, `ro.build.type=user`, `ro.build.tags=release-keys` and `ro.adb.secure=1`. **Do not securize this device.** It removes root, and the signing keys stay test-keys anyway.

**Alternative:** if TD will not boot, use an AOSP `aosp_arm64-userdebug` GSI from ci.android.com (`aosp-android13-gsi` branch). It is test-keys signed and supports `adb root` and `adb remount`, but it has no app-facing su and no MediaTek quirk fixes. **UNVERIFIED** whether ci.android.com still serves Android 13 GSI artifacts after AOSP's 2025 publishing changes. The Google *release* GSI you run now (`TQ3A.230805.001.A3`, `user/release-keys`) is unsuitable: it has no root and cannot be platform-signed.

---

## 1. Facts: verified vs UNVERIFIED

### Verified (from primary sources, 2026-10-09)
| Fact | Source |
|---|---|
| TD Android 13 releases run from ci-20221107 to **ci-20230905** (last A13). ci-20230905 is described as "Fixes boot that got broken by previous version". Later tags are A14, A15 and A16. | GitHub API, [releases](https://github.com/TrebleDroid/treble_experimentations/releases) |
| ci-20230905 ships these assets: `system-td-arm64-ab-vanilla`, `system-td-arm64-ab-vndklite-vanilla`, `system-td-arm32_binder64-ab-{vanilla,vndklite-vanilla}` (`.img.xz`), `manifest.xml`, `patches-for-developers.zip`. There is no gapps variant and no separate secure variant. Both URLs above return HTTP 200. | GitHub API |
| **No checksums are published** for these assets (the API `digest` is null). Only the byte sizes above can be checked. | GitHub API |
| Unpacked sizes come from reading each xz index: 1.80 GiB (vanilla) and 1.82 GiB (vndklite). | xz index read by HTTP range request |
| The A13 build target is `treble_arm64_bvS-userdebug` on `android-13.0.0_r73`, built with no re-signing step. | [build.sh @ e404618](https://github.com/TrebleDroid/treble_experimentations/blob/e404618671a7d48342fde46c5c00a5c2280514fa/build.sh) |
| `S` adds `phh-su me.phh.superuser`. | [device_phh_treble generate.sh @ a5d5328](https://github.com/TrebleDroid/device_phh_treble/blob/a5d5328f43b8d05a67dda385c66d7952a107d0e4/generate.sh) (the commit pinned in the ci-20230905 manifest) |
| `ro.adb.secure=0` and `persist.sys.usb.config=adb` | [base.mk @ a5d5328](https://github.com/TrebleDroid/device_phh_treble/blob/a5d5328f43b8d05a67dda385c66d7952a107d0e4/base.mk) |
| Securize removes su and spoofs props to user, release-keys and secure. | [phh-securize.sh](https://github.com/TrebleDroid/device_phh_treble/blob/a5d5328f43b8d05a67dda385c66d7952a107d0e4/phh-securize.sh), [rw-system.sh L745-787](https://github.com/TrebleDroid/device_phh_treble/blob/a5d5328f43b8d05a67dda385c66d7952a107d0e4/rw-system.sh) |
| On every boot (non-Samsung devices), `rw-system.sh` tries `mount -o remount,rw /system` (or `/`) followed by `resize2fs`, so a writable fs grows to fill its logical partition. | rw-system.sh L334-349 |
| vndklite is built by `sas-creator/lite-adapter.sh`: `e2fsck -E unshare_blocks`, then `resize2fs -M`, which shrinks the fs to its minimum size and leaves almost no free space. | [lite-adapter.sh](https://github.com/TrebleDroid/sas-creator/blob/master/lite-adapter.sh) |
| "If you want unshared_blocks/read-write system, use vndklite variants. Non-vndklite devices are capable of running vndklite variants." | [phh v400.b notes](https://newreleases.io/project/github/phhusson/treble_experimentations/release/v400.b), [phh discussion #2494](https://github.com/phhusson/treble_experimentations/discussions/2494) |
| AOSP platform test cert (`android-13.0.0_r73` `platform.x509.pem`): SHA-256 `C8:A2:E9:BC:CF:59:7C:2F:B6:DC:66:BE:E2:93:FC:13:F2:FC:47:EC:77:BC:6B:2B:0D:52:C1:1F:51:19:2A:B8`, SHA-1 `27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA` | [googlesource](https://android.googlesource.com/platform/build/+/refs/tags/android-13.0.0_r73/target/product/security/) |
| Google A13 GSI `TQ3A.230805.001.A3` ARM64 zips: `aosp_arm64-exp-TQ3A.230805.001.A3-10454027-66e107ed.zip` (sha256 `66e107edaf84b74ba1572be9a4216bf7ae45b3b7f0b516ba2b7aa365ba763905`) and the GMS zip (`3adcae59…a81f`). | [GSI releases](https://developer.android.com/topic/generic-system-image/releases) |
| Google's GSI flash flow: `fastboot reboot fastboot` → `erase system` → `flash system` → `-w` → `reboot-bootloader` → `--disable-verification flash vbmeta vbmeta.img`. On a space error, `delete-logical-partition product_a` (the suffix must match the slot). | [source.android.com GSI](https://source.android.com/docs/core/tests/vts/gsi) |
| mtkclient is actively maintained: v2.1.4.1 (2026-03-18), last commit 2026-09-12. | [bkerler/mtkclient](https://github.com/bkerler/mtkclient) |
| mtkclient's config has an entry for **MT6761** (hwcode `0x717`, "Helio A20/P22/A22/A25/G25"). It specifies the XFLASH DA mode and a dedicated BROM payload `mt6761_payload.bin`. MT6761 is **not** in the patched-bootrom "V6" list (MT6781/6789/6855/6886/6895/6983/8985). | [brom_config.py](https://github.com/bkerler/mtkclient/blob/main/mtkclient/config/brom_config.py), [README](https://github.com/bkerler/mtkclient/blob/main/README.md) |
| mtkclient commands: `rl <dir> [--skip a,b]` writes `gpt.bin`, `gpt_backup.bin` and `<partition>.bin` for every user-area GPT partition. `wl <dir>` writes every file whose name matches a partition, plus `gpt.bin` at sector 0. `r`/`w` work on named partitions. `--parttype boot1/boot2` selects the eMMC boot areas (preloader). `printgpt`, `rf`, `reset`, `e`. | [README-USAGE](https://github.com/bkerler/mtkclient/blob/main/README-USAGE.md), [mtk.py](https://github.com/bkerler/mtkclient/blob/main/mtk.py), [mtk_da_handler.py](https://github.com/bkerler/mtkclient/blob/main/mtkclient/Library/DA/mtk_da_handler.py) |
| mtkclient macOS install: `brew install macfuse openssl`, a venv, `pip3 install -r requirements.txt` (uses pyusb). macFUSE is only needed for `mtk fs`. | [README-INSTALL](https://github.com/bkerler/mtkclient/blob/main/README-INSTALL.md) |
| BROM entry (generic): power off, hold **Vol Up + Power** or **Vol Down + Power**, then connect USB. Release the buttons once the tool detects the phone. | mtkclient README |
| Firmware: FirmwareDrive lists **one** Ravoz V1 file, `Ravoz_V1_MT6761_V3.11_230518.zip` (1.55 GB, possibly paid). A MartView list names `RAVOZ.V1_3-64.S.G3FZRG.20220906.V3.05`. **No V3.09 package for the V1 was found.** The only "V3.09" in that list belongs to the **Z7 Pro**. | [FirmwareDrive Ravoz V1](https://firmwaredrive.com/index.php?a=downloads&b=folder&id=40365), [MartView thread](https://www.martview-forum.com/threads/ravoz-firmware-download.86480/) (now 404) |

### UNVERIFIED (check on the device or accept the risk)
- **UNVERIFIED** that TD ci-20230905 boots on this MT6761 vendor. Positive signs: Google's A13 arm64 GSI already boots via DSU, and Helio A22 users report TD-based A13 GSIs working. One report needed an adb command to fix Wi-Fi and mobile data ([XDA LOS20-TD thread](https://xdaforums.com/t/discontinued-gsi-13-lineageos-20-trebledroid-based.4517345/page-50)).
- **UNVERIFIED** that the on-device fingerprint contains `userdebug/test-keys`. It is expected from the build scripts. Step 6.3 checks the actual certificate.
- **UNVERIFIED** whether `ro.vndk.lite` and `ro.virtual_ab.enabled` are set on this vendor. Step 1 records them.
- **UNVERIFIED** the RAVOZ fastboot and fastbootd serial (it may differ from `RAV1022V1000265`; MTK LK often reports the same `androidboot.serialno`, but not always).
- **UNVERIFIED** the super, system_a and product_a sizes, and whether ~1.95 GB fits without deleting `product_a`.
- **UNVERIFIED** the MT6761 BROM key combo on *this* phone, and whether this unit has SLA/DAA enabled. mtkclient handles these on MT6761 through its BROM payload, but this has not been tested here.
- **UNVERIFIED** how mtkclient behaves on macOS/Apple Silicon (libusb timing, macOS CDC driver grabbing the preloader port). Fallback: run mtkclient on a Linux machine (the Arch host has `libusb` and a udev rules file in `Setup/Linux/`).
- **UNVERIFIED** that `fastboot resize-logical-partition` (grow) keeps the flashed data intact. It is expected to, because grow appends extents, but test it once on this device.
- **UNVERIFIED** whether the V3.11 FirmwareDrive package is free, intact or matches the 3+64 hardware variant. V3.11 is *newer* than the installed V3.09, so its preloader/LK could differ (rollback risk).
- **UNVERIFIED** the value of `ro.control_privapp_permissions` on TD. Assume `enforce`, which means a priv-app without an allowlist XML can bootloop the device.

---

## 2. Mac prerequisites (one-time)

| Purpose | Command | Verify |
|---|---|---|
| Install platform-tools (fastboot ≥ 33 for fastbootd and logical partition commands), xz, libusb and Python | `brew install --cask android-platform-tools && brew install xz libusb python@3.12` | `fastboot --version` reports 35.x or newer; `xz --version` |
| Install mtkclient | `git clone https://github.com/bkerler/mtkclient ~/src/mtkclient && cd ~/src/mtkclient && python3.12 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt && pip install .` | `python mtk.py --help` lists `rl`, `wl`, `printgpt` |
| Install avbtool (to create a disabled vbmeta if needed) | `curl -sL 'https://android.googlesource.com/platform/external/avb/+/refs/tags/android-13.0.0_r73/avbtool.py?format=TEXT' \| base64 -D > ~/bin/avbtool && chmod +x ~/bin/avbtool` | `avbtool version` |
| Install apksigner (optional, for checking signing certs) | Android SDK build-tools (`sdkmanager "build-tools;34.0.0"`) | `apksigner version` |

The README asks for macFUSE, but it is only needed for `mtk fs` (mounting the flash as a filesystem), so skip it. Skip PySide6 unless you want the GUI. No kext or driver is needed for libusb on macOS. **UNVERIFIED** on Apple Silicon (see section 1).

---

## Step 1: Record the current state (read-only, phone booted)

**Purpose:** capture the facts needed to choose an image and plan space, without changing anything.

Run these on the DSU GSI now. Vendor props are the same under DSU.
```sh
S=RAV1022V1000265
adb -s $S shell getprop > ravoz-getprop-dsu.txt
adb -s $S shell getprop ro.vndk.lite            # empty/false = non-lite (either image works); true = MUST use vndklite
adb -s $S shell getprop ro.virtual_ab.enabled   # true = Virtual A/B (no real system_b; see Step 5 notes)
adb -s $S shell getprop ro.boot.slot_suffix     # e.g. _a
adb -s $S shell getprop ro.product.cpu.abilist  # must include arm64-v8a (it does, since arm64 GSI boots)
adb -s $S shell getprop ro.vendor.build.fingerprint
```
**Measured on 2026-10-09 (on the DSU, read-only):**
- `ro.vndk.lite` is empty, so the vendor is **not** VNDK-lite. Either TD image works, and vndklite stays the choice because it allows a writable `/system`.
- `ro.virtual_ab.enabled=true`, so this is **Virtual A/B**. There is no physical `system_b`, so the snapshot-cancel check in Step 5.2 matters.
- Current slot is `_a`.
- Vendor fingerprint: `RAVOZ/RAVOZ_V1_3_64/RAVOZ_V1_3_64:12/SP1A.210812.016/1692877872:user/release-keys` (vendor built 2023-08-24; 3 GB RAM / 64 GB variant).
- Block devices by name: `boot_a/b dtbo_a/b gz_a/b lk_a/b md1img_a/b preloader_a/b preloader_raw_a/b scp_a/b spmfw_a/b sspm_a/b tee_a/b vbmeta_a/b vbmeta_system_a/b vbmeta_vendor_a/b vendor_boot_a/b`, plus `boot_para expdb flashinfo frp logo md_udc metadata nvcfg nvdata nvram otp para persist proinfo protect1 protect2 sec1 seccfg super userdata` and eMMC `mmcblk0boot0/boot1`.

Next, leave DSU. Use the DSU notification → **Restart** (or simply reboot) to boot stock V3.09, then repeat `getprop > ravoz-getprop-stock.txt`.

**Verify:** the files exist and `ro.vendor.build.fingerprint` mentions V3.09 or the RAVOZ build.
**Rollback:** not applicable (read-only).

---

## Step 2: Full backup with mtkclient (most important step)

**Purpose:** without a verified stock firmware package, **your own dump is the only reliable restore path**. It also preserves the device-unique `nvram`, `nvdata`, `nvcfg`, `protect1/2`, `proinfo`, `persist` and `seccfg` partitions, which hold IMEI and calibration data and are not in any firmware zip.

1. Check free disk space on the Mac: you need ≈ (eMMC size − userdata) plus about 10 GB. Most of it is `super` (a few GB).
2. Prove the connection with a read-only command. Power the phone **off** completely and unplug it. Then start:
   ```sh
   cd ~/src/mtkclient && . .venv/bin/activate
   python mtk.py printgpt
   ```
   While it waits, hold **Vol Up + Vol Down** (most MT6761 budget phones), or **Vol Down + Power**, or **Vol Up + Power**, and plug in USB. Release once the tool prints `hwcode 0x717`. Without buttons, the phone connects in preloader mode. If that times out, use a BROM combo instead.
   **Verify:** you see the user-area partition list (the preloader lives in eMMC boot1/boot2, not here), e.g. `pgpt`, `boot_para`, `para`, `expdb`, `frp`, `nvcfg`, `nvdata`, `md_udc`, `metadata`, `protect1`, `protect2`, `seccfg`, `persist`, `sec1`, `proinfo`, `nvram`, `logo`, `md1img_a`, `spmfw_a`, … `lk_a`, `boot_a`, `vendor_boot_a`?, `dtbo_a`, `vbmeta_a`, `vbmeta_system_a`, `vbmeta_vendor_a`, `super`, `userdata`, …). **Save the output:** `python mtk.py printgpt > ravoz-gpt.txt` (do this on the next connect).
3. Dump all user-area partitions except userdata (and optionally `md_udc`, which is huge on some devices):
   ```sh
   python mtk.py rl ~/ravoz-backup/user --skip userdata
   ```
4. Dump the eMMC boot areas (preloader) **into a separate directory**, so a later `wl` on `user/` can never write them:
   ```sh
   python mtk.py r preloader ~/ravoz-backup/emmc-boot/preloader_boot1.bin --parttype boot1
   python mtk.py r preloader ~/ravoz-backup/emmc-boot/preloader_boot2.bin --parttype boot2
   ```
5. Optional: `python mtk.py rf ~/ravoz-backup/full-user-area.bin` (a raw image of the whole user area, including userdata, which is as big as the eMMC).
6. `python mtk.py reset`, unplug, and boot normally.

**Verify:**
- `ls -l ~/ravoz-backup/user` lists `gpt.bin`, `super.bin`, `vbmeta_a.bin`, `boot_a.bin`, `nvram.bin`, `nvdata.bin`, `seccfg.bin`, … Their sizes should match the sector counts in `ravoz-gpt.txt`.
- `shasum -a 256 ~/ravoz-backup/user/* ~/ravoz-backup/emmc-boot/* > ~/ravoz-backup/SHA256SUMS`, then copy the whole folder to a second disk.
- The phone still boots stock afterwards.

**Restore (rollback or unbrick).** This works whenever BROM is reachable. BROM is mask ROM, so fastboot or LK being gone does not matter.
```sh
# Selective restore (preferred), e.g. after a bad GSI flash:
python mtk.py w super,vbmeta_a,vbmeta_system_a,vbmeta_vendor_a \
  ~/ravoz-backup/user/super.bin,~/ravoz-backup/user/vbmeta_a.bin,~/ravoz-backup/user/vbmeta_system_a.bin,~/ravoz-backup/user/vbmeta_vendor_a.bin
python mtk.py e metadata,userdata    # if Android then complains about encryption (data is wiped)
# Full user-area restore (last resort; writes gpt.bin + every <name>.bin in the dir):
python mtk.py wl ~/ravoz-backup/user
# Preloader only if it was actually damaged:
python mtk.py w preloader ~/ravoz-backup/emmc-boot/preloader_boot1.bin --parttype boot1
python mtk.py reset
```
**Risks:** writing the wrong preloader, or erasing `nvram`, `nvdata` or `protect*`, loses IMEI and calibration data. Never use `wl` on a directory that contains files from a different device. If BROM entry fails on this unit (because of a locked-down efuse or SLA without a working bypass), mtkclient cannot unbrick it. **Step 2.2 succeeding is what proves the unbrick path. Do not continue to Step 5 until it works.**

---

## Step 3: Download and check the GSI (on the Mac)

```sh
mkdir -p ~/ravoz-gsi && cd ~/ravoz-gsi
curl -LO https://github.com/TrebleDroid/treble_experimentations/releases/download/ci-20230905/system-td-arm64-ab-vndklite-vanilla.img.xz
stat -f%z system-td-arm64-ab-vndklite-vanilla.img.xz     # expect 629373872
xz -t system-td-arm64-ab-vndklite-vanilla.img.xz           # integrity of the xz stream (built-in CRC)
xz -dk system-td-arm64-ab-vndklite-vanilla.img.xz
stat -f%z system-td-arm64-ab-vndklite-vanilla.img          # expect 1949241344
shasum -a 256 *.img* > SHA256SUMS                          # record (no upstream checksum exists)
```
**Verify:** the sizes match and `xz -t` exits 0. Upstream publishes no checksum, so the xz CRC and the size are the only integrity checks.
**Rollback:** delete the files.

### 3.1 Find the fastboot serials (do this once)
```sh
adb -s RAV1022V1000265 reboot bootloader
fastboot devices -l          # LK/bootloader fastboot; note the serial shown → FB
fastboot -s <FB> reboot fastboot
fastboot devices -l          # fastbootd; note serial → FBD (often same; UNVERIFIED)
fastboot -s <FBD> getvar is-userspace   # expect: yes
```
Only devices *in fastboot mode* appear here. The OnePlus will be absent unless it is also in fastboot, but always pass `-s` anyway. The rest of this document uses `FBD` for the fastbootd serial and `FB` for the bootloader serial.

---

## Step 4 (optional, low risk): smoke-test TD through DSU first

**Purpose:** check that TD boots and that Wi-Fi, audio and root work, without touching `super`. This is the same mechanism already used for the Google GSI. It works with an unlocked bootloader. **UNVERIFIED** with TD specifically.

1. In the current DSU, use the notification to **Discard** it, then boot stock.
2. Prepare and install:
   ```sh
   gzip -c system-td-arm64-ab-vndklite-vanilla.img > td.img.gz
   adb -s $S push td.img.gz /storage/emulated/0/Download/
   adb -s $S shell setprop persist.sys.fflag.override.settings_dynamic_system true
   adb -s $S shell am start-activity -n com.android.dynsystem/com.android.dynsystem.VerificationActivity \
     -a android.os.image.action.START_INSTALL -d file:///storage/emulated/0/Download/td.img.gz \
     --el KEY_SYSTEM_SIZE 1949241344 --el KEY_USERDATA_SIZE 4294967296
   ```
   Then confirm the DSU notification and restart.

**Verify:** `adb -s $S shell getprop ro.build.fingerprint` (expect `…:userdebug/test-keys`). `adb -s $S root && adb -s $S shell id` should show uid=0. Also check Wi-Fi, the mic and the speaker.
**Rollback:** reboot. DSU is one-shot unless you enable it persistently, so the next boot is stock.

---

## Step 5: Flash TD into `super`

**Before you start:** Step 2 is complete and the backup is verified. The phone is on **stock** (not DSU), charged above 50%. This step **wipes userdata**.

1. **Enter fastbootd.**
   `adb -s RAV1022V1000265 reboot fastboot` → `fastboot -s <FBD> getvar is-userspace` should print `yes`.
2. **Collect state and remove leftover DSU or OTA data.**
   ```sh
   fastboot -s <FBD> getvar current-slot
   fastboot -s <FBD> getvar snapshot-update-status    # if not "none": fastboot -s <FBD> snapshot-update cancel
   fastboot -s <FBD> gsi status                       # if installed: fastboot -s <FBD> gsi wipe
   fastboot -s <FBD> getvar all 2>&1 | tee ravoz-getvar-all.txt
   grep -E 'partition-size:(super|system_|product_|system_ext_|vendor_)|is-logical' ravoz-getvar-all.txt
   ```
   **Verify:** note the slot (`a` or `b`; this runbook assumes `_a`, so substitute if needed), and the sizes of `super`, `system_a` and `product_a`.
3. **Flash system** to the current slot. fastbootd resizes the logical partition automatically.
   ```sh
   fastboot -s <FBD> erase system
   fastboot -s <FBD> flash system system-td-arm64-ab-vndklite-vanilla.img
   ```
   If you get `Not enough space to resize partition`: `fastboot -s <FBD> delete-logical-partition product_a` (the suffix must match the slot), then flash again. Leave `system_ext_a` alone unless it still does not fit. **UNVERIFIED** whether this vendor's fstab tolerates a missing system_ext. On Virtual A/B devices, any `*_b`/`-cow` leftovers are also reclaimable, but cancel snapshots first (2.).
4. **Optional: add headroom** for priv-apps. TD's `rw-system.sh` grows the fs to the partition size on boot.
   ```sh
   fastboot -s <FBD> resize-logical-partition system_a 2214592512   # +~250 MiB; UNVERIFIED data-preserving, see §1
   ```
   Skip this the first time if you want the most conservative path. You can do it later from fastbootd.
5. **Disable AVB verification** on the top-level vbmeta. Use **your own stock vbmeta** from the backup:
   ```sh
   fastboot -s <FBD> reboot bootloader
   fastboot -s <FB> --disable-verity --disable-verification flash vbmeta_a ~/ravoz-backup/user/vbmeta_a.bin
   ```
   If you prefer a "blank" vbmeta: `avbtool make_vbmeta_image --flags 3 --padding_size 4096 --output vbmeta_disabled.img`, then flash that with the same flags. A blank vbmeta drops chained descriptors. Both approaches work on most devices, but the stock+flags approach is less invasive. If your partition is named plain `vbmeta` rather than `vbmeta_a`, use the name from `printgpt`.
6. **Wipe data** (required: Android 12 metadata encryption plus a new system). This targets the RAVOZ only:
   ```sh
   fastboot -s <FB> -w
   fastboot -s <FB> reboot
   ```
   First boot can take up to 5 minutes. adb comes up early and needs no authorization, so you can watch it with `adb -s RAV1022V1000265 logcat`. The adb serial should still be `RAV1022V1000265`. Check `adb devices -l` (never `adb devices` combined with server-wide commands).

**Verify:** the phone boots to the TrebleDroid launcher, and `adb -s RAV1022V1000265 shell getprop ro.product.system.model` contains `TrebleDroid`.

**Rollback, in order of preference:**
- a) **Different GSI:** in fastbootd, `fastboot -s <FBD> flash system <other system.img>` (e.g. the non-lite TD image or Google's GSI).
- a2) **Stock system only:** extract `system_a.img` from `~/ravoz-backup/user/super.bin` with `lpunpack` (AOSP tool), then flash it from fastbootd. Or restore the whole super as in b).
- b) **Full stock restore:** `python mtk.py w super,vbmeta_a … ` as in Step 2's restore block, then `python mtk.py e metadata,userdata`.
- c) **Stuck at the logo:** adb is usually up, so take a logcat. Otherwise use the stock recovery (Power + Vol Up), wipe data, and retry. Also try the non-lite `system-td-arm64-ab-vanilla.img`.

---

## Step 6: Root, read-write /system, priv-app, platform signing

### 6.1 Root
```sh
S=RAV1022V1000265
adb -s $S root && adb -s $S wait-for-device
adb -s $S shell id                         # uid=0(root)
adb -s $S shell getprop ro.debuggable      # 1
adb -s $S shell getprop ro.build.fingerprint   # expect …:13/…/…:userdebug/test-keys
adb -s $S shell 'ls -l /system/bin/phh-su /system/xbin/su; pm list packages me.phh.superuser'
```
Apps call `su` through phh-su and get a grant prompt from `me.phh.superuser`. Plan to use `adb root` for setup, and avoid needing on-screen prompts (the touchscreen is unreliable).

### 6.2 Make /system writable
```sh
adb -s $S shell 'mount -o remount,rw / || mount -o remount,rw /system'
adb -s $S shell 'df -h / /system; touch /system/.rwtest && rm /system/.rwtest && echo RW-OK'
```
**Fallback** (non-lite image, or no free space): `adb -s $S remount`, then `adb -s $S reboot`. This uses an overlayfs backed by /data or a scratch partition, and changes are lost on `fastboot -w`. **UNVERIFIED** on this kernel (4.19 should have overlayfs).

### 6.3 Confirm the device is platform-signed with AOSP test-keys
```sh
adb -s $S pull /system/framework/framework-res.apk
apksigner verify --print-certs framework-res.apk | grep -i 'SHA-256'
# expect: c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8
```
If it matches, **you can platform-sign the agent**:
```sh
for f in platform.pk8 platform.x509.pem; do
  curl -sL "https://android.googlesource.com/platform/build/+/refs/tags/android-13.0.0_r73/target/product/security/$f?format=TEXT" | base64 -D > $f; done
apksigner sign --key platform.pk8 --cert platform.x509.pem --out agent-platform.apk agent.apk
# Gradle: convert to a keystore
openssl pkcs8 -inform DER -nocrypt -in platform.pk8 -out platform.key
openssl pkcs12 -export -in platform.x509.pem -inkey platform.key -name platform -out platform.p12 -passout pass:android
```
Security note: these keys are public. Any APK signed with them gets signature-level permissions on this device. That is acceptable only on a throwaway, isolated test phone.

### 6.4 Install as a priv-app
```sh
adb -s $S shell mkdir -p /system/priv-app/OrAgent
adb -s $S push agent-platform.apk /system/priv-app/OrAgent/OrAgent.apk
adb -s $S push privapp-permissions-or.agent.xml /system/etc/permissions/
adb -s $S shell 'chmod 755 /system/priv-app/OrAgent; chmod 644 /system/priv-app/OrAgent/OrAgent.apk /system/etc/permissions/privapp-permissions-or.agent.xml; restorecon -R /system/priv-app/OrAgent /system/etc/permissions'
adb -s $S shell getprop ro.control_privapp_permissions   # if "enforce": the allowlist XML MUST list every privileged permission
adb -s $S reboot
```
**Verify:** `adb -s $S shell dumpsys package <pkg> | grep -E 'codePath|privateFlags|PRIVILEGED|granted=true'`, and check that the app's `codePath` is `/system/priv-app/OrAgent`.
**Rollback:** adb comes up even during a system_server bootloop, so run `adb -s $S root; adb -s $S shell 'mount -o remount,rw /; rm -rf /system/priv-app/OrAgent /system/etc/permissions/privapp-permissions-or.agent.xml'; adb -s $S reboot`. Worst case, reflash the GSI (Step 5.3) or restore super (Step 2).

---

## 7. Known risks and caveats
- **No verified stock firmware.** Only V3.11 (FirmwareDrive, possibly paid) and a V3.05 name were found. "V3.09" turned out to be a Z7 Pro listing. If you obtain a package, check these before trusting it: `MT6761_Android_scatter.txt` (platform `MT6761`, storage `EMMC`, and partition names and offsets that match `ravoz-gpt.txt`), that `preloader_*.bin` and `lk.img` exist, `super.img` and `vbmeta*.img`, and the build string in the folder name or `build.prop` (`RAVOZ.V1_3-64…`, with the RAM/ROM matching your 3+64 unit). **Never flash its preloader** ("Download Only", untick preloader), and never use "Format All".
- TD A13 is from **August 2023**: no security updates, and TrebleDroid no longer develops A13.
- `ro.adb.secure=0`: an unauthenticated root shell over USB.
- Helio A22 GSI reports mention Wi-Fi and data needing a fix, and IMS/VoLTE problems. Calls and SMS are not needed here. Phh Treble Settings has MediaTek toggles if audio misbehaves.
- vndklite has almost no free space until it is resized (Step 5.4). Pushing a large APK can fail with ENOSPC.
- The earlier `fastboot reboot` → "Can't load Android system" incident means: always have stock recovery's factory reset and the mtkclient backup ready.

## 8. Sources
- TrebleDroid releases: https://github.com/TrebleDroid/treble_experimentations/releases (tag ci-20230905)
- TrebleDroid build script: https://github.com/TrebleDroid/treble_experimentations/blob/master/build.sh
- device_phh_treble (A13 commit a5d5328): https://github.com/TrebleDroid/device_phh_treble/tree/a5d5328f43b8d05a67dda385c66d7952a107d0e4
- sas-creator lite-adapter: https://github.com/TrebleDroid/sas-creator/blob/master/lite-adapter.sh
- TrebleDroid FAQ / GSI list: https://github.com/TrebleDroid/treble_experimentations/wiki
- phh on vndklite/secure: https://github.com/phhusson/treble_experimentations/discussions/2494 , https://newreleases.io/project/github/phhusson/treble_experimentations/release/v400.b
- AOSP GSI guide: https://source.android.com/docs/core/tests/vts/gsi
- Google GSI releases: https://developer.android.com/topic/generic-system-image/releases
- AOSP test keys: https://android.googlesource.com/platform/build/+/refs/tags/android-13.0.0_r73/target/product/security/
- mtkclient: https://github.com/bkerler/mtkclient (README, README-INSTALL.md, README-USAGE.md, mtkclient/config/brom_config.py)
- Helio A22 GSI report: https://xdaforums.com/t/discontinued-gsi-13-lineageos-20-trebledroid-based.4517345/page-50
- Firmware listings: https://firmwaredrive.com/index.php?a=downloads&b=folder&id=40365 , https://www.martview-forum.com/threads/ravoz-firmware-download.86480/
