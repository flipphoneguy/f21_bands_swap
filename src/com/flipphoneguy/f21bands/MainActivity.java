package com.flipphoneguy.f21bands;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public final class MainActivity extends Activity {

    private static final String TAG = "F21Bands";
    private static final int REQ_PICK = 100;

    private TextView statusRoot, statusRegion, statusBlob;
    private LinearLayout cardGate, cardAction, cardError;
    private TextView gateExplain, dlStatus, manualUrl, errorMsg;
    private ProgressBar dlProgress;
    private Button btnDownload, btnPick, btnCopyUrl, btnSwap, btnSwapNoBackup, btnOverride;

    private boolean rooted;
    private String currentRegion = Constants.REGION_UNKNOWN;
    private String otherRegion;
    private boolean blobReady;
    /** True while a download or import is writing and validating a blob. */
    private boolean loadingBlob;
    /** File picked before the region probe had reported; replayed once it has. */
    private Uri pendingPickUri;
    /**
     * Region the user chose to flash after overriding the unknown-bands
     * refusal. Session-only, so the warning comes back on every launch.
     * Written on the UI thread, read by the probe thread.
     */
    private volatile String overrideTarget;
    /** Why region detection threw, or null when it ran and simply matched nothing. */
    private String detectError;

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

        btnDownload     = findViewById(R.id.btn_download);
        btnPick         = findViewById(R.id.btn_pick);
        btnCopyUrl      = findViewById(R.id.btn_copy_url);
        btnSwap         = findViewById(R.id.btn_swap);
        btnSwapNoBackup = findViewById(R.id.btn_swap_nobackup);
        btnOverride     = findViewById(R.id.btn_override);

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
        btnSwapNoBackup.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmSwapNoBackup(); }
        });
        btnOverride.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmOverride(); }
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
        // A download/import is in flight. Probing now would hide its progress,
        // and the loader re-probes itself when it finishes.
        if (loadingBlob) return;
        renderLoading();
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean r = RootRunner.hasRoot();
                String detected;
                String detectErr = null;
                try {
                    detected = r ? RegionDetector.detect() : Constants.REGION_UNKNOWN;
                } catch (Exception e) {
                    Log.e(TAG, "region detection failed", e);
                    detected = Constants.REGION_UNKNOWN;
                    detectErr = describe(e);
                }
                final String region = detected;
                final String err = detectErr;
                cleanupStaleBlob(region);
                String target = RegionDetector.otherRegion(region);
                if (target == null && r && Constants.REGION_UNKNOWN.equals(region)) {
                    // Live bands unrecognised, but the user picked a target via the override.
                    target = overrideTarget;
                }
                final String other = target;
                final boolean haveBlob;
                if (other != null) {
                    File f = BlobLoader.blobFile(MainActivity.this, other);
                    haveBlob = f.isFile() && f.length() > 0;
                } else {
                    haveBlob = false;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // A load started while this probe ran; it refreshes when done.
                        if (loadingBlob) return;
                        rooted = r;
                        currentRegion = region;
                        otherRegion = other;
                        blobReady = haveBlob;
                        detectError = err;
                        if (!Constants.REGION_UNKNOWN.equals(region)) overrideTarget = null;
                        render();
                        Uri pending = pendingPickUri;
                        pendingPickUri = null;
                        if (pending != null && other != null) handlePicked(pending);
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
        } else if (otherRegion != null) {
            statusRegion.setText(getString(R.string.status_region_unknown_override,
                Constants.prettyRegion(otherRegion)));
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
            btnOverride.setVisibility(View.GONE);
            cardError.setVisibility(View.VISIBLE);
            return;
        }
        if (Constants.REGION_UNKNOWN.equals(currentRegion) && otherRegion == null) {
            errorMsg.setText(detectError != null
                ? getString(R.string.err_detect_failed, detectError)
                : getString(R.string.err_unknown_region));
            btnOverride.setVisibility(View.VISIBLE);
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
            btnSwapNoBackup.setText(getString(R.string.btn_swap_nobackup, Constants.prettyRegion(otherRegion)));
            cardAction.setVisibility(View.VISIBLE);
        }
    }

    // ─── Unknown-bands override ───────────────────────────────────────────

    private void confirmOverride() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.override_title)
            .setMessage(R.string.override_warn)
            .setPositiveButton(R.string.btn_override_confirm, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { chooseOverrideTarget(); }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void chooseOverrideTarget() {
        final String[] regions = { Constants.REGION_US, Constants.REGION_STOCK };
        String[] labels = new String[regions.length];
        for (int i = 0; i < regions.length; i++) {
            labels[i] = getString(R.string.override_item, Constants.prettyRegion(regions[i]));
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.override_pick_title)
            .setItems(labels, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int which) { applyOverride(regions[which]); }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    /**
     * Treats {@code target} as the region to flash while the live bands stay
     * "unknown". Download/pick/swap then run exactly as they would for a
     * recognised device; a with-backup swap saves the current bands as
     * bands_unknown.tar.xz.
     */
    private void applyOverride(String target) {
        overrideTarget = target;
        otherRegion = target;
        File f = BlobLoader.blobFile(this, target);
        blobReady = f.isFile() && f.length() > 0;
        render();
    }

    // ─── Download ─────────────────────────────────────────────────────────

    private void startDownload() {
        if (otherRegion == null || loadingBlob) return;
        loadingBlob = true;
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
                    // download() validates the blob before giving it its final name.
                    BlobLoader.download(MainActivity.this, region, new BlobLoader.ProgressListener() {
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
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            loadingBlob = false;
                            dlStatus.setText(R.string.dl_done);
                            refreshState();
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "download failed", e);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            loadingBlob = false;
                            btnDownload.setEnabled(true);
                            btnPick.setEnabled(true);
                            dlProgress.setVisibility(View.GONE);
                            dlStatus.setText(getString(R.string.err_download, describe(e)));
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
        if (otherRegion == null) {
            // The activity was recreated behind the picker and the region
            // probe hasn't reported yet. refreshState() replays this pick.
            pendingPickUri = uri;
            return;
        }
        if (loadingBlob) return;
        loadingBlob = true;
        final String region = otherRegion;
        btnDownload.setEnabled(false);
        btnPick.setEnabled(false);
        dlStatus.setVisibility(View.VISIBLE);
        dlStatus.setText("Importing…");

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // importFromUri() validates the blob before giving it its final name.
                    BlobLoader.importFromUri(MainActivity.this, uri, region);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            loadingBlob = false;
                            dlStatus.setText(R.string.picker_copied_hint);
                            refreshState();
                        }
                    });
                } catch (final Exception e) {
                    Log.e(TAG, "import failed", e);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            loadingBlob = false;
                            btnDownload.setEnabled(true);
                            btnPick.setEnabled(true);
                            dlStatus.setText(getString(R.string.err_pick_invalid, describe(e)));
                        }
                    });
                }
            }
        }).start();
    }

    /** Exception text for the UI; some exceptions carry no message. */
    private static String describe(Throwable e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
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
                @Override public void onClick(DialogInterface d, int w) { performSwap(false); }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void confirmSwapNoBackup() {
        if (otherRegion == null) return;
        final String from = Constants.prettyRegion(currentRegion);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_nobackup_warning, null);
        TextView warn   = view.findViewById(R.id.nobackup_warn);
        TextView detail = view.findViewById(R.id.nobackup_detail);
        warn.setText(getString(R.string.preswap_nobackup_warn, from));
        detail.setText(R.string.preswap_nobackup_detail);
        new AlertDialog.Builder(this)
            .setTitle(R.string.preswap_nobackup_title)
            .setView(view)
            .setPositiveButton(R.string.btn_swap_nobackup_confirm, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { performSwap(true); }
            })
            .setNegativeButton(R.string.btn_cancel, null)
            .show();
    }

    private void performSwap(final boolean skipBackup) {
        final String from = currentRegion;
        final String to   = otherRegion;
        if (to == null) return;

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_swap_progress, null);
        final TextView statusText = view.findViewById(R.id.swap_status);
        statusText.setText(skipBackup ? R.string.step_unlock : R.string.step_backup);
        final AlertDialog pd = new AlertDialog.Builder(this)
            .setTitle(R.string.swap_progress_title)
            .setView(view)
            .setCancelable(false)
            .create();
        pd.show();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SwapEngine.swap(MainActivity.this, from, to, skipBackup, new SwapEngine.ProgressListener() {
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
