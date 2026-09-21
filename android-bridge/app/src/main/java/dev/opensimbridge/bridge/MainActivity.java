package dev.opensimbridge.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1001;

    private TextView status;
    private TextView urls;
    private TextView pairingCode;
    private TextView pairingExpiry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshUi();
        requestPermissionsIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refreshUi();
    }

    private View buildUi() {
        int pad = dp(18);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Open SIM Bridge — Call State Probe");
        title.setTextSize(24);
        root.addView(title);

        TextView warning = new TextView(this);
        warning.setText("\nMinimal probe: incoming SMS + GSM call state only. No SMS sending or call control in this build.\n");
        root.addView(warning);

        status = new TextView(this);
        root.addView(status);

        TextView urlLabel = new TextView(this);
        urlLabel.setText("\nLocal URL(s):");
        root.addView(urlLabel);

        urls = new TextView(this);
        urls.setTextIsSelectable(true);
        root.addView(urls);

        Button start = new Button(this);
        start.setText("Start / refresh local bridge");
        start.setOnClickListener(v -> startBridge());
        root.addView(start);

        Button permissions = new Button(this);
        permissions.setText("Grant missing permissions");
        permissions.setOnClickListener(v -> requestPermissionsIfNeeded());
        root.addView(permissions);

        TextView pairingLabel = new TextView(this);
        pairingLabel.setText("\nTemporary pairing code:");
        root.addView(pairingLabel);

        pairingCode = new TextView(this);
        pairingCode.setTextSize(32);
        pairingCode.setTextIsSelectable(true);
        root.addView(pairingCode);

        pairingExpiry = new TextView(this);
        root.addView(pairingExpiry);

        Button regenerate = new Button(this);
        regenerate.setText("Generate new pairing code");
        regenerate.setOnClickListener(v -> {
            PairingStore.regenerate(this);
            refreshUi();
        });
        root.addView(regenerate);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void refreshUi() {
        List<String> missing = missingPermissions();
        status.setText(
                missing.isEmpty()
                        ? "Permissions: granted"
                        : "Missing permissions: " + join(missing)
        );

        List<String> list = NetworkInfoUtil.localUrls(LocalHttpServer.PORT);
        StringBuilder sb = new StringBuilder();
        for (String value : list) sb.append(value).append("\n");
        urls.setText(sb.toString());

        pairingCode.setText(PairingStore.currentCode(this));
        long seconds = Math.max(0L, PairingStore.expiresInMs(this) / 1000L);
        pairingExpiry.setText(String.format(
                Locale.US,
                "Valid for about %d:%02d",
                seconds / 60L,
                seconds % 60L
        ));
    }

    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 23) return missing;
        addIfMissing(missing, Manifest.permission.RECEIVE_SMS, "RECEIVE_SMS");
        addIfMissing(missing, Manifest.permission.READ_PHONE_STATE, "READ_PHONE_STATE");
        return missing;
    }

    private void addIfMissing(List<String> list, String permission, String label) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            list.add(label);
        }
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) return;

        ArrayList<String> permissions = new ArrayList<>();
        addPermissionIfMissing(permissions, Manifest.permission.RECEIVE_SMS);
        addPermissionIfMissing(permissions, Manifest.permission.READ_PHONE_STATE);

        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST);
        }
    }

    private void addPermissionIfMissing(List<String> list, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            list.add(permission);
        }
    }

    private void startBridge() {
        Intent intent = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Bridge active", Toast.LENGTH_SHORT).show();
        refreshUi();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshUi();
        if (requestCode == PERMISSION_REQUEST) {
            startBridge();
        }
    }

    private String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
