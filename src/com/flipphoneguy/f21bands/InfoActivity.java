package com.flipphoneguy.f21bands;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public final class InfoActivity extends Activity {

    private static final String IMEI_REPO   = "flipphoneguy/mtk-imei-switcheroo-app";
    private static final String SELF_REPO   = "flipphoneguy/f21_bands_swap";
    private static final String IMEI_STAGE  = "imei_switch.apk";
    private static final String SELF_STAGE  = "f21_bands_update.apk";

    private Button btnInstallImei, btnUpdateApp;
    private TextView installImeiStatus, updateAppStatus;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_info);

        TextView usUrl    = findViewById(R.id.info_url_us);
        TextView stockUrl = findViewById(R.id.info_url_stock);
        usUrl.setText(Constants.urlForRegion(Constants.REGION_US));
        stockUrl.setText(Constants.urlForRegion(Constants.REGION_STOCK));

        // Buttons-body uses inline HTML for bold labels.
        TextView buttonsBody = findViewById(R.id.info_buttons_body);
        buttonsBody.setText(Html.fromHtml(
            getString(R.string.info_buttons_body),
            Html.FROM_HTML_MODE_LEGACY));

        btnInstallImei    = findViewById(R.id.btn_install_imei);
        installImeiStatus = findViewById(R.id.install_imei_status);
        btnInstallImei.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                runInstall(btnInstallImei, installImeiStatus,
                    IMEI_REPO, IMEI_STAGE,
                    R.string.imei_install_done, R.string.imei_install_failed);
            }
        });

        btnUpdateApp    = findViewById(R.id.btn_update_app);
        updateAppStatus = findViewById(R.id.update_app_status);
        btnUpdateApp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runSelfUpdate(); }
        });

        bindLink(R.id.btn_alltech,           R.string.info_alltech_url);
        bindLink(R.id.btn_imei_app,          R.string.info_imei_app_url);
        bindLink(R.id.btn_github,            R.string.info_github_url);
        bindLink(R.id.btn_repo,              R.string.info_repo_url);
    }

    private void runInstall(final Button btn, final TextView statusView,
                            final String repo, final String stage,
                            final int doneStringId, final int failedStringId) {
        btn.setEnabled(false);
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(R.string.apk_install_progress_finding);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ApkInstaller.installLatest(InfoActivity.this, repo, stage,
                        new ApkInstaller.ProgressListener() {
                            @Override public void step(final String message) {
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        statusView.setText(message);
                                    }
                                });
                            }
                        });
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            statusView.setText(doneStringId);
                            btn.setEnabled(true);
                            Toast.makeText(InfoActivity.this,
                                doneStringId, Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            statusView.setText(getString(failedStringId, e.getMessage()));
                            btn.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    private void runSelfUpdate() {
        btnUpdateApp.setEnabled(false);
        updateAppStatus.setVisibility(View.VISIBLE);
        updateAppStatus.setText(R.string.update_app_checking);
        final String currentVersion = currentVersionName();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final ApkInstaller.LatestRelease latest =
                        ApkInstaller.checkLatest(SELF_REPO);
                    int cmp = ApkInstaller.compareVersions(latest.tag, currentVersion);
                    if (cmp <= 0) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                updateAppStatus.setText(getString(
                                    R.string.update_app_uptodate, currentVersion));
                                btnUpdateApp.setEnabled(true);
                            }
                        });
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            updateAppStatus.setText(getString(
                                R.string.update_app_newer_found, latest.tag, currentVersion));
                        }
                    });
                    ApkInstaller.installFrom(InfoActivity.this, latest.apkUrl, SELF_STAGE,
                        new ApkInstaller.ProgressListener() {
                            @Override public void step(final String message) {
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        updateAppStatus.setText(message);
                                    }
                                });
                            }
                        });
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            updateAppStatus.setText(R.string.update_app_done);
                            btnUpdateApp.setEnabled(true);
                            Toast.makeText(InfoActivity.this,
                                R.string.update_app_done, Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            updateAppStatus.setText(getString(
                                R.string.update_app_failed, e.getMessage()));
                            btnUpdateApp.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    private String currentVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private void bindLink(int buttonId, final int urlResId) {
        Button b = findViewById(buttonId);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(getString(urlResId)); }
        });
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {}
    }
}
