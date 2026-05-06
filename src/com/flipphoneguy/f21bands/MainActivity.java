package com.flipphoneguy.f21bands;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

public final class MainActivity extends Activity {

    private static final int REQ_PICK = 100;

    private TextView statusRoot, statusRegion, statusBlob;
    private LinearLayout cardGate, cardAction, cardError;
    private TextView gateExplain, dlStatus, manualUrl, errorMsg;
    private ProgressBar dlProgress;
    private Button btnDownload, btnPick, btnCopyUrl, btnSwap;

    private boolean rooted;
    private String currentRegion = Constants.REGION_UNKNOWN;
    private String otherRegion;
    private boolean blobReady;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);

        statusRoot   = findViewById(R.id.status_root);
        statusRegion = findViewById(R.id.status_region);
        statusBlob   = findViewById(R.id.status_blob);

        cardGate   = findViewById(R.id.card_gate);
        cardAction = findViewById(R.id.card_action);
        cardError  = findViewById(R.id.card_error);

        gateExplain = findViewById(R.id.gate_explain);
        dlStatus    = findViewById(R.id.dl_status);
        manualUrl   = findViewById(R.id.manual_url);
        errorMsg    = findViewById(R.id.error_msg);
        dlProgress  = findViewById(R.id.dl_progress);

        btnDownload = findViewById(R.id.btn_download);
        btnPick     = findViewById(R.id.btn_pick);
        btnCopyUrl  = findViewById(R.id.btn_copy_url);
        btnSwap     = findViewById(R.id.btn_swap);

        findViewById(R.id.btn_info).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, InfoActivity.class));
            }
        });

        btnDownload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startDownload(); }
        });
        btnPick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launchPicker(); }
        });
        btnCopyUrl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("F21 Bands URL", manualUrl.getText().toString()));
                Toast.makeText(MainActivity.this, R.string.copied, Toast.LENGTH_SHORT).show();
            }
        });
        btnSwap.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmSwap(); }
        });

        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check on resume so we pick up freshly-flashed bands or freshly-loaded blobs.
        refreshState();
    }

    /** Background-thread state probe → UI render. */
    private void refreshState() {
        renderLoading();
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean r = RootRunner.hasRoot();
                String detected;
                try {
                    detected = r ? RegionDetector.detect() : Constants.REGION_UNKNOWN;
                } catch (Exception e) {
                    detected = Constants.REGION_UNKNOWN;
                }
                final String region = detected;
                cleanupStaleBlob(region);
                final String other = RegionDetector.otherRegion(region);
                final boolean haveBlob;
                if (other != null) {
                    File f = BlobLoader.blobFile(MainActivity.this, other);
                    haveBlob = f.isFile() && f.length() > 0;
                } else {
                    haveBlob = false;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        rooted = r;
                        currentRegion = region;
                        otherRegion = other;
                        blobReady = haveBlob;
                        render();
                    }
                });
            }
        }).start();
    }

    /**
     * After a successful sysrq-b reboot, the blob we just flashed is still
     * sitting on disk under bands_&lt;currentRegion&gt;.tar.xz. Drop it so the
     * "exactly one blob, for the region you're not on" invariant holds.
     */
    private void cleanupStaleBlob(String region) {
        if (region == null || Constants.REGION_UNKNOWN.equals(region)) return;
        File stale = BlobLoader.blobFile(MainActivity.this, region);
        if (stale.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            stale.delete();
        }
    }

    private void renderLoading() {
        statusRoot.setText("checking root…");
        statusRegion.setText("");
        statusBlob.setText("");
        cardGate.setVisibility(View.GONE);
        cardAction.setVisibility(View.GONE);
        cardError.setVisibility(View.GONE);
    }

    private void render() {
        statusRoot.setText(rooted ? R.string.status_root_yes : R.string.status_root_no);

        if (Constants.REGION_US.equals(currentRegion)) {
            statusRegion.setText(R.string.status_region_us);
        } else if (Constants.REGION_STOCK.equals(currentRegion)) {
            statusRegion.setText(R.string.status_region_stock);
        } else {
            statusRegion.setText(R.string.status_region_unknown);
        }

        if (blobReady && otherRegion != null) {
            statusBlob.setText(getString(R.string.status_blob_have, Constants.prettyRegion(otherRegion)));
        } else {
            statusBlob.setText(R.string.status_blob_missing);
        }

        cardGate.setVisibility(View.GONE);
        cardAction.setVisibility(View.GONE);
        cardError.setVisibility(View.GONE);

        if (!rooted) {
            errorMsg.setText(R.string.err_no_root);
            cardError.setVisibility(View.VISIBLE);
            return;
        }
        if (Constants.REGION_UNKNOWN.equals(currentRegion)) {
            errorMsg.setText(R.string.err_unknown_region);
            cardError.setVisibility(View.VISIBLE);
            return;
        }

        if (!blobReady) {
            // Gating screen.
            gateExplain.setText(getString(R.string.gate_explain,
                Constants.prettyRegion(currentRegion),
                Constants.prettyRegion(otherRegion)));
            manualUrl.setText(Constants.urlForRegion(otherRegion));
            cardGate.setVisibility(View.VISIBLE);
        } else {
            // Action screen.
            btnSwap.setText(getString(R.string.btn_swap, Constants.prettyRegion(otherRegion)));
            cardAction.setVisibility(View.VISIBLE);
        }
    }

    // ─── Download ─────────────────────────────────────────────────────────

    private void startDownload() {
        if (otherRegion == null) return;
        btnDownload.setEnabled(false);
        btnPick.setEnabled(false);
        dlProgress.setVisibility(View.VISIBLE);
        dlProgress.setProgress(0);
        dlProgress.setMax(100);
        dlStatus.setVisibility(View.VISIBLE);
        dlStatus.setText(getString(R.string.downloading, Constants.urlForRegion(otherRegion)));

        final String region = otherRegion;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File f = BlobLoader.download(MainActivity.this, region, new BlobLoader.ProgressListener() {
                        @Override public void onProgress(final long sofar, final long total) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    if (total > 0) {
                                        int pct = (int) (sofar * 100 / total);
                                        dlProgress.setProgress(pct);
                                        dlStatus.setText(pct + "%  (" + (sofar / 1024 / 1024) + " / " + (total / 1024 / 1024) + " MB)");
                                    } else {
                                        dlStatus.setText((sofar / 1024 / 1024) + " MB");
                                    }
                                }
                            });
                        }
                    });
                    if (!BlobLoader.validate(f)) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                        throw new IOException("Downloaded blob is invalid");
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            dlStatus.setText(R.string.dl_done);
                            refreshState();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            btnDownload.setEnabled(true);
                            btnPick.setEnabled(true);
                            dlProgress.setVisibility(View.GONE);
                            dlStatus.setText(getString(R.string.err_download, e.getMessage()));
                        }
                    });
                }
            }
        }).start();
    }

    // ─── Picker ───────────────────────────────────────────────────────────

    private void launchPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            handlePicked(data.getData());
        }
    }

    private void handlePicked(final Uri uri) {
        if (otherRegion == null) return;
        final String region = otherRegion;
        btnDownload.setEnabled(false);
        btnPick.setEnabled(false);
        dlStatus.setVisibility(View.VISIBLE);
        dlStatus.setText("Importing…");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File f = BlobLoader.importFromUri(MainActivity.this, uri, region);
                    if (!BlobLoader.validate(f)) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                        throw new IOException("not a valid F21 bands blob");
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            dlStatus.setText(R.string.picker_copied_hint);
                            refreshState();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            btnDownload.setEnabled(true);
                            btnPick.setEnabled(true);
                            dlStatus.setText(getString(R.string.err_pick_invalid));
                        }
                    });
                }
            }
        }).start();
    }

    // ─── Swap ─────────────────────────────────────────────────────────────

    private void confirmSwap() {
        if (otherRegion == null) return;
        final String from = Constants.prettyRegion(currentRegion);
        final String to   = Constants.prettyRegion(otherRegion);
        new AlertDialog.Builder(this)
            .setTitle(R.string.preswap_title)
            .setMessage(getString(R.string.preswap_msg, from, to))
            .setPositiveButton(R.string.btn_swap_confirm, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { performSwap(); }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void performSwap() {
        final String from = currentRegion;
        final String to   = otherRegion;
        if (to == null) return;

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_swap_progress, null);
        final TextView statusText = view.findViewById(R.id.swap_status);
        statusText.setText(R.string.step_backup);
        final AlertDialog pd = new AlertDialog.Builder(this)
            .setTitle(R.string.swap_progress_title)
            .setView(view)
            .setCancelable(false)
            .create();
        pd.show();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SwapEngine.swap(MainActivity.this, from, to, new SwapEngine.ProgressListener() {
                        @Override public void step(final String message) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() { statusText.setText(message); }
                            });
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            // sysrq-b inside the swap script reboots the device
                            // before we get here. If we *do* get here, the script
                            // exited cleanly without firing sysrq (unexpected) —
                            // tell the user to power-cycle manually.
                            pd.dismiss();
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.swap_done_title)
                                .setMessage(R.string.swap_done_msg)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                            refreshState();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            pd.dismiss();
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle(R.string.swap_failed_title)
                                .setMessage(getString(R.string.err_swap, e.getMessage()))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                            refreshState();
                        }
                    });
                }
            }
        }).start();
    }

}
