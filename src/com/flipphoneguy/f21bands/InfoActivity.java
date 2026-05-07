package com.flipphoneguy.f21bands;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public final class InfoActivity extends Activity {

    private Button btnInstallImei;
    private TextView installImeiStatus;

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
            @Override public void onClick(View v) { startInstallImei(); }
        });

        bindLink(R.id.btn_alltech,           R.string.info_alltech_url);
        bindLink(R.id.btn_imei_app,          R.string.info_imei_app_url);
        bindLink(R.id.btn_imei_app_release,  R.string.info_imei_app_release_url);
        bindLink(R.id.btn_github,            R.string.info_github_url);
        bindLink(R.id.btn_repo,              R.string.info_repo_url);
    }

    private void startInstallImei() {
        btnInstallImei.setEnabled(false);
        installImeiStatus.setVisibility(View.VISIBLE);
        installImeiStatus.setText(R.string.imei_install_progress_finding);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ImeiAppInstaller.installLatest(InfoActivity.this,
                        new ImeiAppInstaller.ProgressListener() {
                            @Override public void step(final String message) {
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        installImeiStatus.setText(message);
                                    }
                                });
                            }
                        });
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            installImeiStatus.setText(R.string.imei_install_done);
                            btnInstallImei.setEnabled(true);
                            Toast.makeText(InfoActivity.this,
                                R.string.imei_install_done, Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            installImeiStatus.setText(getString(
                                R.string.imei_install_failed, e.getMessage()));
                            btnInstallImei.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
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
