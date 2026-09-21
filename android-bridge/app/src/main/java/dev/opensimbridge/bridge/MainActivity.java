package dev.opensimbridge.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.telecom.TelecomManager;
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
    private TextView dialerStatus;

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
        title.setText("Open SIM Bridge — Telephony Prototype");
        title.setTextSize(24);
        root.addView(title);

        TextView warning = new TextView(this);
        warning.setText("\nPrototype. For API 27 end/reject testing, Open SIM Bridge must temporarily be the default phone app.\n");
        root.addView(warning);

        status = new TextView(this);
        root.addView(status);

        dialerStatus = new TextView(this);
        root.addView(dialerStatus);

        Button defaultDialer = new Button(this);
        defaultDialer.setText("Set Open SIM Bridge as default phone app");
        defaultDialer.setOnClickListener(v -> requestDefaultDialer());
        root.addView(defaultDialer);

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
        permissions.setText("Grant missing telephony permissions");
        permissions.setOnClickListener(v -> requestPermissionsIfNeeded());
        root.addView(permissions);

        Button stop = new Button(this);
        stop.setText("Stop local bridge");
        stop.setOnClickListener(v -> stopService(new Intent(this, BridgeService.class)));
        root.addView(stop);

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
                        ? "Telephony permissions: granted"
                        : "Missing permissions: " + join(missing)
        );

        dialerStatus.setText(
                "Default phone app: " + (isDefaultDialer() ? "YES" : "NO")
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

    private boolean isDefaultDialer() {
        if (Build.VERSION.SDK_INT < 23) return false;
        TelecomManager telecom =
                (TelecomManager) getSystemService(TELECOM_SERVICE);
        return telecom != null
                && getPackageName().equals(telecom.getDefaultDialerPackage());
    }

    private void requestDefaultDialer() {
        if (Build.VERSION.SDK_INT < 23) return;

        Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER);
        intent.putExtra(
                TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                getPackageName()
        );
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Default dialer request failed", Toast.LENGTH_LONG).show();
        }
    }

    private List<String> missingPermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 23) return missing;

        addIfMissing(missing, Manifest.permission.RECEIVE_SMS, "RECEIVE_SMS");
        addIfMissing(missing, Manifest.permission.SEND_SMS, "SEND_SMS");
        addIfMissing(missing, Manifest.permission.READ_PHONE_STATE, "READ_PHONE_STATE");
        addIfMissing(missing, Manifest.permission.READ_CALL_LOG, "READ_CALL_LOG");
        addIfMissing(missing, Manifest.permission.CALL_PHONE, "CALL_PHONE");
        if (Build.VERSION.SDK_INT >= 26) {
            addIfMissing(missing, Manifest.permission.ANSWER_PHONE_CALLS, "ANSWER_PHONE_CALLS");
        }
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
        addPermissionIfMissing(permissions, Manifest.permission.SEND_SMS);
        addPermissionIfMissing(permissions, Manifest.permission.READ_PHONE_STATE);
        addPermissionIfMissing(permissions, Manifest.permission.READ_CALL_LOG);
        addPermissionIfMissing(permissions, Manifest.permission.CALL_PHONE);
        if (Build.VERSION.SDK_INT >= 26) {
            addPermissionIfMissing(permissions, Manifest.permission.ANSWER_PHONE_CALLS);
        }

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
        if (requestCode == PERMISSION_REQUEST) startBridge();
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
