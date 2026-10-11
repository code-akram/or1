# Google Play Services on the RAVOZ (draft, not yet flashed)

Adds real GMS and the Play Store (MindTheGapps 13.0.0 arm64, 20231025_200931) to the phone's current `system_a`.
`/data` is kept: no wipe, the agent, its root (phh-su), Etar and the boot animation stay as they are.

## The images (on archlinux, `~/code/or1/downloads/gms-image/`)

| File | Bytes | sha256 |
|---|---|---|
| `system_a-gms.img` (new) | 2196766720 (0x82f00000) | `4d96aa1f13054f02547415f0ff668f47155ab8377fa0f1f24ebafeb01aa189cb` |
| `system_a-current.img` (rollback: today's system_a) | 1931804672 (0x73250000) | `f14f117749b2d6c69f6486da2321b6316505eaf0ed956af76b918ce3bb06b4d6` |

- `system_a-current.img` is the stock TrebleDroid ci-20230905 image plus the or1 boot/shutdown animations. On
  2026-10-11 all 1843 of its MiB hashed identical to `system_a` read from `super` through the dm table.
- `system_a-gms.img` = that image, filesystem grown from 464169 to 536320 4K blocks (66 MiB free afterwards), plus 26 files from
  MindTheGapps (root:root, dirs 0755, files 0644, `system_file`, `system_lib_file` for `product/lib*`, as
  `plat_file_contexts` says). The ext4 keeps `shared_blocks`; `e2fsck -fn` is clean. Its stale AVB hashtree and
  footer were dropped (top-level `vbmeta_a` has verification disabled, so first-stage mount skips them; the
  phone's `system_a` is mounted without dm-verity today). Built by `build_gms.py` next to it.
- Space: `main_a` allows 5366611968 bytes; product_a + vendor_a + the new system_a use 4907503616, leaving 438 MiB.
- Added: GmsCore, Phonesky (Play Store), GoogleServicesFramework, GoogleFeedback, GooglePartnerSetup,
  AndroidAutoStub (priv-apps); GoogleCalendarSyncAdapter, GoogleContactsSyncAdapter, PrebuiltExchange3Google,
  SpeechServicesByGoogle (apps); GmsOverlay, GmsSettingsProviderOverlay; permissions, sysconfig,
  default-permissions, `gapps.rc`, the dialer support jar, `libjni_latinimegoogle.so` (lib, lib64), the fs-verity cert.
- Left out: SetupWizard and GoogleRestore (the phone is set up; Provision stays, as the installer only deletes it
  when it installs SetupWizard), VelvetTitan (Pixel Tablet only), and what the installer drops on low-space
  devices: Velvet (Google app), MarkupGoogle, TalkBack. All three can be installed later from the Play Store.
  Also `addon.d` (LineageOS OTA hook).

## Flash (on the Mac)

Never run `adb` or `fastboot` without `-s`: the OnePlus (`d41317c3`) is on the same Mac.

```sh
mkdir -p ~/ravoz-gms && cd ~/ravoz-gms
# One ssh stream to the Mac runs at ~1 MB/s (130 ms RTT, some loss), so the image is zstd-compressed and split
# into 8 parts on archlinux (downloads/gms-image/parts/) and pulled in parallel: ~2-3 min instead of ~26.
for i in 0 1 2 3 4 5 6 7; do scp -q archlinux:code/or1/downloads/gms-image/parts/gms.part.0$i . & done; wait
cat gms.part.0* | zstd -d -o system_a-gms.img && rm gms.part.0*
# Rollback image only if needed, the same way (split it first on archlinux).
shasum -a 256 system_a-gms.img       # 4d96aa1f13054f02547415f0ff668f47155ab8377fa0f1f24ebafeb01aa189cb
shasum -a 256 system_a-current.img   # f14f117749b2d6c69f6486da2321b6316505eaf0ed956af76b918ce3bb06b4d6

# fastbootd. `adb reboot fastboot` makes the phone cycle (logo, off) before fastbootd shows; give it a minute.
adb -s RAV1022V1000265 reboot fastboot
#   if it doesn't come up: Power + Vol Up, choose Fastboot (LK), then
#   fastboot -s RAV1022V1000265 reboot fastboot
fastboot devices -l                                         # expect RAV1022V1000265; never flash without -s
fastboot -s RAV1022V1000265 getvar is-userspace             # yes (fastbootd, not LK)
fastboot -s RAV1022V1000265 getvar current-slot             # a
fastboot -s RAV1022V1000265 getvar snapshot-update-status   # none (else: snapshot-update cancel)
fastboot -s RAV1022V1000265 getvar partition-size:system_a  # 0x73250000 (today's size)

fastboot -s RAV1022V1000265 flash system_a system_a-gms.img  # fastbootd resizes system_a to fit
fastboot -s RAV1022V1000265 getvar partition-size:system_a  # 0x82F00000
fastboot -s RAV1022V1000265 reboot                          # no -w: keep /data
```

First boot takes longer (new packages are scanned and optimised). If it hangs at the logo for over 5 minutes,
hold Power ~15 s; if it still won't boot, roll back.

## Verify (from archlinux)

```sh
a(){ ~/.local/share/android/platform-tools/adb -P 5038 -s RAV1022V1000265 "$@"; }
a shell getprop ro.com.google.gmsversion                  # 13_202304 (set by gapps.rc)
a shell pm list packages -s | grep -E 'com.google.android.gms$|com.android.vending|com.google.android.gsf$'
a shell dumpsys package com.google.android.gms | grep -E 'codePath|versionName|privateFlags' | head
a shell 'logcat -d | grep -iE "privapp|not in privapp-permissions allowlist"'   # expect nothing for Google packages
a shell 'logcat -d -b crash | tail -20'                                         # no GMS crash loop
a shell sha256sum /product/media/bootanimation.zip      # b400704e... (boot animation unchanged)
```

`ro.control_privapp_permissions` is `log` on this phone, so a missing allowlist entry would only log; even so,
every privileged platform permission requested by the six added priv-apps is in the shipped allowlists (checked
against this GSI's framework-res). Also check the agent still works (root via phh-su, HOME is still
`com.or1.agent`: `a shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN`).
Google's default runtime permission grants (`default-permissions-google.xml`) are normally applied on first boot or a
fingerprint change; this flash keeps the fingerprint, so they may not be applied (not verified). If GMS lacks e.g.
location, grant it with `pm grant`.

**Result (2026-10-11):** boot, packages and HOME as above, no privapp warnings. The default grants were indeed not
applied (GMS had location, accounts, contacts and notifications denied), so they were granted by hand:

```sh
for pkg in com.google.android.gms com.google.android.gsf com.android.vending; do
  for perm in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION READ_CONTACTS WRITE_CONTACTS \
      GET_ACCOUNTS READ_PHONE_STATE POST_NOTIFICATIONS READ_CALENDAR WRITE_CALENDAR; do
    a shell pm grant $pkg android.permission.$perm 2>/dev/null
  done
done
a shell pm grant com.google.android.gm android.permission.POST_NOTIFICATIONS        # and for YouTube
```

`content query` on gservices is denied to the shell; `su 0 sqlite3 …/gservices.db` (phh-su) reads the ID without
restarting adbd. After sign-in GMS updated itself from the Play Store (22.21 to 26.37). One harmless crash seen:
`com.google.android.gms.ui` asking Settings to enable Bluetooth (`REQUEST_ENABLE`), denied.

## Register the device, then sign in

The build is uncertified (test-keys, userdebug), so Google blocks sign-in until its GSF ID is registered.

1. Let GMS check in: Wi-Fi on, wait a few minutes (opening the Play Store once speeds it up:
   `a shell monkey -p com.android.vending 1`).
2. Read the GSF Android ID (already decimal in this database):
   ```sh
   a shell content query --uri content://com.google.android.gsf.gservices --where "name='android_id'"
   # if the shell is denied: adb root costs an adbd restart (tell any other session first), then
   a root && a shell "sqlite3 /data/data/com.google.android.gsf/databases/gservices.db \"select value from main where name='android_id'\"" ; a unroot
   ```
   A hex ID (e.g. from an ID app) converts with `python3 -c 'print(int("<hex>", 16))'`.
3. On https://www.google.com/android/uncertified, sign in with the Google account, paste the decimal ID, register.
   It can take from minutes to a few hours to apply.
4. Sign in on the phone: `a shell am start -a android.settings.ADD_ACCOUNT_SETTINGS`, choose Google (the
   touchscreen is unreliable: `scrcpy -s RAV1022V1000265` on the Mac helps). If it still says the device isn't
   certified, wait and reboot; don't clear GSF's data (that makes a new ID, which would need registering again).

## Rollback

Same fastbootd steps, flashing today's image (it shrinks system_a back):

```sh
fastboot -s RAV1022V1000265 flash system_a system_a-current.img   # f14f1177...
fastboot -s RAV1022V1000265 reboot
```

PackageManager drops the vanished Google packages and their data on the next boot. A full `super` backup is in
`backup/20261009-202452/by-name/super.bin`.
