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
 * One-tap installer for the companion mtk-imei-switcheroo-app. Hits the
 * GitHub releases API to find the latest .apk asset, downloads it, then
 * shells out via su to copy the file to /data/local/tmp, run pm install,
 * and remove the staging file.
 *
 * Network and root are both required; the app already declares INTERNET
 * and assumes Magisk for everything else.
 */
public final class ImeiAppInstaller {

    private static final String API_URL =
        "https://api.github.com/repos/flipphoneguy/mtk-imei-switcheroo-app/releases/latest";
    private static final String STAGE_PATH = "/data/local/tmp/imei_switch.apk";

    public interface ProgressListener {
        void step(String message);
    }

    private ImeiAppInstaller() {}

    public static void installLatest(Context ctx, ProgressListener pl)
            throws IOException, InterruptedException {
        if (pl != null) pl.step(ctx.getString(R.string.imei_install_progress_finding));
        String apkUrl = fetchApkUrl();

        if (pl != null) pl.step(ctx.getString(R.string.imei_install_progress_downloading));
        File local = new File(ctx.getFilesDir(), "imei_switch.apk");
        downloadTo(apkUrl, local);

        if (pl != null) pl.step(ctx.getString(R.string.imei_install_progress_installing));
        try {
            // Stage the APK in /data/local/tmp (readable by package manager),
            // run pm install, then remove the staging file regardless of
            // whether the install reported Success.
            String cmd =
                "cp '" + local.getAbsolutePath() + "' '" + STAGE_PATH + "' && "
                + "chmod 644 '" + STAGE_PATH + "' && "
                + "pm install -r '" + STAGE_PATH + "'; "
                + "rc=$?; rm -f '" + STAGE_PATH + "'; exit $rc";
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

    private static String fetchApkUrl() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API_URL).openConnection();
        c.setRequestProperty("User-Agent", "F21Bands/1.0");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("GitHub API HTTP " + code);
            String body;
            try (InputStream in = c.getInputStream()) {
                body = drain(in);
            }
            // Naive scrape: first browser_download_url ending in .apk.
            // Adequate because the mtk-imei-switcheroo-app release ships
            // exactly one APK asset per release.
            Pattern p = Pattern.compile(
                "\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"");
            Matcher m = p.matcher(body);
            if (!m.find()) throw new IOException("No .apk asset in latest release");
            return m.group(1);
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
