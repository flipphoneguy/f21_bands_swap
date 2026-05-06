# F21 Bands Swap

> # ⚠️ DO NOT USE!! WILL BRICK MODEM!!
> Test confirmed a big issue with the way the phone writes the partitions. The way it is now, the app will brick the service. Currently lookik into it. i  the meamtime do not use the app!

One-tap modem-band swapping (US / International) for the rooted DuoQin F21 (pre-v3).

## Why

The DuoQin F21 ships with the stock Chinese band table; the Qin F30 ships with US LTE bands. Either side alone is a problem for frequent travelers — the US firmware doesn't carry the international bands you need elsewhere, and the stock firmware doesn't cover US LTE. Israel used to work on the US firmware with 3G, but with 3G being shut down that workaround is gone. Flashing either side manually with `adb` + `dd` works fine if you have a laptop. This app does the same thing on the phone, in one tap. The APK is under 1 MB; the first setup pulls down a ~21 MB blob (compressed firmware) for the side you're not on, and from then on every swap is local.

## How it works

1. Hashes the first 100 MB of `/dev/block/by-name/md1img_a` and matches it against two embedded SHA-256 constants to decide which side you're on (US, stock, or unknown — unknown refuses to swap).
2. When user clicks to swap, streams the four live partitions (`md1img_a`, `nvcfg`, `nvdata`, `nvram`) through xz into a backup blob in app storage. The 270 MB of raw partition data is never staged on disk.
3. Streams the *other* side's blob from app storage straight into `dd` to flash all four partitions.
4. Prompts to reboot.

App storage holds exactly one blob at a time — the side you're not currently on. After a swap, the now-flashed blob is deleted (it's live on the device, trivially re-dumpable next time) and the freshly-created backup takes its place.

## Compatibility

Exclusively for DuoQin F21 prior to v3. v3 has a red charging port and serial-number prefix `F21PMQC25`; running this on a v3 would soft-brick the modem. (app refuses to swap). **Requires root.**

## Using it

1. Install the APK and grant root via Magisk.
2. Open the app — it shows current region and root status.
3. First run only: download the other side's blob (or load it from a file you already have).
4. Tap **Swap to X bands**, confirm, wait 1–3 minutes, reboot.

## Recovery

The previous bands are backed up before anything is flashed. If a flash dies halfway, the backup is at `/data/data/com.flipphoneguy.f21bands/files/bands_<region>.tar.xz` — swap back, or to be safe, pull it from a rooted shell, decompress, and `dd` each `.bin` back to its partition.

## Layout

```
src/com/flipphoneguy/f21bands/
  MainActivity.java       UI: state probe, gating, download, swap flow
  InfoActivity.java       Compat warnings, recovery, links
  Constants.java          Partition table + region SHA-256s + GitHub URLs
  RegionDetector.java     md1img_a hash → US / stock / unknown
  RootRunner.java         Streamed dd via su; never stages on disk
  SwapEngine.java         Backup-then-flash, end-to-end streaming
  BlobLoader.java         HTTPS download + SAF picker + validate
  TarStream.java          Minimal USTAR reader/writer

bands/                    us.tar.xz, stock.tar.xz
res/                      Layouts, strings, theme, adaptive icon
build.sh                  aapt2 → ecj → d8 → apksigner
```

## Legal

Modifying an IMEI is illegal in some jurisdictions. You are responsible for checking your local laws.
