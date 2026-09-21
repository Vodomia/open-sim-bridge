package dev.opensimbridge.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public final class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }

        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;

        String sender = parts[0].getDisplayOriginatingAddress();
        StringBuilder body = new StringBuilder();
        long timestamp = System.currentTimeMillis();

        for (SmsMessage part : parts) {
            if (part != null) {
                body.append(part.getDisplayMessageBody());
                if (part.getTimestampMillis() > 0) {
                    timestamp = part.getTimestampMillis();
                }
            }
        }

        SmsStore.add(context.getApplicationContext(), sender, body.toString(), timestamp);
    }
}
