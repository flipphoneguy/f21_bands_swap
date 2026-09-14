package com.flipphoneguy.f21bands;

import android.content.Context;
import android.net.Uri;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Fetches (network or picker) and validates band blobs.
 *
 * Both load paths write to a {@code .part} file, run {@link #validate} on
 * it, and only then rename it to the final {@code bands_<region>.tar.xz}.
 * So a file under the final name has always passed validation, and
 * {@link MainActivity#refreshState} can trust its mere existence.
 */
public final class BlobLoader {

    public interface ProgressListener {
        void onProgress(long bytesSoFar, long total);
    }

    /** Index of md1img_a in Constants.PARTITION_FILES — the entry we hash to identify the region. */
    private static final int MD1IMG = 0;

    private BlobLoader() {}

    public static File blobFile(Context ctx, String region) {
        return new File(ctx.getFilesDir(), Constants.blobFileNameForRegion(region));
    }

    private static File partFile(Context ctx, String region) {
        return new File(ctx.getFilesDir(), Constants.blobFileNameForRegion(region) + ".part");
    }

    public static File download(Context ctx, String region, ProgressListener listener) throws IOException {
        File outFile = blobFile(ctx, region);
        File tmp = partFile(ctx, region);
        try {
            fetch(Constants.urlForRegion(region), tmp, listener);
            return validateAndFinalize(tmp, outFile, region);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
    }

    public static File importFromUri(Context ctx, Uri src, String region) throws IOException {
        File outFile = blobFile(ctx, region);
        File tmp = partFile(ctx, region);
        try {
            InputStream raw = ctx.getContentResolver().openInputStream(src);
            if (raw == null) throw new IOException("could not open the selected file");
            try (InputStream in = new BufferedInputStream(raw);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                copy(in, out, null, -1);
            }
            return validateAndFinalize(tmp, outFile, region);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
    }

    private static void fetch(String urlStr, File tmp, ProgressListener listener) throws IOException {
        URL url = new URL(urlStr);
        // GitHub raw uses public CAs, so the default trust manager is fine.
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setRequestProperty("User-Agent", "F21Bands/1.0");
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long total = conn.getContentLength();   // -1 if unknown; the int form exists on minSdk 23
            try (InputStream in = new BufferedInputStream(conn.getInputStream());
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                copy(in, out, listener, total);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out, ProgressListener listener, long total)
            throws IOException {
        byte[] buf = new byte[64 * 1024];
        long sofar = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            sofar += n;
            if (listener != null) listener.onProgress(sofar, total);
        }
    }

    private static File validateAndFinalize(File tmp, File outFile, String expectedRegion) throws IOException {
        String found = validate(tmp);
        if (!expectedRegion.equals(found)) {
            throw new IOException("it contains " + Constants.prettyRegion(found)
                + " bands, but you need " + Constants.prettyRegion(expectedRegion));
        }
        return finalize(tmp, outFile);
    }

    /**
     * Streams the entire blob through xz+tar, checks that the 4 expected
     * files are present at the expected sizes, and hashes md1img_a to
     * identify which region's bands the blob holds.
     *
     * @return {@link Constants#REGION_US} or {@link Constants#REGION_STOCK}
     * @throws IOException naming the first problem found
     */
    public static String validate(File blob) throws IOException {
        if (!blob.isFile() || blob.length() == 0) throw new IOException("file is empty");
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        OutputStream hasher = new OutputStream() {
            @Override public void write(int b) { md.update((byte) b); }
            @Override public void write(byte[] b, int off, int len) { md.update(b, off, len); }
        };
        boolean[] seen = new boolean[Constants.PARTITION_FILES.length];
        try (InputStream raw = new BufferedInputStream(new FileInputStream(blob));
             InputStream xz = new XZInputStream(raw)) {
            while (true) {
                TarStream.Entry e = TarStream.readHeader(xz);
                if (e == null) break;
                int idx = indexOf(e.name);
                if (idx < 0) {
                    TarStream.copyBody(xz, null, e.size);
                    continue;
                }
                if (seen[idx]) throw new IOException("archive has two copies of " + e.name);
                if (e.size != Constants.PARTITION_SIZES[idx]) {
                    throw new IOException(e.name + " is " + e.size + " bytes, expected "
                        + Constants.PARTITION_SIZES[idx]);
                }
                TarStream.copyBody(xz, idx == MD1IMG ? hasher : null, e.size);
                seen[idx] = true;
            }
        }
        for (int i = 0; i < seen.length; i++) {
            if (!seen[i]) throw new IOException("archive has no " + Constants.PARTITION_FILES[i]);
        }
        String h = hex(md.digest());
        if (h.equalsIgnoreCase(Constants.US_MD1IMG_SHA256)) return Constants.REGION_US;
        if (h.equalsIgnoreCase(Constants.STOCK_MD1IMG_SHA256)) return Constants.REGION_STOCK;
        throw new IOException(Constants.PARTITION_FILES[MD1IMG] + " matches neither US nor stock bands");
    }

    private static File finalize(File tmp, File outFile) throws IOException {
        if (outFile.exists() && !outFile.delete()) {
            throw new IOException("Cannot replace existing blob");
        }
        if (!tmp.renameTo(outFile)) {
            throw new IOException("Cannot rename .part to final");
        }
        return outFile;
    }

    private static int indexOf(String name) {
        for (int i = 0; i < Constants.PARTITION_FILES.length; i++) {
            if (Constants.PARTITION_FILES[i].equals(name)) return i;
        }
        return -1;
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
