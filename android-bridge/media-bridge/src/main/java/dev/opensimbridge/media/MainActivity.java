package dev.opensimbridge.media;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
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
        start.setOnClickListener(v -> startBridge());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Stop local SIP audio bridge");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, MediaBridgeService.class));
            refresh();
        });
        root.addView(stop);

        setContentView(root);
        startBridge();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void startBridge() {
        Intent i = new Intent(this, MediaBridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        status.postDelayed(this::refresh, 400L);
    }

    private void refresh() {
        String ip = SipAudioBridge.localIpv4();
        status.setText(
                "SIP URI: sip:osb@" + ip + ":5060\n"
                + "RTP codec: PCMU / 8000 Hz\n"
                + "State: " + MediaBridgeService.status()
        );
    }
}
