package dev.opensimbridge.media;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public final class MediaBridgeService extends Service {
    private static final String CHANNEL_ID = "osb_media_bridge";
    private static final int NOTIFICATION_ID = 8790;
    private static volatile String lastStatus = "stopped";

    private SipAudioBridge bridge;
    private CellularCallMonitor callMonitor;

    static String status() {
        return lastStatus;
    }

    static void setStatus(String value) {
        lastStatus = value;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                Build.VERSION.SDK_INT >= 23
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        startForeground(
                NOTIFICATION_ID,
                nb.setContentTitle("OSB Media Bridge")
                        .setContentText("Local SIP audio bridge on UDP 5060")
                        .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                        .setOngoing(true)
                        .setContentIntent(pendingIntent)
                        .build()
        );

        bridge = new SipAudioBridge(this);
        try {
            bridge.start();
            callMonitor = new CellularCallMonitor(this, new CellularCallMonitor.Listener() {
                @Override public void onRinging(String incomingNumber) {
                    bridge.onCellularRinging(incomingNumber);
                }
                @Override public void onOffhook() {
                    bridge.onCellularOffhook();
                }
                @Override public void onIdle() {
                    bridge.onCellularIdle();
                }
            });
            callMonitor.start();
            setStatus("listening on sip:osb@" + SipAudioBridge.localIpv4() + ":5060");
        } catch (Exception e) {
            setStatus("start failed: " + safe(e));
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (callMonitor != null) callMonitor.stop();
        if (bridge != null) bridge.stop();
        setStatus("stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "OSB Media Bridge", NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private static String safe(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
