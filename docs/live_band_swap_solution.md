# Live band swap on F21 Pro -- working procedure

Companion to [`live_band_swap_re.md`](live_band_swap_re.md) (the original
investigation that catalogued why naive `dd` doesn't work). This doc is
the **working procedure** that swaps the four partitions on F21 Pro
**from a running, rooted Android** -- no recovery, no fastboot, no host
PC, no per-power-cycle limit.

> **Determination -- solved.** A live US <-> stock band swap on F21 Pro is
> a deterministic, repeatable procedure built on four primitives layered
> on top of `dd`:
>
> 1. **CMD0 GO_IDLE_STATE** to refresh the eMMC's session state. Issued
>    via `MMC_IOC_CMD`, the eMMC drops to IDLE; the Linux MMC layer
>    notices the next operation fails and runs its standard error-
>    recovery path (re-init: CMD0, CMD1, CMD2, CMD3, CMD7, then EXT_CSD
>    re-read). The kernel-driven re-init resets the eMMC's volatile
>    session state including the per-power-cycle CMD29 honor quota.
>    Without this, after ~2 swaps in a power session the eMMC silently
>    no-ops further CMD29s and the procedure stops working until the
>    device is genuinely power-cycled.
>
> 2. **CMD6 SWITCH to set `EXT_CSD[171].US_PWR_WP_EN` (bit 4)**. The
>    bootloader sets up power-on write-protect on the band-related WP
>    groups with `USR_WP=0x00`. With that bit clear, `CMD29
>    (CLR_WRITE_PROT)` is a silent no-op (eMMC accepts it without error
>    and doesn't actually clear the WP). With `US_PWR_WP_EN=1`, `CMD29`
>    actually clears.
>
> 3. **A "settle" command between SWITCH and CMD29.** Empirically a CMD6
>    SWITCH followed immediately by CMD29 in two adjacent ioctls hits a
>    race: the eMMC's USR_WP write hasn't settled by the time CMD29 is
>    issued, so CMD29 returns clean R1 but doesn't actually clear.
>    Inserting any read command (this script uses CMD8 SEND_EXT_CSD)
>    gives the eMMC enough time to register the SWITCH. A
>    `usleep(100ms)` works just as well empirically.
>
> 4. **`sysrq-b` reboot** instead of `reboot` after the writes. The two
>    mounted ext4 partitions (`nvcfg`, `nvdata`) have their own
>    pagecache; on a normal shutdown the ext4 driver writes its (stale)
>    cache back over the partition and Frankensteins our writes.
>    `sysrq-b` triggers an immediate reboot with no shutdown sync -- the
>    bytes we just `dd`'d are what the next boot sees.
>
> Empirically tested on the connected F21 Pro for **four consecutive
> bidirectional swaps with no intervening power-cycle** (US -> stock ->
> US -> stock -> US, all driven by the same packaged
> [`tools/swap.sh`](../tools/swap.sh)): every swap completed end-to-end,
> `md1img_a` and `nvram` post-reboot hashes matched the target blob
> exactly, modem booted, IMEI parcel reflected the target blob's IMEI
> (`[REDACTED]` for stock, `[REDACTED]` for US's test
> placeholder).

## What the original investigation got wrong

The earlier doc, [`live_band_swap_re.md`](live_band_swap_re.md), correctly
established:

- The eMMC has power-on write-protection on the band-related WP groups
  (md1img_a, nvram, partial nvcfg/nvdata).
- Naive `dd` to `/dev/block/by-name/<part>` and to `/dev/block/mmcblk0
  seek=<n>` both silently no-op at the eMMC level.
- Mounted-ext4 partitions get Frankensteined on shutdown by the FS driver
  writing its stale cache back.

Where it concluded wrong: "live swap is fundamentally not reachable". The
missing pieces were (a) the `USR_WP` register gating CMD29, (b) the
SWITCH-then-CMD29 timing race, and (c) the kernel's MMC error-recovery
path which can be deliberately triggered to refresh the eMMC session
state. The full sequence -- CMD0 reinit, SWITCH, settle, CMD29, verify,
dd, sysrq-b -- swaps the partitions cleanly on every run.

## The procedure

### Prerequisites

- F21 Pro pre-v3 (the v3 with the red charging port and serial prefix
  `F21PMQC25` is a different modem; this procedure is not validated
  there).
- Rooted via Magisk; `shell` user has root grant.
- Stock and US blobs available (the four .bin files for each region).
- ~270 MiB free on `/sdcard` to stage one side's blobs at a time.
- The `mmc_probe` helper -- prebuilt arm64 binary at
  [`tools/mmc_probe`](../tools/mmc_probe), source at
  [`tools/mmc_probe.c`](../tools/mmc_probe.c), rebuild via
  [`tools/build.sh`](../tools/build.sh) (needs `ANDROID_HOME` pointing at
  an SDK with an NDK installed).

### Sector offsets (F21 Pro pre-v3, fixed across units)

| partition | block dev    | start sector | size sectors | size MiB | dd seek (4K) | dd count (4K) |
|-----------|--------------|--------------|--------------|---------:|-------------:|--------------:|
| md1img_a  | mmcblk0p16   | 573440       | 204800       | 100      | 71680        | 25600         |
| nvcfg     | mmcblk0p5    | 46144        | 65536        | 32       | 5768         | 8192          |
| nvdata    | mmcblk0p6    | 111680       | 131072       | 64       | 13960        | 16384         |
| nvram     | mmcblk0p14   | 419840       | 131072       | 64       | 52480        | 16384         |

All four are 4K-aligned on `mmcblk0`. `dd bs=4096 seek=<4k-block>` is
sound.

### One-shot run

```bash
# Stage the target side's blobs and the helpers.
adb push <region>/md1img_a.bin /sdcard/
adb push <region>/nvcfg.bin    /sdcard/
adb push <region>/nvdata.bin   /sdcard/
adb push <region>/nvram.bin    /sdcard/
adb push tools/mmc_probe       /data/local/tmp/mmc_probe
adb push tools/swap.sh         /data/local/tmp/swap.sh
adb shell chmod +x /data/local/tmp/mmc_probe /data/local/tmp/swap.sh

# Run. The `-mm` (mount-master) flag is required so `su` lands in the
# SELinux context that can open /dev/block/mmcblk0 for the MMC ioctls.
adb shell su -mm -c 'sh /data/local/tmp/swap.sh'

# Device sysrq-reboots immediately. After ~30s:
adb wait-for-device && sleep 30
adb shell su -c 'sha256sum /dev/block/by-name/md1img_a /dev/block/by-name/nvram'
adb shell 'service call iphonesubinfo 1'
```

`md1img_a` and `nvram` post-reboot hash exactly match the target blob.
`nvcfg` and `nvdata` drift slightly (Android writes runtime state into
them on every boot), but they're derived from the freshly-written
content. The modem reads its NV records (IMEI, MACs, region SBP) from
the new state cleanly -- the IMEI parcel reflects the target blob's
IMEI.

### Per-step breakdown ([`tools/swap.sh`](../tools/swap.sh))

The script is ~70 lines of orchestration over the `mmc_probe` helper plus
four `dd` calls. Annotated:

```sh
# [1] CMD0 GO_IDLE_STATE -- forces the eMMC into IDLE state. The Linux
#     MMC layer's error-recovery path then re-issues a full init
#     sequence, which resets the eMMC's volatile session state including
#     the per-power-cycle CMD29 honor quota. Without this, the procedure
#     would silently no-op after ~2 swaps in a power session.
"$PROBE" "$DEV" reinit

# [2] CMD6 SWITCH to set USR_WP[171] bit 4 (US_PWR_WP_EN).
"$PROBE" "$DEV" switch 1 171 0x10

# [3] Settle: CMD8 SEND_EXT_CSD acts as a delay so the SWITCH actually
#     registers in the eMMC before CMD29 is issued. Without this, CMD29
#     hits a race and returns clean R1 without actually clearing WP.
"$PROBE" "$DEV" ext_csd > /dev/null

# [4] CMD29 CLR_WRITE_PROT. On this F21 Pro eMMC, a single CMD29
#     cascades across every power-on WP group in the user area.
"$PROBE" "$DEV" clear_wp 419840

# [5] CMD31 SEND_WRITE_PROT_TYPE. If WP didn't actually clear, abort
#     before any dd. The script retries the read up to 5x because CMD31
#     is occasionally flaky (returns EILSEQ) right after CMD29.
"$PROBE" "$DEV" read_wp 419840   # expect "00 00 00 00 00 00 00 00"

# [6] dd 4 partitions through /dev/block/mmcblk0+seek (NOT by-name --
#     by-name has an additional silent-write-protect filter on F21 Pro
#     documented in live_band_swap_re.md).
dd if=/sdcard/md1img_a.bin of=/dev/block/mmcblk0 bs=4096 seek=71680  count=25600 conv=notrunc
dd if=/sdcard/nvcfg.bin    of=/dev/block/mmcblk0 bs=4096 seek=5768   count=8192  conv=notrunc
dd if=/sdcard/nvdata.bin   of=/dev/block/mmcblk0 bs=4096 seek=13960  count=16384 conv=notrunc
dd if=/sdcard/nvram.bin    of=/dev/block/mmcblk0 bs=4096 seek=52480  count=16384 conv=notrunc
sync

# [7] sysrq-b reboot: skips ext4 shutdown sync so the FS driver doesn't
#     write its stale pagecache back over our nvcfg/nvdata writes.
echo 1 > /proc/sys/kernel/sysrq
sleep 1
echo b > /proc/sysrq-trigger
```

## Empirical verification (live run on the connected F21 Pro)

Four consecutive swaps using the packaged
[`tools/swap.sh`](../tools/swap.sh), no intervening power-cycle, no
fastboot:

| Round | Direction | md1img_a hash post-reboot | IMEI post-reboot |
|---:|---|---|---|
| 1 | stock -> US     | `784c350304...05077b8` (US matches blob) | `[REDACTED]` (US blob) |
| 2 | US -> stock     | `68ec5dcb9f...d5d9b2ae` (stock matches blob) | `[REDACTED]` (stock blob) |
| 3 | stock -> US     | `784c350304...05077b8` (US matches blob) | `[REDACTED]` (US blob) |
| 4 | US -> stock     | `68ec5dcb9f...d5d9b2ae` (stock matches blob) | `[REDACTED]` (stock blob) |

Every round completed end-to-end with the same script, the same `dd`
seek offsets, and the same `sysrq-b` reboot. The `reinit` step in `[1]`
makes each round independent of the prior session state -- the per-
power-cycle CMD29 quota that constrained the earlier work is no longer
relevant.

## Tooling

[`tools/mmc_probe`](../tools/mmc_probe) is a small C program (source in
[`tools/mmc_probe.c`](../tools/mmc_probe.c)) that uses `MMC_IOC_CMD` to
send specific JEDEC eMMC commands the kernel block layer doesn't expose
to userspace:

- `mmc_probe <dev> reinit` -- CMD0 GO_IDLE_STATE; forces eMMC into idle,
  triggers kernel error-recovery re-init, refreshes session state.
- `mmc_probe <dev> ext_csd` -- CMD8 read; prints WP-relevant fields
  (`USR_WP[171]`, `BOOT_WP[173]`, `HC_WP_GRP_SIZE[221]`, etc.).
- `mmc_probe <dev> read_wp <sector>` -- CMD31 SEND_WRITE_PROT_TYPE;
  prints WP type code (0=none, 1=temp, 2=power-on, 3=permanent) for the
  next 32 WP groups (8 MiB each).
- `mmc_probe <dev> clear_wp <sector>` -- CMD29 CLR_WRITE_PROT; prints
  R1.
- `mmc_probe <dev> set_wp <sector>` -- CMD28 SET_WRITE_PROT; prints R1.
  (Not used by the swap procedure; included for symmetry / debugging.)
- `mmc_probe <dev> switch <access> <index> <value>` -- CMD6 SWITCH.
  `access` is 0 (cmd-set), 1 (set-bits), 2 (clear-bits), 3 (write-byte).
  For the unlock used by this procedure: `switch 1 171 0x10` (set bit 4
  of EXT_CSD[171]).
- `mmc_probe <dev> read1 <sector>` -- CMD17 SINGLE_BLOCK_READ. Note:
  this times out on F21 Pro for normal user-area sectors; the kernel
  MMC subsystem filters ADTC data-transfer ioctls. **Diagnostic only**;
  the working write path goes through `dd` on the block device, not
  through raw `MMC_IOC_CMD` block ops.
- `mmc_probe <dev> writepat <sector> <byte>` -- CMD24 WRITE_BLOCK with
  all bytes set to a pattern. Same filter applies; **diagnostic only**.

## Why each piece is necessary

**Why `su -mm` and not plain `su`.** The default Magisk `su` drops the
spawned process into `magisk:s0` SELinux context. That context can't
open `/dev/block/mmcblk0` directly (gets `EACCES` at `open()`). `su -mm`
(mount-master) runs in the global mount namespace under a different
domain that does have the open permission. Without `-mm`, every MMC
ioctl call fails before it even starts.

**Why CMD0 reinit is needed.** The eMMC's CMD29 (CLR_WRITE_PROT) honor
mechanism has a per-power-cycle quota -- empirically ~2 honors per fresh
session on this F21 Pro eMMC. After ~2 swaps in the same power cycle,
subsequent CMD29s return clean R1 but silently no-op (the eMMC ignores
them). Things confirmed not to refresh: warm reboot, fastboot reboot,
`reboot -p` with manual long-off, kernel-panic auto-reboot, sysfs
`mmcblk` unbind/bind, runtime PM toggles, CMD5 SLEEP/AWAKE, repeat
SWITCH, repeat CMD28+CMD29 in any order. What does refresh: triggering
the kernel's MMC error-recovery path. Sending CMD0 GO_IDLE_STATE via
`MMC_IOC_CMD` is the cleanest way -- the eMMC drops to IDLE, the
kernel's next access fails, the kernel re-issues the full init sequence
(CMD0, CMD1, CMD2, CMD3, CMD7, EXT_CSD re-read), and the eMMC's
volatile session state including the CMD29 quota resets. The smoking
gun is that `BOOT_WP[173]` flips from `0x81` (bootloader-set) to `0x00`
across the reinit -- the eMMC really did re-init, the values aren't
sticky after all when triggered through this path.

**Why `CMD6 SWITCH USR_WP 0x10` is required and `CMD29` alone isn't.**
When the bootloader sets up power-on WP via `CMD28`, `EXT_CSD[171]`
(`USR_WP`) remains `0x00` -- both `US_PWR_WP_EN` and `US_PWR_WP_DIS`
clear. Per JEDEC eMMC 5.0 Sec.6.6.6.4, with `US_PWR_WP_EN=0`, the
user-WP register's clear-mode isn't engaged: `CMD29` returns
`R1=0x00000900` (clean, no error) and silently does nothing. After
`SWITCH` flips bit 4, the eMMC honors `CMD29` and the next query
returns code 0 (no WP).

**Why a settle is needed between SWITCH and CMD29.** The CMD6 SWITCH
uses R1B response (busy-wait), but the kernel's polling on this MTK
platform returns to userspace before the eMMC fully commits the USR_WP
change. Issuing CMD29 in the next ioctl too quickly hits a stale state
where the eMMC sees the old USR_WP value, silently no-ops the CMD29,
and returns clean R1. Inserting any read command (CMD8 SEND_EXT_CSD
here) provides sufficient delay for the SWITCH to register; a
`usleep(100ms)` works just as well empirically.

**Why a single `CMD29` clears everything, not just one group.**
Empirically on this F21 Pro / mt6761 build, sending `CMD29` at any
sector inside the WP'd region clears every power-on WP group across
the user area. We queried `read_wp` on `md1img_a` (sec 573440), `nvcfg`
(46144), `nvdata` (111680), and `nvram` (419840) after a single
`CMD29 419840`; all four returned `00 00 00 00 00 00 00 00`. Whether
this is JEDEC-mandated cascade or vendor-specific, it works in our
favor -- one `CMD29` is enough.

**Why `dd` to `/dev/block/mmcblk0 seek=<n>` and not
`/dev/block/by-name/<part>`.** F21 Pro has a separate silent-write-
protect filter on by-name partition nodes (documented in
`live_band_swap_re.md` and originally in `mtk-imei-switcheroo`'s
project memory). Even with eMMC WP cleared, by-name writes still no-
op. The parent-bdev path with seek doesn't go through that filter.

**Why `sysrq-b` and not `reboot`.** Two of the four swap partitions are
mounted ext4 (`/mnt/vendor/nvcfg` on `mmcblk0p5`, `/mnt/vendor/nvdata`
on `mmcblk0p6`). Their FS-layer pagecache has the *old* state (from
before our block-level writes). On a normal `reboot`, the kernel does
`sync -> unmount -> ext4_writeback`, which writes the stale pagecache
back over the partition. The result is a Frankenstein mix of "what we
just dd'd" and "what the FS layer thought was there", and `nvdata`'s
`LD0B_001` is reliably corrupted in transit -- modem returns
`ffffffff` IMEI on next boot. `sysrq-b` triggers an immediate reboot
via the kernel's emergency-reboot path, with no shutdown sync. The
bytes we wrote are what's on the partition when the next boot reads
them.

**Why all 4 partitions and not just `md1img_a`.** XDA-style flashes do
all four because the modem's NV state mirrors across `nvdata` (live
records) and `nvram` (BinRegion mirror). Writing only `md1img_a` would
leave the modem firmware on one side and the NV state on the other,
producing IMEI verification mismatches and ECC mode. The full-set
write keeps everything internally consistent.

## Limitations and gotchas

1. **The blobs are still cross-device.** `bands/us.tar.xz` and
   `bands/stock.tar.xz` were dumped from two physically distinct devices.
   After a swap, the new IMEI is the dumper's IMEI (or test placeholder),
   not the user's. Per-app design, the user reprovisions IMEI after the
   swap (e.g. with `mtk-imei-switcheroo`'s `live_patch.sh`). BT/WiFi
   MACs inherit the same -- reprovision via `live_patch_mac.sh`.

2. **`USR_WP` change persists across reboot.** Once `US_PWR_WP_EN` is
   set to 1, future `CMD28`s on this device set power-on WP rather than
   temp WP. Not harmful for normal use, but a behavior change worth
   noting. The CMD0 reinit doesn't reset USR_WP itself, only the eMMC's
   internal CMD29 quota.

3. **`sysrq-b` is destructive of dirty pagecache for OTHER mounts too.**
   `/data` has dirty pages too at any given moment. They'll be lost on
   `sysrq-b`. ext4 journals on `/data` will replay clean on next boot
   (this is the journal's purpose), so it's not a corruption risk --
   but any not-yet-fsync'd userspace writes are gone. Tell the user to
   close apps before swapping.

4. **`mmc_probe` requires `su -mm`, not plain `su`.** If you run via
   `adb shell su -c 'sh /script'` without the `-mm`, every MMC ioctl
   call fails with `open: Permission denied`.

5. **`nvcfg` and `nvdata` post-reboot hashes drift slightly.** Android
   writes runtime state into both during boot. The post-reboot hashes
   won't byte-match the source blob exactly. What matters is that the
   modem's NV records are derived from the freshly-written content, not
   from the previous side. Empirically that's the case -- IMEI reads
   back as the new region's IMEI.

6. **Pre-v3 only.** F21 v3 (red charging port, serial prefix
   `F21PMQC25`) has different modem partition layout and a different
   eMMC; the procedure isn't validated there. The sector offsets above
   are F21 pre-v3 specific.

## Implications for `f21_bands_swap`

The app's existing four-partition `dd` flow
(`RootRunner.streamFlashFromIn` in the current source) is the right
primitive in shape -- it just runs into the issues `live_band_swap_re.md`
documented and that this doc resolves. The replacement flow:

1. Bundle the [`tools/mmc_probe`](../tools/mmc_probe) arm64 binary in
   the APK assets.
2. On first run, extract it to
   `/data/data/com.flipphoneguy.f21bands/files/mmc_probe`, `chmod +x`
   it.
3. Replace the existing swap orchestration with: `su -mm -c '<script>'`
   where the script is [`tools/swap.sh`](../tools/swap.sh). The script
   does CMD0 reinit + SWITCH + settle + CMD29 + verify + dd + sysrq-b
   in order.
4. The "save backup blob" step is unchanged -- it already streams
   partition reads (which work fine; reads aren't WP'd).

The blob format and the four-partition swap unit don't need to change.
The `sysrq-b` reboot does mean the user can't get an "are you sure?"
prompt between the dd and the reboot -- once dd runs, the device has to
go down via sysrq immediately, otherwise ext4 writeback eats the
changes. The current "Tap Apply, wait 1-3 minutes, reboot" UX becomes
"Tap Apply, device reboots in ~6 s", which is a UX improvement.

## Reproducibility

```bash
# Host: build the helper (one-time; or just use the prebuilt binary)
tools/build.sh

# Stage the target side (e.g. stock):
adb push extracted/stock/md1img_a.bin extracted/stock/nvcfg.bin \
         extracted/stock/nvdata.bin   extracted/stock/nvram.bin    /sdcard/
adb push tools/mmc_probe /data/local/tmp/mmc_probe
adb push tools/swap.sh   /data/local/tmp/swap.sh
adb shell chmod +x /data/local/tmp/mmc_probe /data/local/tmp/swap.sh

# Run
adb shell su -mm -c 'sh /data/local/tmp/swap.sh'

# Wait for boot and verify
adb wait-for-device && sleep 30
adb shell su -c 'sha256sum /dev/block/by-name/md1img_a'
adb shell 'service call iphonesubinfo 1'
```

Recovery if anything ever goes wrong: stock fastboot of the four
baseline blobs (see the original
[`live_band_swap_re.md`](live_band_swap_re.md) Step 7f for the
commands).

## Cross-references

- [`live_band_swap_re.md`](live_band_swap_re.md) -- the original
  investigation (catalogues why naive `dd` fails; the determination
  there is partly superseded by this doc, but the WP / pagecache /
  by-name-filter details are still accurate).
- [`mtk-imei-switcheroo`](https://github.com/alltechdev/mtk-imei-switcheroo)
  -- IMEI / BT MAC / WiFi MAC reprovisioning tools; useful after a
  swap to set the user's real IMEI/MACs (since the blobs carry the
  dumper's).
- [XDA F30-bands-on-F21-Pro guide](https://xdaforums.com/t/guide-xiaomi-qin-f21-pro-with-us-bands.4579393/)
  -- the host-PC fastboot/spflash/mtkclient procedure that this on-
  device procedure parallels.
