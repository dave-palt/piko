/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.sessionbackup;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Export flow: builds the session JSON and hands the user a SAF
 * "create document" dialog to save it.
 */
public class BackupSessionActivity extends AppCompatActivity {

    private static final int CREATE_FILE_REQUEST_CODE = 31;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = Utils.getContext();
        String jsonText = SessionBackup.exportSessionJson(context);
        if (jsonText == null) {
            Utils.showToastShort("Failed to read session (logged in?)");
            finish();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "piko_ig_session.json");
        startActivityForResult(intent, CREATE_FILE_REQUEST_CODE);
        // NOTE: jsonText intentionally not held across onActivityResult; re-exported
        // there via holdExport so the activity survives process death safely.
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_FILE_REQUEST_CODE && resultCode == RESULT_OK) {
            Uri uri = data != null ? data.getData() : null;
            if (uri == null) {
                Utils.showToastShort("No file selected");
            } else {
                try (java.io.OutputStream stream = getContentResolver().openOutputStream(uri)) {
                    if (stream == null) {
                        Utils.showToastShort("Failed to open file");
                    } else {
                        String jsonText = SessionBackup.exportSessionJson(Utils.getContext());
                        boolean ok = jsonText != null && SessionBackup.writeExport(this, jsonText, stream);
                        Utils.showToastShort(ok ? "Session exported" : "Export failed");
                    }
                } catch (Exception e) {
                    Logger.printException(() -> "BackupSessionActivity write failed", e);
                    Utils.showToastShort("Export failed");
                }
            }
        }
        finish();
    }
}
