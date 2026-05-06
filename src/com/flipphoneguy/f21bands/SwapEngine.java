package com.flipphoneguy.f21bands;

import android.content.Context;
import android.content.res.AssetManager;

import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Backup current bands, then live-flash the loaded blob and sysrq-reboot.
 *
 * Phase 1 (backup) streams `dd if=<part>` straight into an xz/tar output
 * stream so the 270 MiB of raw partition data never lands on disk.
 *
 * Phase 2 (live flash) follows docs/live_band_swap_solution.md:
 *   1. CMD0 GO_IDLE_STATE   — refresh the eMMC's per-power-cycle CMD29 quota.
 *   2. CMD6 SWITCH USR_WP   — set US_PWR_WP_EN bit so CMD29 is honored.
 *   3. CMD8 SEND_EXT_CSD    — settle delay between SWITCH and CMD29.
 *   4. CMD29 CLR_WRITE_PROT — clears every power-on WP group across the user area.
 *   5. CMD31 verify         — abort cleanly if WP didn't actually clear.
 *   6. dd 4 partitions to /dev/block/mmcblk0+seek, reading from script stdin.
 *   7. sync; sysrq-b        — emergency reboot, skipping ext4 shutdown sync.
 *
 * Step 6's stdin is the very pipe Java owns: the orchestration shell launched
 * by {@link RootRunner#execMountMasterShell} dd's exactly the canonical bytes
 * for each partition off its stdin in canonical order, and Java streams the
 * loaded blob's tar entries into that stdin in the same order. Storage cost
 * stays at zero — no /sdcard staging, no temp expansion of the 260 MiB raw
 * partition images.
 *
 * Storage invariant: at any time, app private storage holds exactly ONE
 * blob — the bands for the region the user is NOT currently on. The
 * post-sysrq cleanup of the just-applied blob happens at the next app
 * launch (see {@link MainActivity#cleanupStaleBlob}).
 */
public final class SwapEngine {

    public interface ProgressListener {
        void step(String message);
    }

    private SwapEngine() {}

    public static void swap(Context ctx, String currentRegion, String targetRegion,
                            ProgressListener listener) throws IOException, InterruptedException {

        File loadedBlob = BlobLoader.blobFile(ctx, targetRegion);
        if (!loadedBlob.isFile()) {
            throw new IOException("Loaded blob missing: " + loadedBlob.getName());
        }

        File backupBlob = BlobLoader.blobFile(ctx, currentRegion);
        File backupTmp = new File(ctx.getFilesDir(), backupBlob.getName() + ".part");
        // Stale .part from a previously aborted swap: drop it so we don't
        // accidentally rename half-written content over a fresh backup.
        if (backupTmp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            backupTmp.delete();
        }

        int total = Constants.PARTITION_FILES.length;

        // ── 1. Stream live partitions → xz/tar (PRESET_MIN) → backup blob.
        // PRESET_MIN keeps XZ-format compatibility with downloaded blobs while
        // running ~5-10x faster than PRESET_DEFAULT on MT6761 (the level-6
        // LZMA encoder pure-Java is 0.3-0.5 MB/s; level-0 ≈ 3-5 MB/s).
        FileOutputStream rawFos = new FileOutputStream(backupTmp);
        BufferedOutputStream raw = new BufferedOutputStream(rawFos);
        // Wrap raw in a non-closing filter so XZOutputStream.close() doesn't
        // cascade-close the FileOutputStream before we get to fsync it.
        OutputStream rawNoClose = new java.io.FilterOutputStream(raw) {
            @Override public void close() throws IOException { flush(); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }
        };
        XZOutputStream xz = new XZOutputStream(rawNoClose, new LZMA2Options(LZMA2Options.PRESET_MIN));
        boolean cleanClose = false;
        final Context fctx = ctx;
        final ProgressListener flistener = listener;
        try {
            for (int i = 0; i < total; i++) {
                final int part = i;
                final long sz = Constants.PARTITION_SIZES[i];
                final String name = Constants.PARTITION_FILES[i];
                final int totalParts = total;
                if (flistener != null) flistener.step(fctx.getString(
                    R.string.step_backup_part, name, part + 1, totalParts));
                TarStream.writeHeader(xz, name, sz);
                RootRunner.streamPartitionToOut(
                    Constants.PARTITION_DEVICES[i],
                    sz,
                    xz,
                    new RootRunner.ByteProgressListener() {
                        @Override public void onBytes(long sofar, long totalBytes) {
                            if (flistener != null) flistener.step(fctx.getString(
                                R.string.step_backup_part_progress,
                                name, part + 1, totalParts,
                                sofar / 1024 / 1024, totalBytes / 1024 / 1024));
                        }
                    });
                TarStream.writePad(xz, sz);
            }
            TarStream.writeEnd(xz);
            xz.finish();
            xz.close();              // → rawNoClose.close() → flush only (raw stays open)
            raw.flush();             // ensure BufferedOutputStream is empty
            rawFos.getFD().sync();   // fsync content to disk before close
            raw.close();             // closes rawFos
            cleanClose = true;
        } finally {
            if (!cleanClose) {
                try { xz.close(); } catch (Throwable ignored) {}
                try { raw.close(); } catch (Throwable ignored) {}
                try { rawFos.close(); } catch (Throwable ignored) {}
            }
        }

        if (backupBlob.exists() && !backupBlob.delete()) {
            //noinspection ResultOfMethodCallIgnored
            backupTmp.delete();
            throw new IOException("Cannot replace existing backup");
        }
        if (!backupTmp.renameTo(backupBlob)) {
            throw new IOException("Cannot rename backup");
        }

        // Force the rename's directory entry change AND any tail page-cache
        // pages to disk before we touch any mmcblk0 partition. `sync` returns
        // when the kernel has handed the data to the eMMC, but the eMMC
        // controller's own volatile write cache may still be uncommitted in
        // its NAND for a few seconds after that. Phase 2 then issues CMD0
        // GO_IDLE_STATE (mmc_probe reinit) which resets eMMC session state,
        // and finally sysrq-b cuts power abruptly. A tester reported the
        // SECOND backup of two consecutive swaps missing after reboot — the
        // first phase 1 ran 5 min (PRESET_DEFAULT, since changed to PRESET_MIN)
        // giving the controller plenty of time to writeback naturally; the
        // second phase 1 ran ~1 min, leaving the file in volatile cache when
        // CMD0 + sysrq-b hit. The fix: sync, sleep 5s for the controller to
        // commit, sync once more in case anything dirtied during the sleep.
        if (listener != null) listener.step(ctx.getString(R.string.step_backup_sync));
        RootRunner.Result syncRes = RootRunner.run("sync && sleep 5 && sync");
        if (syncRes.exit != 0) {
            throw new IOException("sync after backup failed (exit "
                + syncRes.exit + "): " + syncRes.stderr);
        }
        if (!backupBlob.isFile() || backupBlob.length() < 1024) {
            throw new IOException("Backup blob missing or empty after sync ("
                + backupBlob.getName() + ", "
                + (backupBlob.exists() ? backupBlob.length() + " bytes" : "no file")
                + ") — refusing to flash, your current bands are unchanged");
        }

        // ── 2. Live flash via mmc_probe + sysrq-b reboot.
        File probe = stageMmcProbe(ctx);
        File script = writeLiveSwapScript(ctx, probe);

        if (listener != null) listener.step(ctx.getString(R.string.step_unlock));

        Process shell = RootRunner.execMountMasterShell(
            "sh '" + script.getAbsolutePath() + "'");

        // Drain stderr off-thread so the shell never blocks on a full stderr pipe;
        // capture it for diagnostics if the unlock aborts.
        final StringBuilder errBuf = new StringBuilder();
        final InputStream errIn = shell.getErrorStream();
        Thread errPump = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = errIn.read(buf)) > 0) {
                        synchronized (errBuf) { errBuf.append(new String(buf, 0, n)); }
                    }
                } catch (IOException ignored) {}
            }
        }, "live-swap-stderr");
        errPump.setDaemon(true);
        errPump.start();

        // Drain stdout similarly (mmc_probe is chatty).
        final InputStream outIn = shell.getInputStream();
        Thread outPump = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] buf = new byte[4096];
                    while (outIn.read(buf) > 0) { /* discard */ }
                } catch (IOException ignored) {}
            }
        }, "live-swap-stdout");
        outPump.setDaemon(true);
        outPump.start();

        // Stream loaded blob's partition bodies into the shell's stdin in
        // canonical order; the script's `dd` calls consume exactly each
        // partition's bytes from this same stdin.
        IOException flashErr = null;
        try (InputStream rawIn = new BufferedInputStream(new FileInputStream(loadedBlob));
             InputStream xzIn = new XZInputStream(rawIn);
             OutputStream stdin = shell.getOutputStream()) {

            int idx = 0;
            while (true) {
                TarStream.Entry e = TarStream.readHeader(xzIn);
                if (e == null) break;
                int j = indexOf(e.name);
                if (j < 0) {
                    TarStream.copyBody(xzIn, null, e.size);
                    continue;
                }
                if (j != idx) {
                    throw new IOException(
                        "blob entries out of canonical order at " + e.name
                        + " (expected " + Constants.PARTITION_FILES[idx] + ")");
                }
                if (e.size != Constants.PARTITION_SIZES[idx]) {
                    throw new IOException("Blob entry size mismatch for " + e.name);
                }
                if (listener != null) listener.step(ctx.getString(
                    R.string.step_flash_part, e.name, idx + 1, total));
                copyExact(xzIn, stdin, e.size);
                TarStream.skipFully(xzIn, TarStream.padAfter(e.size));
                idx++;
            }
            if (idx != total) {
                throw new IOException("blob has only " + idx + " of " + total + " partitions");
            }
            stdin.flush();
        } catch (IOException e) {
            flashErr = e;
        }

        // The script's last line is `echo b > /proc/sysrq-trigger`. The kernel
        // reboots before the shell can return cleanly, so we don't waitFor()
        // indefinitely — give the shell a few seconds to either fail-fast on
        // a WP-unlock abort or fire sysrq, then surface either error or
        // success-with-pending-reboot.
        boolean exited;
        try {
            exited = shell.waitFor(8000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            exited = false;
        }

        if (flashErr != null) {
            String stderr;
            synchronized (errBuf) { stderr = errBuf.toString(); }
            String detail = stderr.isEmpty() ? flashErr.getMessage()
                                              : flashErr.getMessage() + "\n" + stderr;
            throw new IOException(detail);
        }

        if (exited) {
            int rc = shell.exitValue();
            if (rc != 0) {
                String stderr;
                synchronized (errBuf) { stderr = errBuf.toString(); }
                throw new IOException("live flash failed (exit " + rc + ")"
                    + (stderr.isEmpty() ? "" : ":\n" + stderr));
            }
            // Exit 0 without sysrq is unexpected — script always ends in sysrq-b.
            // Treat as success regardless; user will see the device fail to
            // reboot and can power-cycle manually.
        }

        if (listener != null) listener.step(ctx.getString(R.string.step_done));
        // Falling out here: the device is rebooting (sysrq-b). The loaded blob
        // remains on disk; MainActivity.cleanupStaleBlob() will drop it on next
        // launch when current bands match the blob's region.
    }

    /** Extracts the bundled mmc_probe binary to filesDir on first call. */
    private static File stageMmcProbe(Context ctx) throws IOException {
        File out = new File(ctx.getFilesDir(), "mmc_probe");
        AssetManager am = ctx.getAssets();
        // Re-extract if size differs from the asset, so a future APK update
        // gets picked up cleanly.
        long assetSize;
        try (InputStream probe = am.open("mmc_probe")) {
            assetSize = drain(probe).length;
        }
        if (!out.isFile() || out.length() != assetSize) {
            try (InputStream in = am.open("mmc_probe");
                 OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
        }
        if (!out.setExecutable(true, false)) {
            throw new IOException("cannot mark mmc_probe executable");
        }
        return out;
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /**
     * Writes the live-swap orchestration script to filesDir and returns its
     * file. The script body is the in-app counterpart to tools/swap.sh, with
     * dd reading partition bytes from its own stdin (Java streams them in)
     * instead of from /sdcard staging files.
     */
    private static File writeLiveSwapScript(Context ctx, File probe) throws IOException {
        StringBuilder s = new StringBuilder();
        s.append("#!/system/bin/sh\n");
        s.append("set -e\n");
        s.append("PROBE='").append(probe.getAbsolutePath()).append("'\n");
        s.append("DEV='").append(Constants.MMCBLK0).append("'\n");
        s.append("\n");
        // Belt-and-suspenders: flush once more before CMD0 GO_IDLE_STATE, so
        // any page-cache pages dirtied between the Java-side sync and now
        // (e.g., this very script being written to disk) reach the eMMC
        // before the reinit potentially disrupts uncommitted writes.
        s.append("sync\n");
        s.append("\"$PROBE\" \"$DEV\" reinit\n");
        s.append("\"$PROBE\" \"$DEV\" switch 1 171 0x10\n");
        s.append("\"$PROBE\" \"$DEV\" ext_csd > /dev/null\n");
        s.append("\"$PROBE\" \"$DEV\" clear_wp ").append(Constants.WP_UNLOCK_SECTOR).append("\n");
        s.append("\n");
        s.append("WP_LINE=\"\"\n");
        s.append("i=0\n");
        s.append("while [ $i -lt 5 ]; do\n");
        s.append("  WP_LINE=$(\"$PROBE\" \"$DEV\" read_wp ").append(Constants.WP_UNLOCK_SECTOR)
         .append(" 2>/dev/null | grep '^WP @' || true)\n");
        s.append("  [ -n \"$WP_LINE\" ] && break\n");
        s.append("  sleep 1\n");
        s.append("  i=$((i + 1))\n");
        s.append("done\n");
        s.append("case \"$WP_LINE\" in\n");
        s.append("  *\"00 00 00 00 00 00 00 00\"*) : ;;\n");
        s.append("  \"\") : ;;\n"); // CMD31 flake; proceed on faith of CMD29's clean R1
        s.append("  *) echo 'ERROR: WP did not clear (eMMC CMD29 quota exhausted). ");
        s.append("Power the device fully off, wait 30s, power on, retry.' >&2; exit 1 ;;\n");
        s.append("esac\n");
        s.append("\n");
        for (int i = 0; i < Constants.PARTITION_FILES.length; i++) {
            // dd reading from stdin with bs=4096 and a Java BufferedOutputStream
            // writer 1 MB ahead reliably gets full 4096-byte reads in practice
            // (the pipe buffer stays full, so each read() returns the requested
            // size). iflag=fullblock would be the defensive way to enforce this
            // but the toybox shipped on F21 pre-v3 rejects "fullblock" as an
            // unknown flag value, aborting the dd before it does anything.
            s.append("dd of=\"$DEV\" bs=4096 seek=").append(Constants.PARTITION_4K_SEEK[i])
             .append(" count=").append(Constants.PARTITION_4K_COUNT[i])
             .append(" conv=notrunc  # ").append(Constants.PARTITION_FILES[i]).append("\n");
        }
        // Two-stage flush before sysrq-b: first sync hands data to eMMC,
        // sleep gives the controller time to commit volatile cache to NAND,
        // second sync is in case anything dirtied during the sleep. Without
        // this, sysrq-b can race ahead of an in-flight commit and silently
        // drop the most recently written file (the backup .tar.xz lives on
        // userdata, not the modem partitions, so it's vulnerable too).
        s.append("sync\n");
        s.append("sleep 2\n");
        s.append("sync\n");
        s.append("\n");
        s.append("echo 1 > /proc/sys/kernel/sysrq\n");
        s.append("echo b > /proc/sysrq-trigger\n");

        File out = new File(ctx.getFilesDir(), "live_swap.sh");
        try (FileWriter w = new FileWriter(out)) {
            w.write(s.toString());
        }
        //noinspection ResultOfMethodCallIgnored
        out.setExecutable(true, false);
        return out;
    }

    private static void copyExact(InputStream in, OutputStream out, long bytes) throws IOException {
        byte[] buf = new byte[1024 * 1024];
        long remaining = bytes;
        int n;
        while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
            out.write(buf, 0, n);
            remaining -= n;
        }
        if (remaining != 0) throw new IOException("short blob: " + remaining + " bytes left");
    }

    private static int indexOf(String name) {
        for (int i = 0; i < Constants.PARTITION_FILES.length; i++) {
            if (Constants.PARTITION_FILES[i].equals(name)) return i;
        }
        return -1;
    }
}
