/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.sessionbackup;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Import flow: SAF "open document", write the session into IG's prefs,
 * then restart the app so the session bootstrap re-reads everything.
 */
public class RestoreSessionActivity extends AppCompatActivity {

    private static final int OPEN_FILE_REQUEST_CODE = 42;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/json", "text/plain"});
        startActivityForResult(intent, OPEN_FILE_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OPEN_FILE_REQUEST_CODE && resultCode == RESULT_OK) {
            Uri uri = data != null ? data.getData() : null;
            if (uri == null) {
                Utils.showToastShort("No file selected");
            } else {
                restore(uri);
            }
        }
        finish();
    }

    private void restore(Uri uri) {
        // Importing over a LIVE session loses: the in-memory session keeps the
        // old device identity while prefs now hold the new one, and the server
        // rejects the mismatched combination after restart. Only import while
        // logged out (fresh install, or after the login screen returns).
        if (!app.morphe.extension.instagram.patches.sessionbackup.SessionBackup
                .isLoggedOut(Utils.getContext())) {
            Logger.printInfo(() -> "RestoreSessionActivity: refusing import, already logged in");
            Utils.showToastShort("Already logged in — import only works while logged out");
            return;
        }

        // Cheap extension pre-check: SAF may hand us text/plain or octet-stream
        // for .json files, so only reject clearly-wrong extensions here; the
        // JSON parser below remains the real gate.
        String name = uri.getLastPathSegment();
        if (name != null) {
            String lower = name.toLowerCase();
            if (!lower.endsWith(".json") && !lower.endsWith(".txt")) {
                Utils.showToastShort("Not a session backup file");
                return;
            }
        }

        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                Utils.showToastShort("Failed to open file");
                return;
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            String jsonText = new String(buffer.toByteArray(), StandardCharsets.UTF_8);

            boolean ok = SessionBackup.importSessionJson(Utils.getContext(), jsonText);
            if (!ok) {
                Utils.showToastShort("Import failed");
                return;
            }
            Utils.showToastShort("Session imported, restarting…");
            Utils.restartApp(this);
        } catch (Exception e) {
            Logger.printException(() -> "RestoreSessionActivity failed", e);
            Utils.showToastShort("Import failed");
        }
    }
}
