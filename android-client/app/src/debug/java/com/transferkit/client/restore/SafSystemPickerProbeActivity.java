package com.transferkit.client.restore;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Debug-only bridge to exercise the real system folder picker under the app's own UID. */
public final class SafSystemPickerProbeActivity extends Activity {
    public static final String PREFERENCES = "saf_system_picker_acceptance";
    public static final String KEY_URI = "selected_tree_uri";
    public static final String KEY_FLAGS = "selected_tree_flags";
    public static final String KEY_RESULT = "selected_tree_result";
    private static final int REQUEST_TREE = 8471;

    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusText = new TextView(this);
        statusText.setText("TransferKit test: waiting for the system folder selection.");
        statusText.setTextSize(18f);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(32, 32, 32, 32);
        setContentView(statusText);

        if (savedInstanceState == null) {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            picker.addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(picker, REQUEST_TREE);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE) return;

        Uri uri = data == null ? null : data.getData();
        int flags = data == null ? 0 : data.getFlags();
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(KEY_URI, uri == null ? null : uri.toString())
                .putInt(KEY_FLAGS, flags)
                .putInt(KEY_RESULT, resultCode)
                .commit();
        statusText.setText("Folder selected. TransferKit is verifying and cleaning its synthetic test file.");
    }
}
