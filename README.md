# F21 Bands Swap

One-tap on-device band swap (US ↔ stock) for the rooted DuoQin F21 Pro (pre-v3, MT6761). No fastboot, no recovery, no host PC, no per-power-cycle limit. Backs up the live region before flashing the target region, then sysrq-reboots the device with the new bands live.

## Compatibility

**EXCLUSIVELY for rooted DuoQin F21 prior to v3.** v3 has a red charging port and serial number prefix `F21PMQC25`; running this on v3 will brick the modem. Magisk is required (the app needs root and `su -mm` for the MMC ioctls). If you're not sure which revision you have, do not use this app.

## What it does

- Detects whether the device is currently on US or stock bands by hashing `md1img_a`.
- Streams a backup of the four live partitions (`md1img_a`, `nvcfg`, `nvdata`, `nvram`) into an xz/tar blob in app private storage. No raw partition data lands on disk uncompressed.
- Unlocks the eMMC's power-on write-protect via `mmc_probe` (bundled arm64 helper, sources in [`tools/mmc_probe.c`](tools/mmc_probe.c)).
- `dd`s the four target partitions to `/dev/block/mmcblk0` at the canonical sector offsets, with the loaded blob streamed straight into the orchestration shell's stdin (no /sdcard staging, no 260 MiB of temp expansion).
- `sysrq-b` reboots the device immediately after the writes, skipping the normal shutdown sync so the mounted ext4 driver doesn't clobber `nvcfg`/`nvdata` with its stale pagecache.


## Credits

The on-device live-flash flow this app ships was reverse-engineered, validated end-to-end, and documented by [alltechdev](https://github.com/alltechdev) in [`docs/live_band_swap_solution.md`](docs/live_band_swap_solution.md). The four primitives that make it work — CMD0 GO_IDLE_STATE to refresh the eMMC's per-power-cycle CMD29 honor quota, CMD6 SWITCH on `EXT_CSD[171]` to flip `US_PWR_WP_EN`, the settle delay between SWITCH and CMD29 to dodge the silent-no-op race, and the `sysrq-b` reboot to skip the ext4 shutdown sync that would otherwise clobber the writes — are alltechdev's findings. This app is the wrapper; the engineering is theirs.

## Install

Download the [latest release](https://github.com/flipphoneguy/f21_bands_swap/releases/latest) from the [releases](https://github.com/flipphoneguy/f21_bands_swap/releases) below.

```sh
adb install -r F21BandsSwap.apk
```

Open the app, accept the root prompt, follow the on-screen flow:

1. Tap **Download** to fetch the target region's blob (~20 MB) into app private storage. Or tap **Pick file** if you already have it.
2. Tap **Swap to <target>**. Confirm the warning.
3. The app backs up your current bands, runs the eMMC unlock dance, flashes the new bands, and sysrq-reboots. Total wall-clock is dominated by the backup compression step: roughly 1–2 minutes on MT6761, mostly spent compressing the 100 MB modem partition through the pure-Java LZMA encoder at PRESET_MIN. The eMMC unlock + dd flash itself is under 10 seconds.
4. After the reboot, re-provision IMEI / BT MAC / WiFi MAC using [mtk-imei-switcheroo-app](https://github.com/flipphoneguy/mtk-imei-switcheroo-app) — the freshly-flashed nvram contains placeholder values, not your phone's identifiers.

## Run from Termux (no APK, no host PC)

If you'd rather skip the APK and run the swap from an on-device Termux shell, [`tools/termux_swap.sh`](tools/termux_swap.sh) is a one-shot wrapper that downloads the target region's blob, `mmc_probe`, and `tools/swap.sh`, stages them via `su`, and invokes the same flash procedure the app uses. From Termux:

```sh
F21_BANDS_RAW=https://raw.githubusercontent.com/flipphoneguy/f21_bands_swap/main \
  curl -fsSL "$F21_BANDS_RAW/tools/termux_swap.sh" | sh -s us
```

Fully written and tested by [alltechdev](https://github/flipphoneguy/alltechdev)

Substitute `stock` for `us` to flash the other direction. The script auto-installs any missing Termux packages (`curl`, `tar`, `xz-utils`) and triggers the Magisk grant prompt up front before the ~21 MiB download so you click Allow once and walk away. Same post-flash step as the APK path: re-provision IMEI / BT MAC / WiFi MAC with [mtk-imei-switcheroo-app](https://github.com/flipphoneguy/mtk-imei-switcheroo-app). Unlike the app, this path does **not** back up your current bands before flashing — if you want a backup, dump the four partitions yourself first (see Recovery).

## Recovery

The pre-flash backup blob lives at `/data/data/com.flipphoneguy.f21bands/files/bands_<region>.tar.xz`. If a flash fails or you want to revert manually, pull it, `tar -xJf`, and `dd` the four `.bin` files to their partitions from a recovery shell. The in-app "swap back" path runs the same flow with the previously-backed-up region as the new target.

## How it actually works

The full reverse-engineering trail, including every wrong turn, is in:

- [`docs/live_band_swap_re.md`](docs/live_band_swap_re.md) — original investigation. Established that naive `dd` silently no-ops on the band-related partitions while Android is running, and that mounted-ext4 pagecache coherency Frankensteins anything that does land. Concluded the wrong thing ("live swap is fundamentally not reachable") for the right reasons.
- [`docs/live_band_swap_solution.md`](docs/live_band_swap_solution.md) — the solution that supersedes the above. CMD0 reinit + USR_WP unlock + CMD29 cascade + sysrq-b. Empirically tested over four consecutive bidirectional swaps with no power-cycle.

## Tooling

- [`tools/mmc_probe.c`](tools/mmc_probe.c) — minimal C helper that sends specific JEDEC eMMC commands the kernel block layer doesn't expose to userspace (CMD0, CMD6, CMD8, CMD28, CMD29, CMD31). Built for arm64 Android API 30+.
- [`tools/mmc_probe`](tools/mmc_probe) — prebuilt arm64 binary; bundled in the APK as `assets/mmc_probe`.
- [`tools/build.sh`](tools/build.sh) — NDK cross-compile wrapper.
- [`tools/swap.sh`](tools/swap.sh) — standalone reproduction of the in-app swap procedure, for use from `adb shell`. Stages partition images on `/sdcard/` rather than streaming via stdin.
- [`tools/termux_swap.sh`](tools/termux_swap.sh) — Termux one-shot wrapper around `tools/swap.sh`. Downloads the target region's blob, `mmc_probe`, and `swap.sh`, stages everything via `su`, then runs the swap. Self-bootstraps missing Termux packages and pre-triggers the Magisk grant so the whole flow is unattended after the first Allow tap.

## Build

```sh
./build.sh
```

Requires `aapt2`, `ecj`, `d8`, `apksigner`, `zip` (e.g. `pkg install aapt2 ecj d8 apksigner zip` in Termux), an `android.jar`, a `framework-res.apk`, and a debug keystore — paths in `build.sh`.

## Legal

Modifying an IMEI is illegal in some jurisdictions. You are responsible for checking your local laws.

## License

See [LICENSE](LICENSE).

# Links

- [alltechdev](https://github.com/alltechdev)
- [mtk-imei-switcheroo-app](https://github.com/flipphoneguy/mtk-imei-switcheroo-app) — companion app for reprovisioning IMEI/BT/WiFi after a swap
- [mtk-imei-switcheroo-app — latest release](https://github.com/flipphoneguy/mtk-imei-switcheroo-app/releases/latest)
