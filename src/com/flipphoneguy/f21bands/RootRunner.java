package com.flipphoneguy.f21bands;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Thin wrapper around `su -c "..."` (and `su -mm -c "..."` for the live-flash
 * path that needs the mount-master SELinux domain to open /dev/block/mmcblk0
 * for the MMC ioctls used by mmc_probe).
 *
 * Reads (dumps) are streamed: `dd if=<part>` stdout goes straight into a Java
 * OutputStream. Writes (flashes) go through the mount-master orchestration
 * shell launched by {@link #execMountMasterShell(String)} — that shell
 * unlocks the eMMC's power-on write-protect, then dd's all four partition
 * payloads from its own stdin to mmcblk0+seek, then sysrq-b reboots the
 * device. Java just writes the four partition images to the shell's stdin
 * in canonical order.
 */
public final class RootRunner {

    private RootRunner() {}

    public static boolean hasRoot() {
        try {
            Result r = run("id");
            return r.exit == 0 && r.stdout.contains("uid=0");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    public static Result run(String cmd) throws IOException, InterruptedException {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        p.getOutputStream().close();
        String stdout = drain(p.getInputStream());
        String stderr = drain(p.getErrorStream());
        int exit = p.waitFor();
        return new Result(exit, stdout, stderr);
    }

    /** SHA-256 of the first `bytes` bytes of a partition. Used for region detection. */
    public static String sha256Partition(String device, long bytes) throws IOException, InterruptedException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        long blocks = bytes / Constants.DD_BS;
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
            "dd if=" + device + " bs=4M count=" + blocks + " 2>/dev/null"});
        p.getOutputStream().close();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[1024 * 1024];
            long remaining = bytes;
            int n;
            while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                md.update(buf, 0, n);
                remaining -= n;
            }
        }
        int exit = p.waitFor();
        if (exit != 0) throw new IOException("dd hash exited " + exit);
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public interface ByteProgressListener {
        void onBytes(long sofar, long total);
    }

    /** Streams `bytes` bytes from a partition through `dd` into the provided OutputStream. */
    public static void streamPartitionToOut(String device, long bytes, OutputStream out)
            throws IOException, InterruptedException {
        streamPartitionToOut(device, bytes, out, null);
    }

    public static void streamPartitionToOut(String device, long bytes, OutputStream out,
                                            ByteProgressListener progress)
            throws IOException, InterruptedException {
        long blocks = bytes / Constants.DD_BS;
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
            "dd if=" + device + " bs=4M count=" + blocks + " 2>/dev/null"});
        p.getOutputStream().close();
        // Drain stderr so a chatty su/sh can't fill the 64 KB pipe and deadlock dd.
        Thread errPump = startStderrDrain(p, "rr-dump-stderr");
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[256 * 1024];
            long remaining = bytes;
            long lastReported = -1;
            int n;
            while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) > 0) {
                out.write(buf, 0, n);
                remaining -= n;
                if (progress != null) {
                    long sofar = bytes - remaining;
                    // Throttle UI callbacks to every ~1 MB so we don't flood the looper.
                    if (sofar - lastReported >= 1024 * 1024 || remaining == 0) {
                        progress.onBytes(sofar, bytes);
                        lastReported = sofar;
                    }
                }
            }
            if (remaining != 0) throw new IOException("dd dump short read: " + remaining + " left");
        }
        int exit = p.waitFor();
        try { errPump.join(2000); } catch (InterruptedException ignored) {}
        if (exit != 0) throw new IOException("dd dump exited " + exit);
    }

    private static Thread startStderrDrain(final Process p, String name) {
        final InputStream err = p.getErrorStream();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] buf = new byte[4096];
                    while (err.read(buf) > 0) { /* discard */ }
                } catch (IOException ignored) {}
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * Launches `su -mm -c "sh -c <cmd>"`. The mount-master flag is required
     * so the spawned shell lands in an SELinux context that can open
     * /dev/block/mmcblk0 for MMC ioctls. Plain `su` lands in magisk:s0 and
     * gets EACCES at open(). Caller owns the returned Process: write the
     * partition payloads to its stdin (the orchestration script issues
     * `dd of=mmcblk0` calls in canonical partition order, each consuming
     * exactly its partition's bytes from stdin), then close stdin. The
     * shell's last line is `sysrq-b`, so the device reboots before the
     * Process formally exits.
     */
    public static Process execMountMasterShell(String cmd) throws IOException {
        return Runtime.getRuntime().exec(new String[]{"su", "-mm", "-c", cmd});
    }

    private static String drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString();
    }

    public static final class Result {
        public final int exit;
        public final String stdout;
        public final String stderr;
        Result(int exit, String stdout, String stderr) {
            this.exit = exit;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
