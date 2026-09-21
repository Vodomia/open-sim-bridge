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

import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int SMS_PERMISSION_REQUEST = 1001;

    private TextView status;
    private TextView urls;
    private TextView pairingCode;
    private TextView pairingExpiry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshUi();
        requestSmsPermissionIfNeeded();
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
        title.setText("Open SIM Bridge — M0.1");
        title.setTextSize(24);
        root.addView(title);

        TextView warning = new TextView(this);
        warning.setText("\nPROTOTYPE: Do not use real banking OTPs before M1 encrypted pairing is finished.\n");
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
        start.setText("Start local bridge");
        start.setOnClickListener(v -> startBridge());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop local bridge");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, BridgeService.class));
            Toast.makeText(this, "Bridge stopped", Toast.LENGTH_SHORT).show();
        });
        root.addView(stop);

        TextView pairingLabel = new TextView(this);
        pairingLabel.setText("\n6-digit pairing code:");
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
            Toast.makeText(this, "New pairing code generated", Toast.LENGTH_SHORT).show();
        });
        root.addView(regenerate);

        Button clear = new Button(this);
        clear.setText("Clear stored test messages");
        clear.setOnClickListener(v -> {
            SmsStore.clear(this);
            Toast.makeText(this, "Messages cleared", Toast.LENGTH_SHORT).show();
        });
        root.addView(clear);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void refreshUi() {
        boolean allowed = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;

        status.setText("SMS permission: " + (allowed ? "granted" : "not granted"));

        List<String> list = NetworkInfoUtil.localUrls(LocalHttpServer.PORT);
        StringBuilder sb = new StringBuilder();
        for (String value : list) sb.append(value).append("\n");
        urls.setText(sb.toString());

        pairingCode.setText(PairingStore.currentCode(this));
        long seconds = Math.max(0L, PairingStore.expiresInMs(this) / 1000L);
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        pairingExpiry.setText(String.format(
                Locale.US,
                "Valid for about %d:%02d",
                minutes,
                remainingSeconds
        ));
    }

    private void requestSmsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECEIVE_SMS},
                    SMS_PERMISSION_REQUEST
            );
        }
    }

    private void startBridge() {
        Intent intent = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Local bridge started", Toast.LENGTH_SHORT).show();
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
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
