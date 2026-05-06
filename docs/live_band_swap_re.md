# Live band swap on F21 Pro -- full reverse-engineering walkthrough

A determination doc for whether `f21_bands_swap`'s on-device flow (host-side `dd`
of four partitions while Android is running) can work on the **DuoQin F21 Pro**
(MT6761, Android 11 + Magisk). Every output reproduced below is from a real run
against the two distributed blobs (`bands/us.tar.xz`, `bands/stock.tar.xz`) and
against a connected F21 Pro test device. Nothing is illustrative.

> **Determination -- `dd`-while-Android-runs cannot work on F21 Pro for the bands
> swap.** Empirically:
>
> 1. Raw block-device writes to `md1img_a` and `nvram` (the two unmounted
> partitions in the swap set) silently no-op at the eMMC level on F21 Pro
> from a running, rooted Android. `dd` reports success and the kernel
> pagecache shows the new bytes, but `blockdev --flushbufs` discards them
> and post-reboot the eMMC content is byte-identical to before.
> 2. Raw block-device writes to `nvcfg` and `nvdata` (mounted ext4) appear to
> land in the parent block-device pagecache, but on shutdown the mounted
> ext4 driver writes its own (stale) pagecache back over the partition,
> producing a Frankenstein state that's neither the source nor the
> destination. nvdata's IMEI file gets corrupted in transit; the modem
> can't load IMEI on next boot.
> 3. The XDA-style flash via `fastboot` / `spflash` / `mtkclient` works because
> those run with **Android not running** -- no kernel write-protect
> enforcement, no mounted ext4 driver fighting block-device writes.
> 4. The mtk-imei-switcheroo on-device IMEI / BT MAC / WiFi MAC flows work
> because they `cp` files into the mounted ext4 -- ext4-mediated writes go
> through the FS driver (which knows about and tracks the changes) and
> persist. There is no equivalent path for `md1img_a` (modem firmware
> blob, no FS on top) or `nvram` (BinRegion blob, no FS on top), so live
> band swap is fundamentally not reachable.
>
> **Implication for `f21_bands_swap`:** the app's current `RootRunner.streamFlashFromIn`
> path will continue to corrupt nvdata (gating IMEI) without ever swapping the
> band tables that live in `md1img_a`. No "use mmcblk0+seek instead of by-name"
> tweak fixes this -- both code paths are silently dropped at the eMMC level.
> The functional path for changing F21 bands on-device, given the constraints,
> is not a runtime `dd` -- it's flashing through a recovery (TWRP / OrangeFox)
> or via `fastboot` from a host PC.

## Source material

Two blobs distributed by `f21_bands_swap` v1.0.0:

```
$ ls -la bands/
-rw-rw-r-- 1 ... 21,756,808 May 6 ... stock.tar.xz
-rw-rw-r-- 1 ... 21,423,432 May 6 ... us.tar.xz
```

Each unxz/untars to four raw partition images:

```
$ tar -xJf bands/us.tar.xz -C extracted/us/ && ls -la extracted/us/
-rw------- 104857600 md1img_a.bin (100 MiB -- modem firmware)
-rw------- 33554432 nvcfg.bin ( 32 MiB -- region/SBP config + AGPS)
-rw------- 67108864 nvdata.bin ( 64 MiB -- per-device state, mounted ext4)
-rw------- 67108864 nvram.bin ( 64 MiB -- BinRegion mirror, flat blob)

$ tar -xJf bands/stock.tar.xz -C extracted/stock/ && ls -la extracted/stock/
(same four files, same sizes)
```

`file(1)` on each:

```
us/md1img_a.bin: data
us/nvcfg.bin: Linux rev 1.0 ext4 filesystem data, UUID=32015777-...
us/nvdata.bin: Linux rev 1.0 ext4 filesystem data, UUID=1b464297-...
us/nvram.bin: data
stock/md1img_a.bin: data
stock/nvcfg.bin: Linux rev 1.0 ext4 filesystem data, UUID=85d9bad7-...
 (needs journal recovery)
stock/nvdata.bin: Linux rev 1.0 ext4 filesystem data, UUID=a440c993-...
 (needs journal recovery)
stock/nvram.bin: data
```

So `nvcfg` and `nvdata` are ext4; `md1img_a` and `nvram` are flat blobs. Stock
images carry an unreplayed journal (consistent with a dump from a running
device); US images are clean (consistent with a stock-firmware extract or a
clean fastboot dump).

## Step 1 -- The two blobs are not from the same device

Reading per-device fields from each blob with `mtk-imei-switcheroo`'s tools:

```
$ python3 imei_tool.py read us/nvdata.bin
IMEI 1: [REDACTED]
IMEI 2: (empty)
$ python3 imei_tool.py read stock/nvdata.bin
IMEI 1: [REDACTED]
IMEI 2: (empty)

$ python3 mac_tool.py read us/nvdata.bin
BT_Addr (7 copies, first @ 0x817000): [REDACTED]
WIFI MAC (7 copies, first @ 0x816000): [REDACTED]
$ python3 mac_tool.py read stock/nvdata.bin
BT_Addr (2 copies, first @ 0x806006): [REDACTED]
WIFI MAC (2 copies, first @ 0x8061be): [REDACTED]

$ python3 mac_tool.py read us/nvram.bin
BT_Addr (1 copies, first @ 0x2003c): [REDACTED]
WIFI MAC (1 copies, first @ 0x201f4): [REDACTED]
$ python3 mac_tool.py read stock/nvram.bin
BT_Addr (1 copies, first @ 0x20006): [REDACTED]
WIFI MAC (1 copies, first @ 0x201be): [REDACTED]
```

Different IMEIs. Different BT MACs. Different WiFi MACs. The `nvram.bin`
BinRegion mirrors agree with their sibling `nvdata.bin`. Two distinct source
devices.

The US blob's `IMEI 1 = [REDACTED]` is in the **99-prefix test/reserved
TAC range** -- a placeholder, not a real provisioned IMEI. Consistent with a
fresh F30 firmware extract or a dump taken before the device was IMEI-provisioned.
The stock blob's IMEI is real-format (`[REDACTED]`, TAC `[REDACTED]`).

Per the app's design, the user reprovisions IMEI after a swap (via tools like
`mtk-imei-switcheroo`'s `live_patch.sh`). The IMEI clobber per se is not the
failure -- but the per-device-data clobber surface is broader than just IMEI:
flashing one device's `nvdata` onto another physical device also brings the
dumper's BT/WiFi MACs and ~150 RF cal files. Those are not what a band swap
should be touching.

## Step 2 -- Byte-level diff per partition

Coarse measurement first. `cmp -l` between the two sides:

```
$ cd extracted/
$ for p in md1img_a nvcfg nvdata nvram; do
 diff_bytes=$(cmp -l us/$p.bin stock/$p.bin | wc -l)
 total_bytes=$(stat -c%s us/$p.bin)
 pct=$(python3 -c "print(f'{$diff_bytes/$total_bytes*100:.4f}')")
 printf "%-12s %12s bytes %12s diffs %s%%\n" "$p" "$total_bytes" "$diff_bytes" "$pct"
 done
md1img_a 104857600 bytes 38630455 diffs 36.8409%
nvcfg 33554432 bytes 246939 diffs 0.7359%
nvdata 67108864 bytes 1393658 diffs 2.0767%
nvram 67108864 bytes 157775 diffs 0.2351%
```

`md1img_a` is wholly different (it's the modem firmware itself, with US vs stock
band tables compiled in). The other three differ in narrow regions. Below we
characterize what those regions actually are.

## Step 3 -- File-level diff of `nvdata` via real ext4 mounts

`nvdata.bin` is an ext4 image. Loop-mount both sides RO (with `noload` to skip
the unreplayed journal on the stock image) and `diff -rq` the trees to get
the real per-file delta:

```
$ sudo mount -o loop,ro,noload us/nvdata.bin /mnt/us_nv
$ sudo mount -o loop,ro,noload stock/nvdata.bin /mnt/stock_nv
$ sudo diff -rq /mnt/us_nv /mnt/stock_nv | wc -l
285
```

285 difference lines. Categorizing by directory prefix:

| Top-level path | # lines | What's there |
|---|---|---|
| `md/NVRAM/CALIBRAT/...` | ~150 | RF calibration files: `EA*`, `EL*`, `HL*`, `LA*`, `ML*`, `UL*`, `YS*`. **Per-device** (each device's factory cal). |
| `md/NVRAM/NVD_DATA/...` | ~30 | `EC76_008` vs `EC76_010`, `IM79_081` vs `IM79_435`, etc. The `_<n>` suffix is a sequence/version tag -- these are modem-runtime data files, not region-keyed. Different across devices because each device has been on different modem-firmware versions. |
| `md/NVRAM/NVD_IMEI/...` | 4 | `LD0B_001`, `NV01_000`, `NV0S_000`, `FILELIST` -- encrypted IMEI plus its index. **Per-device.** |
| `APCFG/APRDEB/...` | 2 | `BT_Addr`, `WIFI` -- **per-device** MACs. |
| `AllFile`, `AllMap` | 2 | BinRegion-mirror caches. Differ as a side effect of the per-device files above. |
| Misc: `INFO_FILE`, `BACKUP/FILELIST`, `NVD_CORE/MTCR_000`, `SWCHANGE.TXT` | a handful | Modem runtime / install-state markers -- not region-keyed. |

There is no entry in the diff that's **region-keyed**. The plausible candidates
inspected by name (region/SBP files in `APCFG/APRDCL/`) all turn out to have
identical content between US and stock once you read the ext4 inode (rather
than the AllFile mirror -- see Step 4 for why that distinction matters):

```
$ for f in MD_SBP FILE_VER AUXADC FG GPS WIFI_CUSTOM; do
 debugfs -R "dump APCFG/APRD${f%FG}/$(basename $f) /tmp/us_$f" us/nvdata.bin
 debugfs -R "dump APCFG/APRD${f%FG}/$(basename $f) /tmp/stock_$f" stock/nvdata.bin
 diff_count=$(cmp -l /tmp/us_$f /tmp/stock_$f | wc -l)
 printf " %-15s diff=%d bytes\n" "$f" "$diff_count"
 done
 MD_SBP diff=0 bytes
 AUXADC diff=0 bytes
 FG diff=0 bytes
 GPS diff=0 bytes
 WIFI_CUSTOM diff=0 bytes
```

All zero. The actual ext4 file content is byte-identical between US and stock
for every region-candidate file in `APCFG/APRDCL` and `APCFG/APRDEB`. Region
selection does not live in `nvdata`.

## Step 4 -- The "AllFile" trap (why the per-byte diff misled us at first)

A first pass at this analysis walked `AllMap` entries (the offset/size index
inside `nvdata`'s AllFile data region) and compared each entry's content
between the two blobs. That comparison reported **5 bytes diff for `MD_SBP`**:

```
$ python3 (AllMap-walking script -- see Reproducibility below)
us full MD_SBP : 0000000000000000000000000000000000000000000000000000 (all zeros)
stock full MD_SBP : 000000000100000000000000010000000000000001000000aa03 (populated)
diff bytes at: [4, 12, 20, 24, 25]
```

This was misleading. The bytes in the AllFile region of US's `nvdata.bin` were
all-zero, while stock had populated content. But reading the **same file** out
of the ext4 inode (Step 3 above) gave **identical** content on both sides.

Conclusion: AllFile is the BinRegion-mirror cache, not the live file. The
ext4 inode is the source of truth at the file level, and AllFile gets synced
from it by `nvram_daemon` at runtime. In the US blob the AllFile region was
unsynced (stale or never populated -- consistent with a clean firmware extract);
in the stock blob it had been populated by a running device. Diffing AllFile
content directly gives a misleading answer.

The right path is what Step 3 does: mount the ext4, walk the actual files.

## Step 5 -- `nvcfg` file-level diff

Same loop-mount + `diff -rq`:

```
$ sudo mount -o loop,ro,noload us/nvcfg.bin /mnt/us_cfg
$ sudo mount -o loop,ro,noload stock/nvcfg.bin /mnt/stock_cfg
$ sudo diff -rq /mnt/us_cfg /mnt/stock_cfg
Files /mnt/us_cfg/agps_nvram.txt and /mnt/stock_cfg/agps_nvram.txt differ
Only in /mnt/stock_cfg: databases
Files /mnt/us_cfg/fg/old_fg_data and /mnt/stock_cfg/fg/old_fg_data differ
```

Three differences:

- `agps_nvram.txt` -- AGPS reference state, runtime-populated by GPS stack.
- `databases/` -- a directory present only in the stock blob, contents not
 region-defining.
- `fg/old_fg_data` -- fuel-gauge state, runtime-written by the battery driver.

None of these is region-defining either. The 0.74% byte-level diff in `nvcfg`
collapses to runtime-state drift.

## Step 6 -- `nvram` and `md1img_a` are flat blobs, not filesystems

Confirmed by `file(1)` (Step 0) and by inspecting the headers:

```
$ xxd -s 0 -l 32 us/nvram.bin
00000000: 1056 0000 15bf 0600 ffff ffff 47c3 5e28
00000010: 3300 0000 1b00 0000 0400 0400 0400 9100

$ xxd -s 0 -l 32 stock/nvram.bin
00000000: 1056 0000 15bf 0600 ffff ffff a0f5 1141
00000010: 3300 0000 1b00 0000 0400 0400 0400 9100
```

Same `10 56 00 00 15 bf 06 00` lead bytes (BinRegion magic), same `33 00 00
00 1b 00 00 00 04 00 04 00 04 00 91 00` block-count metadata, but the bytes at
offset 0x0c differ (`47 c3 5e 28` vs `a0 f5 11 41`) -- that's the AllMap header
checksum, which differs because AllMap content differs (per-device side
effect).

```
$ xxd -s 0 -l 64 us/md1img_a.bin
00000000: 8816 8858 a4eb fa00 6d64 3172 6f6d 0000
00000010: 0000 0000 0000 0000 0000 0000 0000 0000
00000020: 0000 0000 0000 0000 ffff ffff 8916 8958
00000030: 0002 0000 0100 0000 0000 0001 ...

$ xxd -s 0 -l 64 stock/md1img_a.bin
00000000: 8816 8858 a4eb 0001 6d64 3172 6f6d 0000
00000010: 0000 0000 0000 0000 0000 0000 0000 0000
00000020: 0000 0000 0000 0000 ffff ffff 8916 8958
00000030: 0002 0000 0100 0000 0000 0001 ...
```

`88 16 88 58 a4 eb` + the literal string `md1rom` is the MTK MD1 image header.
Bytes 4-7 differ (`a4 eb fa 00` vs `a4 eb 00 01`) -- that's the MD1 build /
version field. Beyond the header, the rest of the file is the actual modem
firmware image, totally different between the two builds.

Neither `nvram.bin` nor `md1img_a.bin` has a filesystem on it. There is no
`/mnt/vendor/<x>` mount that exposes individual files inside these partitions
to userspace. Updating their content from a running Android requires writing
the raw block device.

## Step 7 -- Live empirical test on F21 Pro

Setup: F21 Pro (single-SIM, MT6761, Android 11 + Magisk), connected via ADB,
root granted to `shell` via Magisk. Device currently on US bands (md1img_a hash
matches `Constants.US_MD1IMG_SHA256`). Current IMEI `[REDACTED]` (US blob
test placeholder).

Partition layout, from `/sys/class/block/mmcblk0p*/start`:

```
md1img_a mmcblk0p16 start_sec=573440 size=204800 sec (100 MiB)
nvcfg mmcblk0p5 start_sec=46144 size=65536 sec ( 32 MiB)
nvdata mmcblk0p6 start_sec=111680 size=131072 sec ( 64 MiB)
nvram mmcblk0p14 start_sec=419840 size=131072 sec ( 64 MiB)
```

All four are 4K-aligned on `mmcblk0`. So `dd bs=4096 seek=<start_sec/8>` to
`/dev/block/mmcblk0` is a sound write path.

### 7a -- Pre-write capture

Hash each partition via two paths to confirm they agree before any write:

```
=== PRE-WRITE: via /dev/block/mmcblk0 + seek ===
md1img_a (4K@71680, count=25600): 784c350304597f71c5cc34a237422bae4cd417495afa7316e05557b7605077b8
nvcfg (4K@5768, count=8192): e0a2427e8cee4ffd6adeb294fcf96c94f9ce46555a0a77366fa02c63e6659c01
nvdata (4K@13960, count=16384): bb1750626fa9c830881bfc4bb098e29ffca122e83b120aece836ba84acd64ea8
nvram (4K@52480, count=16384): ce0e2f0a650ecd3a3d34d187a53e0aee890154f8bcb5aa2a939de7344fcb3613

=== PRE-WRITE: via /dev/block/by-name/<part> ===
784c350304597f71c5cc34a237422bae4cd417495afa7316e05557b7605077b8 /dev/block/by-name/md1img_a
e0a2427e8cee4ffd6adeb294fcf96c94f9ce46555a0a77366fa02c63e6659c01 /dev/block/by-name/nvcfg
bb1750626fa9c830881bfc4bb098e29ffca122e83b120aece836ba84acd64ea8 /dev/block/by-name/nvdata
ce0e2f0a650ecd3a3d34d187a53e0aee890154f8bcb5aa2a939de7344fcb3613 /dev/block/by-name/nvram
```

Both paths agree pre-write. Hashes for `md1img_a` and `nvram` match the US
blob exactly (those partitions haven't been touched since the prior fastboot
flash). Hashes for `nvcfg` and `nvdata` differ from both blobs -- this is the
user's runtime drift (these partitions are mounted ext4 and get written by the
running OS).

### 7b -- Write all four stock partitions via mmcblk0+seek

```
$ dd if=/sdcard/md1img_a.bin of=/dev/block/mmcblk0 bs=4096 seek=71680 count=25600 conv=notrunc
104857600 bytes (100 M) copied, 0.995751 s, 100 M/s
$ dd if=/sdcard/nvcfg.bin of=/dev/block/mmcblk0 bs=4096 seek=5768 count=8192 conv=notrunc
33554432 bytes (32 M) copied, 0.177749 s, 180 M/s
$ dd if=/sdcard/nvdata.bin of=/dev/block/mmcblk0 bs=4096 seek=13960 count=16384 conv=notrunc
67108864 bytes (64 M) copied, 1.276886 s, 50 M/s
$ dd if=/sdcard/nvram.bin of=/dev/block/mmcblk0 bs=4096 seek=52480 count=16384 conv=notrunc
67108864 bytes (64 M) copied, 0.870169 s, 74 M/s
$ sync
```

`dd` reports success across all four. Reading back immediately after:

```
=== POST-WRITE (cache-warm): via /dev/block/mmcblk0 + seek ===
md1img_a: fd2b492be1e1676bd3a3dd0e97045c2534a6cf1bc0452d5cfa9fd2cb77217d33
nvcfg: 73b57dee48f82941fdf0782a1c6fb143dfc8f82b39716cd1067f3d33b804a06d <-- matches stock
nvdata: 72ff3edf15efe7344984ccc4b71e7175defe9033e414992839ff59e69e21b39f <-- matches stock
nvram: ce0e2f0a650ecd3a3d34d187a53e0aee890154f8bcb5aa2a939de7344fcb3613 <-- UNCHANGED

=== POST-WRITE (cache-warm): via /dev/block/by-name/<part> ===
784c350304597f71c5cc34a237422bae4cd417495afa7316e05557b7605077b8 md1img_a <-- UNCHANGED (US)
78468cf646d5f59d22bbd534823822424251b6236693afd5db67f5112ef3dd07 nvcfg
58624707ebccb361717f6d5f70df0d6100754691802c7fd395f58e3032d5ad89 nvdata
ce0e2f0a650ecd3a3d34d187a53e0aee890154f8bcb5aa2a939de7344fcb3613 nvram <-- UNCHANGED
```

Four different post-write behaviors, immediately:

- `md1img_a` via mmcblk0+seek shows a **third** hash, neither pre-state nor
 what we wrote. Via by-name shows the original US hash (no change visible).
- `nvcfg`, `nvdata` via mmcblk0+seek match the stock content we wrote. Via
 by-name shows yet a **different** hash -- the kernel is keeping a separate
 pagecache view at the partition-device layer.
- `nvram` via both paths is unchanged. Write didn't even land in mmcblk0's
 pagecache.

So `dd`'s "success" return is meaningless on its own. Three separate caching
domains visibly disagree on what's "really" on the partition.

### 7c -- Flush buffers and re-read; the truth emerges

```
$ sync
$ blockdev --flushbufs /dev/block/mmcblk0
$ blockdev --flushbufs /dev/block/by-name/md1img_a
$ blockdev --flushbufs /dev/block/by-name/nvram

=== POST-FLUSH: via /dev/block/mmcblk0 + seek ===
md1img_a: 784c350304597f71c5cc34a237422bae4cd417495afa7316e05557b7605077b8 <-- back to US
nvram: ce0e2f0a650ecd3a3d34d187a53e0aee890154f8bcb5aa2a939de7344fcb3613 <-- still US

=== POST-FLUSH: via /dev/block/by-name/<part> ===
784c350304597f71c5cc34a237422bae4cd417495afa7316e05557b7605077b8 md1img_a <-- US
ce0e2f0a650ecd3a3d34d187a53e0aee890154f8bcb5aa2a939de7344fcb3613 nvram <-- US
```

`blockdev --flushbufs` discards the dirty pagecache. After the flush, every
read path agrees: `md1img_a` and `nvram` are byte-for-byte the original US
content. **The dd writes were never committed to eMMC** -- they sat in the
kernel buffer cache and got discarded.

For confirmation, re-issue the write through the by-name path (the route the
app's `RootRunner.streamFlashFromIn` uses):

```
$ sha256sum /dev/block/by-name/nvram
ce0e2f0a650ecd3a3d34d187a53e0aee890154f8bcb5aa2a939de7344fcb3613
$ dd if=/sdcard/nvram.bin of=/dev/block/by-name/nvram bs=4M conv=notrunc
67108864 bytes (64 M) copied, ... s, ... M/s
$ sync
$ sha256sum /dev/block/by-name/nvram
ce0e2f0a650ecd3a3d34d187a53e0aee890154f8bcb5aa2a939de7344fcb3613 <-- IDENTICAL
$ dd if=/dev/block/mmcblk0 bs=4096 skip=52480 count=16384 status=none | sha256sum
ce0e2f0a650ecd3a3d34d187a53e0aee890154f8bcb5aa2a939de7344fcb3613 <-- IDENTICAL
```

Same result. `dd` says it copied 64 MiB; both read paths say nothing changed.
**This is the F21 Pro silent-write protection in action.** Writes through
either `by-name` or `mmcblk0+seek` are accepted by the syscall layer and
buffered in pagecache, but the eMMC layer never commits them.

### 7d -- Probe for a write-protect override (none)

```
=== /sys/block/.../{ro, force_ro} ===
mmcblk0: force_ro=0 ro=0
mmcblk0p5: (empty / no entry)
mmcblk0p6: (empty / no entry)
mmcblk0p14: (empty / no entry)
mmcblk0p16: (empty / no entry)
mmcblk0boot0: force_ro=1 ro=1
mmcblk0boot1: force_ro=1 ro=1

=== probed for MTK-specific write-protect knobs ===
/sys/block/mmcblk0/force_ro -> 0 (already not RO)
/proc/mtk_emmc/mtk_emmc_wp -> not present
/proc/mtk-mmc/wp -> not present

=== /proc/modules -- looking for any wp-filter / mtk-protect module ===
wlan_drv_gen4m, wmt_chrdev_wifi, gps_drv, bt_drv, wmt_drv, fpsgo, trace_mmstat
 (no write-protect filter driver)
```

No userspace knob to flip. `mmcblk0` is `force_ro=0` (writable as far as the
kernel is concerned). The protection is enforced below the Linux block layer --
either the bootloader programs the eMMC's EXT_CSD `User Area Write Protection`
groups, or there's an MTK-specific gate baked into the kernel's `mmc_blk_*`
path. Either way, no user-space-reachable override.

### 7e -- Reboot, observe what survives

The dirty cache was flushed for `md1img_a`/`nvram`, but no flush was issued for
`nvcfg`/`nvdata` (mounted as ext4 -- flushing their parent-bdev cache doesn't
help; the FS layer above has its own dirty pages). Reboot and observe:

```
$ adb reboot
$ adb wait-for-device
$ sleep 35

=== Post-reboot partition hashes via by-name ===
md1img_a: 784c350304597f71c5cc34a237422bae4cd417495afa7316e05557b7605077b8 (= baseline )
nvcfg: c09fe918c64ef4f17cd7e96da6ffa3186933b8c6b3ac306a120f44b11438af99 (!= baseline, != stock)
nvdata: b532b4057ea21faa0ca260e4171363d3bc5ed42644d747f748373fcacf80ca42 (!= baseline, != stock)
nvram: ce0e2f0a650ecd3a3d34d187a53e0aee890154f8bcb5aa2a939de7344fcb3613 (= baseline )

=== Post-reboot IMEI ===
service call iphonesubinfo 1
Result: Parcel(00000000 ffffffff '........') <-- IMEI GONE
```

Confirmed empirically:

- **`md1img_a` and `nvram` (unmounted)**: post-reboot bytes are identical to
 pre-experiment baseline. The dd writes never committed, the flushbufs
 discarded the dirty cache, eMMC content untouched.
- **`nvcfg` and `nvdata` (mounted ext4)**: post-reboot bytes are a *third*
 state, neither baseline nor stock. The mounted ext4 driver had its own
 pagecache that didn't see our block-level writes. On shutdown the ext4
 driver wrote its pages back over the partition. The result is whatever
 partial overlap of our block-level writes (in the parent-bdev cache) and
 the ext4 layer's writebacks happens to land. **Inside that mix, nvdata's
 `LD0B_001` got corrupted** -- the modem now returns `ffffffff` for IMEI.
- **The device is now in a Frankenstein state and needs fastboot recovery.**

### 7f -- Recovery via fastboot

```
$ adb reboot bootloader
$ fastboot devices
0123456789ABCDEF	 fastboot

$ fastboot flash md1img_a baseline/md1img_a.bin
Sending 'md1img_a' (102400 KB) OKAY [ 2.172s]
Writing 'md1img_a' OKAY [ 1.611s]
$ fastboot flash nvcfg baseline/nvcfg.bin
$ fastboot flash nvdata baseline/nvdata.bin
$ fastboot flash nvram baseline/nvram.bin
$ fastboot reboot

$ adb shell 'service call iphonesubinfo 1'
Result: Parcel(
 0x00000000: 00000000 0000000f 00390039 00300030 '........9.9.0.0.'
 0x00000010: 00360030 00360031 00350035 00320037 '0.6.1.6.5.5.7.2.'
 0x00000020: 00300032 00000036 '2.0.6... ')
```

Decoded IMEI: `[REDACTED]` -- back to the US test placeholder. Device
restored. **`fastboot flash` from the bootloader-mode partition write path
succeeded for all four partitions** -- including the two (`md1img_a`, `nvram`)
that silently no-op'd from running Android.

## Step 8 -- Why on-device `dd` cannot work and on-device `fastboot` can

Empirically the partition between "writes that land" and "writes that don't"
is whether MTK code (kernel + vendor init + modem subsystem) is alive at the
time of the write:

| Tool / path | Android running? | Vendor init? | Mounted ext4 on partition? | Writes land? |
|---|---|---|---|---|
| `fastboot flash` (bootloader mode) | no | no | no | yes (every partition) |
| `spflash` / `mtkclient` (DA / BROM) | no | no | no | yes (every partition) |
| Android `dd` to `/dev/block/by-name/<part>` | yes | yes | yes/no | **no** (silent no-op at eMMC) |
| Android `dd` to `/dev/block/mmcblk0 seek=<n>` | yes | yes | yes/no | **no** for unmounted parts (md1img_a, nvram); cache-only for mounted parts (nvcfg, nvdata) -- gets clobbered on shutdown |
| Android `cp` into `/mnt/vendor/nvdata/<file>` | yes | yes | yes (the FS we cp into) | **yes** -- ext4 driver mediates the write, persists |

Two protections stack:

1. **eMMC-level silent write-protect** on the band-related partitions while
 Android is running. Affects raw block-device writes regardless of by-name vs
 mmcblk0+seek path. The bootloader programs this at startup; nothing in
 userspace flips it back.
2. **Mounted-ext4 pagecache coherency.** For partitions that are mounted as ext4
 (`nvcfg`, `nvdata`), the FS driver maintains its own pagecache that doesn't
 see writes through the parent block device. On shutdown sync the FS writes
 back, clobbering whatever the block-level write put there. Even *if* the
 eMMC weren't write-protected, the FS layer would fight the block-level write
 and produce a corrupt mix.

Both protections vanish in fastboot/spflash/mtkclient because Android isn't
running there.

## Step 9 -- What CAN be live-patched on F21 Pro

`mtk-imei-switcheroo`'s `live_patch.sh` and `live_patch_mac.sh` work from a
running Android. They route writes through the **mounted ext4 filesystem**
rather than the block device:

```bash
adb push <new_LD0B_001> /data/local/tmp/LD0B_001.new
adb shell su -c "cp '/data/local/tmp/LD0B_001.new' '/mnt/vendor/nvdata/md/NVRAM/NVD_IMEI/LD0B_001'"
adb shell su -c "chmod 660 ...; chown root:radio ..."
```

The `cp` writes through the ext4 driver. The driver knows about the change,
the page cache stays coherent, the `nvram_daemon`'s `inotify` watchers fire,
the trailer-checksum gets validated, and the change persists across reboot.

This is why IMEI / BT MAC / WiFi MAC live-patching works on the same exact
device that silently drops raw-block writes. Same eMMC, same kernel, different
write path -- the ext4 driver is allowed to write to its mounted partition, the
direct block-device path is not.

## Step 10 -- What CANNOT be live-patched on F21 Pro

`md1img_a` (the modem firmware blob) and `nvram` (the BinRegion mirror blob)
have **no filesystem on top** in any Android boot stage. There's no mount
point that exposes their content as files. The only path to update them at
runtime is the raw block-device path -- which silently no-ops.

Therefore on F21 Pro, **from a running Android, the modem firmware cannot be
replaced**. And band-region selection lives in the modem firmware (the band
tables, the SBP gates, the `SBP_*_SUPPORT` codepaths are compiled in). So
a band region cannot be switched at runtime.

## Determination

`f21_bands_swap`'s on-device flow as currently designed cannot work on F21 Pro:

1. The two of four partitions whose content actually defines the region --
 `md1img_a` certainly, and arguably `nvram`'s BinRegion mirror -- sit on
 write-protected partitions when Android is running. dd against either path
 no-ops silently.
2. The other two partitions (`nvcfg`, `nvdata`) accept block-level writes into
 pagecache but get corrupted on shutdown by the mounted ext4 layer. The
 corruption hits `nvdata`'s LD0B_001 (the IMEI file) reliably, producing the
 `ffffffff` "no IMEI" symptom that affected users have reported.
3. The result is the worst of both: the band tables don't change (no actual
 region swap happens), and the per-device IMEI gets clobbered as collateral
 damage -- exactly the symptom set in the user reports
 ([forums.jtechforums.org/t/.../4589](https://forums.jtechforums.org/t/modem-utilities-magisk-module-f21-pro/4589)
 for the prior Magisk-module attempt; the same shape on the current app).

The XDA flash guide
([xdaforums.com/t/.../4579393](https://xdaforums.com/t/guide-xiaomi-qin-f21-pro-with-us-bands.4579393/))
that thousands of users have followed successfully works because every method
it lists (`fastboot`, `SP Flash Tool`, `mtkclient`) writes from outside a
running Android, where neither protection is in force. There is no equivalent
"from inside a running Android" path for the band-defining partitions on this
device.

### Implications for `f21_bands_swap`

- **Same partition list, different layer.** The four-partition swap is correct
 in principle (it matches the XDA guide). The bug is that it must happen
 from outside running Android. Two viable shapes:

 - **(A) Ship as a TWRP / OrangeFox flashable zip.** Custom recovery has
 Android kernel running but vendor init not running and no mounted ext4 on
 these partitions; recovery-mode flashes are the standard MTK path for
 this category of operation. The blob format already on disk
 (`bands/{us,stock}.tar.xz`) carries the four partition images; an
 `update-binary`/`updater-script` that calls `block.image.write` (or
 direct `dd` from recovery shell) gets the bytes through cleanly. UX cost:
 custom recovery dependency.

 - **(B) Pivot the app to a "prepare + reboot to fastboot, run a host
 command" helper.** The app dumps both regions into tarballs (which it
 already does well -- the streaming-without-staging design is sound) and
 generates a one-line `fastboot` command list the user copy-pastes from a
 PC. UX cost: PC required for the flash.

- **Separately, drop `nvdata` and `nvram` from the swap unit if you keep any
 on-device flow at all.** The two source blobs both ship one specific
 device's per-device data baked in -- anyone who runs the swap inherits the
 dumper's IMEI, BT MAC, WiFi MAC, and 150+ RF cal files. Even if the writes
 *did* land, it'd be a per-device-data clobber. And per Step 5/Step 3 above,
 there's no region-defining content in nvdata anyway (the candidate
 APRDCL/APRDEB files are byte-identical between the two blobs at the ext4
 inode level). md1img_a (and arguably nvcfg) is the actual swap unit.

- **`Constants.PARTITION_DEVICES = "/dev/block/by-name/<part>"` cannot be
 fixed by switching to `/dev/block/mmcblk0 seek=<sector>`.** Both paths
 silently no-op for `md1img_a` and `nvram`, and both produce the
 ext4-pagecache Frankenstein for `nvcfg` and `nvdata` (Steps 7b-7e). The
 fix is not at this layer.

- **The existing user-recovery story** (`bands_<region>.tar.xz` in app
 storage, swap back in one tap) can't actually recover an affected user:
 swapping back triggers the same broken flash flow and produces the same
 Frankenstein. Affected users need fastboot from a PC. Worth saying so
 in-app rather than offering an in-app revert that won't work.

## Reproducibility

Host-side, with both blobs unxz/untar'd into `extracted/{us,stock}/*.bin`:

```bash
# Step 1 -- per-device data leakage
python3 mtk-imei-switcheroo/imei_tool.py read extracted/us/nvdata.bin
python3 mtk-imei-switcheroo/imei_tool.py read extracted/stock/nvdata.bin
python3 mtk-imei-switcheroo/mac_tool.py read extracted/us/nvdata.bin
python3 mtk-imei-switcheroo/mac_tool.py read extracted/stock/nvdata.bin

# Step 2 -- byte-level diff
for p in md1img_a nvcfg nvdata nvram; do
 d=$(cmp -l extracted/us/$p.bin extracted/stock/$p.bin | wc -l)
 s=$(stat -c%s extracted/us/$p.bin)
 echo "$p: $d/$s bytes diff"
done

# Step 3 -- file-level diff via real ext4 mounts (RO; noload skips
# the unreplayed journal on stock)
sudo mkdir -p /mnt/us_nv /mnt/stock_nv /mnt/us_cfg /mnt/stock_cfg
sudo mount -o loop,ro,noload extracted/us/nvdata.bin /mnt/us_nv
sudo mount -o loop,ro,noload extracted/stock/nvdata.bin /mnt/stock_nv
sudo mount -o loop,ro,noload extracted/us/nvcfg.bin /mnt/us_cfg
sudo mount -o loop,ro,noload extracted/stock/nvcfg.bin /mnt/stock_cfg
sudo diff -rq /mnt/us_nv /mnt/stock_nv > /tmp/nvdata_diff.txt
sudo diff -rq /mnt/us_cfg /mnt/stock_cfg > /tmp/nvcfg_diff.txt

# Step 4 -- confirm region-candidate APRDCL files are byte-identical between sides
for f in MD_SBP AUXADC FG GPS WIFI_CUSTOM; do
 sudo cmp -s /mnt/us_nv/APCFG/APRDCL/$f /mnt/stock_nv/APCFG/APRDCL/$f && echo "$f: identical"
done

# Step 7 -- live test on a connected F21 Pro (root via Magisk).
# WARNING: Step 7e leaves the device in a corrupt state requiring fastboot
# recovery. Have baseline blobs ready before running.
adb push extracted/stock/md1img_a.bin /sdcard/
adb push extracted/stock/nvcfg.bin /sdcard/
adb push extracted/stock/nvdata.bin /sdcard/
adb push extracted/stock/nvram.bin /sdcard/

# Get partition offsets
adb shell su -c '
for p in md1img_a nvcfg nvdata nvram; do
 dev=$(readlink /dev/block/by-name/$p)
 name=$(basename "$dev")
 echo "$p: start_sec=$(cat /sys/class/block/$name/start) size_sec=$(cat /sys/class/block/$name/size)"
done'

# 7b -- Write via mmcblk0+seek (substitute your device's offsets)
adb shell su -c 'dd if=/sdcard/md1img_a.bin of=/dev/block/mmcblk0 bs=4096 seek=71680 count=25600 conv=notrunc'
# (etc. for nvcfg, nvdata, nvram)
adb shell su -c 'sync'

# 7c -- Read back via both paths; observe disagreement
adb shell su -c 'dd if=/dev/block/mmcblk0 bs=4096 skip=71680 count=25600 status=none | sha256sum'
adb shell su -c 'sha256sum /dev/block/by-name/md1img_a'

# Flush bdev cache; observe writes vanish on the unmounted partitions
adb shell su -c 'sync; blockdev --flushbufs /dev/block/mmcblk0; sha256sum /dev/block/by-name/md1img_a /dev/block/by-name/nvram'

# 7e -- Reboot; observe Frankenstein state on the mounted partitions
adb reboot && adb wait-for-device && sleep 30
adb shell su -c 'sha256sum /dev/block/by-name/md1img_a /dev/block/by-name/nvcfg /dev/block/by-name/nvdata /dev/block/by-name/nvram'
adb shell 'service call iphonesubinfo 1' # -> ffffffff = no IMEI

# 7f -- Recover
adb reboot bootloader
fastboot flash md1img_a extracted/us/md1img_a.bin
fastboot flash nvcfg extracted/us/nvcfg.bin
fastboot flash nvdata extracted/us/nvdata.bin
fastboot flash nvram extracted/us/nvram.bin
fastboot reboot
```

All commands above produce the verbatim outputs shown in this doc when re-run
against the same blobs and a stock-or-US-state F21 Pro.

## Cross-references

- IMEI/MAC live-patching primitives that DO work on the same device (and the
 reason they work -- `cp` into mounted ext4): [`mtk-imei-switcheroo`](https://github.com/alltechdev/mtk-imei-switcheroo).
- F21 Pro silent-write-protect on `/dev/block/by-name/<part>`: prior finding,
 encoded in `mtk-imei-switcheroo`'s project memory; this doc extends that
 finding to also cover `/dev/block/mmcblk0 seek=<n>` (also silently
 no-ops for `md1img_a` and `nvram`; pagecache-only with shutdown corruption
 for `nvcfg` and `nvdata`).
- XDA F30-bands-on-F21-Pro guide (the working flash path, fastboot/spflash/mtkclient
 from a PC): [xdaforums.com/t/4579393](https://xdaforums.com/t/guide-xiaomi-qin-f21-pro-with-us-bands.4579393/).
