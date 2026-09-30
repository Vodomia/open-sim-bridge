package dev.opensimbridge.media;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int REQ_AUDIO = 2001;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density + 0.5f);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Open SIM Bridge — M5 Local SIP Audio");
        title.setTextSize(22);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("\nKeep the GSM call active, connect the iPhone to the J7 hotspot, then dial the SIP URI below from Linphone.\n");
        root.addView(info);

        status = new TextView(this);
        status.setTextIsSelectable(true);
        root.addView(status);

        Button start = new Button(this);
        start.setText("Start local SIP audio bridge");
        start.setOnClickListener(v -> ensurePermissionAndStart());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop local SIP audio bridge");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, MediaBridgeService.class));
            refresh();
        });
        root.addView(stop);

        setContentView(root);
        ensurePermissionAndStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void ensurePermissionAndStart() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        startBridge();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startBridge();
        } else {
            refresh();
        }
    }

    private void startBridge() {
        Intent i = new Intent(this, MediaBridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        status.postDelayed(this::refresh, 400L);
    }

    private void refresh() {
        String ip = SipAudioBridge.localIpv4();
        boolean granted = Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        status.setText(
                "SIP URI: sip:osb@" + ip + ":5060\n"
                + "RTP codec: PCMU / 8000 Hz\n"
                + "RECORD_AUDIO: " + (granted ? "granted" : "missing") + "\n"
                + "State: " + MediaBridgeService.status()
        );
    }
}
