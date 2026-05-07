package com.flipphoneguy.f21bands;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generic "fetch latest GitHub release APK and install via pm install"
 * helper. Used both for the companion mtk-imei-switcheroo-app and for
 * self-updating f21_bands_swap itself.
 *
 * Network and root are both required; the app already declares INTERNET
 * and assumes Magisk for everything else.
 */
public final class ApkInstaller {

    public interface ProgressListener {
        void step(String message);
    }

    public static final class LatestRelease {
        public final String tag;
        public final String apkUrl;
        LatestRelease(String tag, String apkUrl) {
            this.tag = tag;
            this.apkUrl = apkUrl;
        }
    }

    private ApkInstaller() {}

    /**
     * Convenience: check + install in one call. Always installs whatever
     * the API returns — no version comparison. Use {@link #checkLatest}
     * + {@link #installFrom} when you want to gate on a version check.
     */
    public static void installLatest(Context ctx, String repo, String stage,
                                     ProgressListener pl)
            throws IOException, InterruptedException {
        if (pl != null) pl.step(ctx.getString(R.string.apk_install_progress_finding));
        LatestRelease latest = checkLatest(repo);
        installFrom(ctx, latest.apkUrl, stage, pl);
    }

    /**
     * Downloads the APK from {@code apkUrl} to app private storage, then
     * shells out via su to stage it in /data/local/tmp, run pm install -r,
     * and remove the staged file. Caller must have already discovered
     * {@code apkUrl} (e.g. via {@link #checkLatest}).
     */
    public static void installFrom(Context ctx, String apkUrl, String stage,
                                   ProgressListener pl)
            throws IOException, InterruptedException {
        if (pl != null) pl.step(ctx.getString(R.string.apk_install_progress_downloading));
        File local = new File(ctx.getFilesDir(), stage);
        downloadTo(apkUrl, local);

        if (pl != null) pl.step(ctx.getString(R.string.apk_install_progress_installing));
        String stagePath = "/data/local/tmp/" + stage;
        try {
            String cmd =
                "cp '" + local.getAbsolutePath() + "' '" + stagePath + "' && "
                + "chmod 644 '" + stagePath + "' && "
                + "pm install -r '" + stagePath + "'; "
                + "rc=$?; rm -f '" + stagePath + "'; exit $rc";
            RootRunner.Result r = RootRunner.run(cmd);
            if (r.exit != 0) {
                throw new IOException("pm install exit " + r.exit
                    + (r.stderr.isEmpty() ? "" : ": " + r.stderr.trim()));
            }
            if (!r.stdout.contains("Success")) {
                throw new IOException("pm install did not report Success: "
                    + (r.stdout.isEmpty() ? r.stderr.trim() : r.stdout.trim()));
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            local.delete();
        }
    }

    /** Returns the latest release's tag and first .apk asset URL. */
    public static LatestRelease checkLatest(String repo) throws IOException {
        String body = fetchApi("https://api.github.com/repos/" + repo + "/releases/latest");
        Matcher tagM = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        Matcher urlM = Pattern.compile(
            "\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").matcher(body);
        if (!tagM.find()) throw new IOException("No tag_name in latest release");
        if (!urlM.find()) throw new IOException("No .apk asset in latest release");
        return new LatestRelease(tagM.group(1), urlM.group(1));
    }

    /**
     * Compares two dotted version strings (e.g. "v1.0.2" vs "1.0.10").
     * Leading 'v'/'V' is stripped. Returns negative if a&lt;b, 0 if equal,
     * positive if a&gt;b. Non-numeric segments are treated as 0.
     */
    public static int compareVersions(String a, String b) {
        a = stripV(a);
        b = stripV(b);
        String[] ap = a.split("\\.");
        String[] bp = b.split("\\.");
        int n = Math.max(ap.length, bp.length);
        for (int i = 0; i < n; i++) {
            int ai = i < ap.length ? parseIntOr0(ap[i]) : 0;
            int bi = i < bp.length ? parseIntOr0(bp[i]) : 0;
            if (ai != bi) return Integer.compare(ai, bi);
        }
        return 0;
    }

    private static String stripV(String s) {
        if (s == null) return "";
        if (s.startsWith("v") || s.startsWith("V")) return s.substring(1);
        return s;
    }

    private static int parseIntOr0(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static String fetchApi(String apiUrl) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(apiUrl).openConnection();
        c.setRequestProperty("User-Agent", "F21Bands/1.0");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("GitHub API HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return drain(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static void downloadTo(String url, File out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "F21Bands/1.0");
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(60_000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("download HTTP " + code);
            try (InputStream in = c.getInputStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String drain(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
