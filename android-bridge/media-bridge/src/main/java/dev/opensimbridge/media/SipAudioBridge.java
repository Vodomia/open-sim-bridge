package dev.opensimbridge.media;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.telecom.TelecomManager;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Random;

final class SipAudioBridge {
    private static final String TAG = "OSB-M5";
    private final Context context;
    private static final int SIP_PORT = 5060;

    // Proven working on this SM-G610F during the privileged-audio probe.
    private static final int DEVICE_SAMPLE_RATE = 16000;

    // Linphone interop: RTP payload type 0 = G.711 µ-law @ 8000 Hz.
    private static final int RTP_SAMPLE_RATE = 8000;
    private static final int RTP_SAMPLES_PER_PACKET = 160;   // 20 ms
    private static final int DEVICE_SAMPLES_PER_PACKET = 320; // 20 ms @ 16 kHz

    private volatile boolean running;
    private volatile boolean mediaRunning;

    private DatagramSocket sipSocket;
    private DatagramSocket rtpSocket;
    private Thread sipThread;
    private Thread txThread;
    private Thread rxThread;

    private volatile InetAddress remoteRtpAddress;
    private volatile int remoteRtpPort;

    private volatile long txPackets;
    private volatile long rxPackets;
    private volatile boolean vendorForwardingEnabled;

    // Last Linphone UDP registration tuple on the J7 hotspot.
    private volatile InetAddress registeredSipAddress;
    private volatile int registeredSipPort;

    // If GSM starts ringing before Linphone has refreshed its REGISTER after boot/service restart,
    // remember the ring and place the SIP INVITE as soon as REGISTER arrives.
    private volatile boolean pendingCellularRing;
    private volatile String pendingIncomingNumber = "";

    // Outbound SIP dialog used to ring the iPhone for an incoming GSM call.
    private volatile boolean outboundInviteActive;
    private volatile boolean outboundSipAnswered;
    private volatile boolean cellularOffhook;
    private volatile String outboundCallId;
    private volatile String outboundFromTag;
    private volatile String outboundToHeader;
    private volatile String outboundRequestUri;
    private volatile String outboundViaBranch;

    SipAudioBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    void start() throws Exception {
        if (running) return;

        sipSocket = new DatagramSocket(SIP_PORT);
        sipSocket.setSoTimeout(1000);

        rtpSocket = new DatagramSocket(0);
        rtpSocket.setSoTimeout(1000);

        running = true;
        Log.i(TAG, "SIP listening UDP " + SIP_PORT + ", RTP local UDP " + rtpSocket.getLocalPort());
        MediaBridgeService.setStatus(
                "listening sip:osb@" + localIpv4() + ":5060 · RTP local " + rtpSocket.getLocalPort()
        );

        sipThread = new Thread(this::sipLoop, "osb-sip");
        sipThread.start();
    }

    void stop() {
        running = false;
        stopMedia();

        if (sipSocket != null) {
            try { sipSocket.close(); } catch (Exception ignored) {}
        }
        if (rtpSocket != null) {
            try { rtpSocket.close(); } catch (Exception ignored) {}
        }
    }

    private void sipLoop() {
        byte[] buffer = new byte[16384];

        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                sipSocket.receive(packet);

                String request = new String(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getLength(),
                        StandardCharsets.UTF_8
                );

                String first = firstLine(request);
                Log.i(TAG, "SIP RX " + packet.getAddress().getHostAddress() + ":" + packet.getPort()
                        + " " + first);

                handleSip(request, packet.getAddress(), packet.getPort());
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (running) {
                    Log.e(TAG, "SIP loop error", e);
                    MediaBridgeService.setStatus("SIP error: " + safe(e));
                }
            }
        }
    }

    private void handleSip(String request, InetAddress peer, int peerPort) throws Exception {
        String first = firstLine(request);
        if (first == null) return;

        if (first.startsWith("SIP/2.0 ")) {
            handleSipResponse(request, peer, peerPort);
            return;
        }

        if (first.startsWith("INVITE ")) {
            int offeredPort = parseAudioPort(request);
            InetAddress offeredAddress = parseConnectionAddress(request, peer);

            if (offeredPort <= 0 || !offersPcmu(request)) {
                Log.w(TAG, "INVITE rejected: PCMU not offered or bad RTP port=" + offeredPort);
                sendSip(peer, peerPort, response(request, 488, "Not Acceptable Here", null));
                return;
            }

            stopMedia();
            remoteRtpAddress = offeredAddress;
            remoteRtpPort = offeredPort;
            txPackets = 0;
            rxPackets = 0;

            sendSip(peer, peerPort, response(request, 100, "Trying", null));

            String ip = localIpv4();
            String sdp = localSdp(ip);

            sendSip(peer, peerPort, response(request, 200, "OK", sdp));

            Log.i(TAG, "INVITE accepted; remote RTP "
                    + remoteRtpAddress.getHostAddress() + ":" + remoteRtpPort
                    + ", local RTP " + rtpSocket.getLocalPort());

            startMedia();
            return;
        }

        if (first.startsWith("ACK ")) {
            Log.i(TAG, "ACK received");
            if (!mediaRunning && remoteRtpAddress != null && remoteRtpPort > 0) {
                startMedia();
            }
            return;
        }

        if (first.startsWith("BYE ") || first.startsWith("CANCEL ")) {
            Log.i(TAG, "call ended by SIP peer");
            stopMedia();
            sendSip(peer, peerPort, response(request, 200, "OK", null));
            if (outboundInviteActive) {
                Log.i(TAG, "iPhone ended auto-forwarded SIP leg; GSM leg left to Telecom/peer");
                resetOutboundDialog();
            }
            MediaBridgeService.setStatus("listening sip:osb@" + localIpv4() + ":5060");
            return;
        }

        if (first.startsWith("REGISTER ")) {
            registeredSipAddress = peer;
            registeredSipPort = peerPort;
            Log.i(TAG, "Linphone registered at " + peer.getHostAddress() + ":" + peerPort);
            sendSip(peer, peerPort, response(request, 200, "OK", null));

            if (pendingCellularRing && !outboundInviteActive && !mediaRunning) {
                Log.i(TAG, "pending GSM ring found after REGISTER -> auto INVITE now");
                onCellularRinging(pendingIncomingNumber);
            }
            return;
        }

        if (first.startsWith("OPTIONS ") || first.startsWith("INFO ")) {
            sendSip(peer, peerPort, response(request, 200, "OK", null));
            return;
        }

        sendSip(peer, peerPort, response(request, 501, "Not Implemented", null));
    }

    void onCellularRinging(String incomingNumber) {
        pendingCellularRing = true;
        pendingIncomingNumber = incomingNumber == null ? "" : incomingNumber;

        if (!running || mediaRunning || outboundInviteActive) return;

        InetAddress peer = registeredSipAddress;
        int port = registeredSipPort;
        if (peer == null || port <= 0) {
            Log.w(TAG, "GSM ringing but no Linphone REGISTER tuple is known yet");
            MediaBridgeService.setStatus("GSM ringing · waiting for Linphone registration");
            return;
        }

        try {
            txPackets = 0;
            rxPackets = 0;
            cellularOffhook = false;
            outboundSipAnswered = false;
            outboundInviteActive = true;

            String ip = localIpv4();
            outboundCallId = System.currentTimeMillis() + "-" + Math.abs(new Random().nextInt())
                    + "@opensimbridge";
            outboundFromTag = "osb" + Integer.toHexString(new Random().nextInt());
            outboundViaBranch = "z9hG4bK" + Long.toHexString(System.nanoTime());
            outboundRequestUri = "sip:iphone@" + peer.getHostAddress() + ":" + port;

            String caller = sanitizeDisplay(incomingNumber);
            if (caller.isEmpty()) caller = "GSM incoming";

            String sdp = localSdp(ip);
            byte[] bodyBytes = sdp.getBytes(StandardCharsets.UTF_8);

            StringBuilder invite = new StringBuilder();
            invite.append("INVITE ").append(outboundRequestUri).append(" SIP/2.0\r\n");
            invite.append("Via: SIP/2.0/UDP ").append(ip).append(":5060;branch=")
                    .append(outboundViaBranch).append(";rport\r\n");
            invite.append("Max-Forwards: 70\r\n");
            invite.append("From: \"").append(caller).append("\" <sip:osb@")
                    .append(ip).append(":5060>;tag=").append(outboundFromTag).append("\r\n");
            invite.append("To: <").append(outboundRequestUri).append(">\r\n");
            invite.append("Call-ID: ").append(outboundCallId).append("\r\n");
            invite.append("CSeq: 1 INVITE\r\n");
            invite.append("Contact: <sip:osb@").append(ip).append(":5060>\r\n");
            invite.append("Content-Type: application/sdp\r\n");
            invite.append("Content-Length: ").append(bodyBytes.length).append("\r\n\r\n");
            invite.append(sdp);

            pendingCellularRing = false;
            Log.i(TAG, "GSM RINGING -> auto INVITE Linphone " + peer.getHostAddress() + ":" + port);
            MediaBridgeService.setStatus("incoming GSM -> ringing iPhone");
            sendSip(peer, port, invite.toString());
        } catch (Exception e) {
            Log.e(TAG, "auto INVITE failed", e);
            resetOutboundDialog();
        }
    }

    void onCellularOffhook() {
        pendingCellularRing = false;
        pendingIncomingNumber = "";
        cellularOffhook = true;
        Log.i(TAG, "cellular state OFFHOOK");
        if (outboundInviteActive && outboundSipAnswered
                && remoteRtpAddress != null && remoteRtpPort > 0) {
            startMedia();
        }
    }

    void onCellularIdle() {
        pendingCellularRing = false;
        pendingIncomingNumber = "";
        cellularOffhook = false;
        if (outboundInviteActive) {
            Log.i(TAG, "cellular state IDLE -> terminate outbound SIP leg");
            sendOutboundTerminate();
        }
        stopMedia();
        resetOutboundDialog();
    }

    private void handleSipResponse(String responseText, InetAddress peer, int peerPort)
            throws Exception {
        if (!outboundInviteActive) return;

        String cseq = header(responseText, "CSeq");
        String callId = header(responseText, "Call-ID");
        if (cseq == null || !cseq.toUpperCase(Locale.US).contains("INVITE")) return;
        if (outboundCallId != null && callId != null && !outboundCallId.equals(callId)) return;

        int code = parseStatusCode(firstLine(responseText));
        if (code < 0) return;

        if (code == 180 || code == 183) {
            Log.i(TAG, "Linphone ringing SIP " + code);
            MediaBridgeService.setStatus("iPhone ringing");
            return;
        }

        if (code >= 200 && code < 300) {
            int offeredPort = parseAudioPort(responseText);
            InetAddress offeredAddress = parseConnectionAddress(responseText, peer);
            if (offeredPort <= 0 || !offersPcmu(responseText)) {
                Log.e(TAG, "Linphone 200 OK has no usable PCMU RTP");
                resetOutboundDialog();
                return;
            }

            outboundToHeader = header(responseText, "To");
            remoteRtpAddress = offeredAddress;
            remoteRtpPort = offeredPort;
            registeredSipAddress = peer;
            registeredSipPort = peerPort;

            sendSip(peer, peerPort, outboundAck());
            outboundSipAnswered = true;

            Log.i(TAG, "Linphone answered auto INVITE; RTP "
                    + offeredAddress.getHostAddress() + ":" + offeredPort);
            answerCellularCall();
            return;
        }

        if (code >= 300) {
            Log.i(TAG, "Linphone rejected auto INVITE SIP " + code);
            resetOutboundDialog();
        }
    }

    private String localSdp(String ip) {
        return "v=0\r\n"
                + "o=osb 1 1 IN IP4 " + ip + "\r\n"
                + "s=OpenSIMBridge\r\n"
                + "c=IN IP4 " + ip + "\r\n"
                + "t=0 0\r\n"
                + "m=audio " + rtpSocket.getLocalPort() + " RTP/AVP 0\r\n"
                + "a=rtpmap:0 PCMU/8000\r\n"
                + "a=ptime:20\r\n"
                + "a=sendrecv\r\n";
    }

    private String outboundAck() {
        String ip = localIpv4();
        String to = outboundToHeader == null
                ? "<" + outboundRequestUri + ">"
                : outboundToHeader;

        return "ACK " + outboundRequestUri + " SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP " + ip + ":5060;branch=z9hG4bK"
                + Long.toHexString(System.nanoTime()) + ";rport\r\n"
                + "Max-Forwards: 70\r\n"
                + "From: <sip:osb@" + ip + ":5060>;tag=" + outboundFromTag + "\r\n"
                + "To: " + to + "\r\n"
                + "Call-ID: " + outboundCallId + "\r\n"
                + "CSeq: 1 ACK\r\n"
                + "Contact: <sip:osb@" + ip + ":5060>\r\n"
                + "Content-Length: 0\r\n\r\n";
    }

    private void answerCellularCall() {
        if (Build.VERSION.SDK_INT < 26) return;
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "ANSWER_PHONE_CALLS missing; cannot auto-answer GSM");
            MediaBridgeService.setStatus("iPhone answered · GSM answer permission missing");
            return;
        }

        try {
            TelecomManager telecom =
                    (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
            if (telecom == null) {
                Log.e(TAG, "TelecomManager unavailable");
                return;
            }
            telecom.acceptRingingCall();
            Log.i(TAG, "requested GSM answer after Linphone answer");
            MediaBridgeService.setStatus("iPhone answered · answering GSM");
        } catch (Exception e) {
            Log.e(TAG, "GSM auto-answer failed", e);
        }
    }

    private void sendOutboundTerminate() {
        InetAddress peer = registeredSipAddress;
        int port = registeredSipPort;
        if (peer == null || port <= 0 || outboundRequestUri == null) return;

        try {
            String ip = localIpv4();
            String method = outboundSipAnswered ? "BYE" : "CANCEL";
            String to = outboundToHeader == null
                    ? "<" + outboundRequestUri + ">"
                    : outboundToHeader;
            String branch = outboundSipAnswered
                    ? "z9hG4bK" + Long.toHexString(System.nanoTime())
                    : outboundViaBranch;
            int cseq = outboundSipAnswered ? 2 : 1;

            String msg = method + " " + outboundRequestUri + " SIP/2.0\r\n"
                    + "Via: SIP/2.0/UDP " + ip + ":5060;branch=" + branch + ";rport\r\n"
                    + "Max-Forwards: 70\r\n"
                    + "From: <sip:osb@" + ip + ":5060>;tag=" + outboundFromTag + "\r\n"
                    + "To: " + to + "\r\n"
                    + "Call-ID: " + outboundCallId + "\r\n"
                    + "CSeq: " + cseq + " " + method + "\r\n"
                    + "Content-Length: 0\r\n\r\n";
            sendSip(peer, port, msg);
        } catch (Exception e) {
            Log.w(TAG, "SIP terminate failed: " + safe(e));
        }
    }

    private void resetOutboundDialog() {
        outboundInviteActive = false;
        outboundSipAnswered = false;
        outboundCallId = null;
        outboundFromTag = null;
        outboundToHeader = null;
        outboundRequestUri = null;
        outboundViaBranch = null;
    }

    private static int parseStatusCode(String firstLine) {
        if (firstLine == null || !firstLine.startsWith("SIP/2.0 ")) return -1;
        String[] parts = firstLine.split("\\s+");
        if (parts.length < 2) return -1;
        try { return Integer.parseInt(parts[1]); }
        catch (Exception ignored) { return -1; }
    }

    private static String sanitizeDisplay(String value) {
        if (value == null) return "";
        return value.replace("\r", "").replace("\n", "").replace("\"", "").trim();
    }

    private void startMedia() {
        if (mediaRunning || remoteRtpAddress == null || remoteRtpPort <= 0) return;

        vendorForwardingEnabled = VendorAudioControl.setCallForwarding(true);
        Log.i(TAG, "vendor call_forwarding requested; enabled=" + vendorForwardingEnabled);

        mediaRunning = true;
        Log.i(TAG, "starting media; Android side 16k PCM, RTP PCMU/8k");
        MediaBridgeService.setStatus(
                "media starting → " + remoteRtpAddress.getHostAddress() + ":" + remoteRtpPort
        );

        txThread = new Thread(this::sendAudioLoop, "osb-rtp-downlink");
        rxThread = new Thread(this::receiveAudioLoop, "osb-rtp-uplink");
        txThread.start();
        rxThread.start();
    }

    private void stopMedia() {
        mediaRunning = false;

        if (txThread != null) {
            try { txThread.join(1200); } catch (InterruptedException ignored) {}
            txThread = null;
        }
        if (rxThread != null) {
            try { rxThread.join(1200); } catch (InterruptedException ignored) {}
            rxThread = null;
        }

        if (vendorForwardingEnabled) {
            VendorAudioControl.setCallForwarding(false);
            vendorForwardingEnabled = false;
        }

        remoteRtpAddress = null;
        remoteRtpPort = 0;
    }

    private void sendAudioLoop() {
        AudioRecord record = null;

        try {
            int min = AudioRecord.getMinBufferSize(
                    DEVICE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int bufferBytes = Math.max(min * 4, 8192);

            Log.i(TAG, "VOICE_DOWNLINK AudioRecord min=" + min + " buffer=" + bufferBytes);

            record = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_DOWNLINK,
                    DEVICE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
            );

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "VOICE_DOWNLINK AudioRecord not initialized");
                MediaBridgeService.setStatus("VOICE_DOWNLINK 16k init failed");
                mediaRunning = false;
                return;
            }

            record.startRecording();
            Log.i(TAG, "VOICE_DOWNLINK recording started");

            short[] pcm16 = new short[DEVICE_SAMPLES_PER_PACKET];
            byte[] packet = new byte[12 + RTP_SAMPLES_PER_PACKET];

            Random random = new Random();
            int seq = random.nextInt(65536);
            long timestamp = random.nextInt() & 0xffffffffL;
            int ssrc = random.nextInt();
            boolean marker = true;

            while (mediaRunning) {
                int got = readExact(record, pcm16, DEVICE_SAMPLES_PER_PACKET);
                if (got != DEVICE_SAMPLES_PER_PACKET) continue;

                packet[0] = (byte) 0x80;
                packet[1] = (byte) (marker ? 0x80 : 0x00);
                marker = false;

                packet[2] = (byte) ((seq >>> 8) & 0xff);
                packet[3] = (byte) (seq & 0xff);

                packet[4] = (byte) ((timestamp >>> 24) & 0xff);
                packet[5] = (byte) ((timestamp >>> 16) & 0xff);
                packet[6] = (byte) ((timestamp >>> 8) & 0xff);
                packet[7] = (byte) (timestamp & 0xff);

                packet[8] = (byte) ((ssrc >>> 24) & 0xff);
                packet[9] = (byte) ((ssrc >>> 16) & 0xff);
                packet[10] = (byte) ((ssrc >>> 8) & 0xff);
                packet[11] = (byte) (ssrc & 0xff);

                // 16 kHz -> 8 kHz: average each adjacent pair before µ-law encoding.
                for (int i = 0; i < RTP_SAMPLES_PER_PACKET; i++) {
                    int a = pcm16[i * 2];
                    int b = pcm16[i * 2 + 1];
                    short down = (short) ((a + b) / 2);
                    packet[12 + i] = linearToMuLaw(down);
                }

                InetAddress dst = remoteRtpAddress;
                int port = remoteRtpPort;
                if (dst == null || port <= 0) break;

                rtpSocket.send(new DatagramPacket(packet, packet.length, dst, port));

                txPackets++;
                if (txPackets == 1 || txPackets % 250 == 0) {
                    Log.i(TAG, "RTP TX packets=" + txPackets + " to "
                            + dst.getHostAddress() + ":" + port
                            + " RX=" + rxPackets);
                    updateMediaStatus();
                }

                seq = (seq + 1) & 0xffff;
                timestamp = (timestamp + RTP_SAMPLES_PER_PACKET) & 0xffffffffL;
            }
        } catch (Exception e) {
            Log.e(TAG, "downlink error", e);
            if (mediaRunning) MediaBridgeService.setStatus("downlink error: " + safe(e));
        } finally {
            if (record != null) {
                try {
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop();
                    }
                } catch (Exception ignored) {}
                try { record.release(); } catch (Exception ignored) {}
            }
        }
    }

    private void receiveAudioLoop() {
        AudioTrack track = null;

        try {
            int min = AudioTrack.getMinBufferSize(
                    DEVICE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int bufferBytes = Math.max(min * 4, 8192);

            Log.i(TAG, "VOICE_CALL AudioTrack min=" + min + " buffer=" + bufferBytes);

            track = new AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    DEVICE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                    AudioTrack.MODE_STREAM
            );

            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "VOICE_CALL AudioTrack not initialized");
                MediaBridgeService.setStatus("VOICE_CALL 16k init failed");
                mediaRunning = false;
                return;
            }

            track.setVolume(1.0f);
            track.play();
            Log.i(TAG, "VOICE_CALL AudioTrack started");

            byte[] data = new byte[4096];
            short[] pcm16 = new short[4096];

            while (mediaRunning) {
                try {
                    DatagramPacket packet = new DatagramPacket(data, data.length);
                    rtpSocket.receive(packet);

                    int len = packet.getLength();
                    if (len < 12) continue;

                    int b0 = data[0] & 0xff;
                    int version = (b0 >>> 6) & 0x03;
                    if (version != 2) continue;

                    int pt = data[1] & 0x7f;
                    if (pt != 0) continue;

                    int cc = b0 & 0x0f;
                    int offset = 12 + (cc * 4);
                    if (offset > len) continue;

                    boolean extension = (b0 & 0x10) != 0;
                    if (extension) {
                        if (offset + 4 > len) continue;
                        int extWords = ((data[offset + 2] & 0xff) << 8)
                                | (data[offset + 3] & 0xff);
                        offset += 4 + extWords * 4;
                        if (offset > len) continue;
                    }

                    int payload = len - offset;
                    if (payload <= 0) continue;

                    // Symmetric RTP: once Linphone sends media, send back to its real source tuple.
                    remoteRtpAddress = packet.getAddress();
                    remoteRtpPort = packet.getPort();

                    // 8 kHz µ-law -> 16 kHz PCM: duplicate each decoded sample.
                    int outSamples = Math.min(payload * 2, pcm16.length);
                    int usablePayload = outSamples / 2;
                    for (int i = 0; i < usablePayload; i++) {
                        short s = muLawToLinear(data[offset + i]);
                        pcm16[i * 2] = s;
                        pcm16[i * 2 + 1] = s;
                    }

                    int written = 0;
                    while (written < outSamples && mediaRunning) {
                        int n = track.write(pcm16, written, outSamples - written);
                        if (n <= 0) break;
                        written += n;
                    }

                    rxPackets++;
                    if (rxPackets == 1 || rxPackets % 250 == 0) {
                        Log.i(TAG, "RTP RX packets=" + rxPackets + " from "
                                + packet.getAddress().getHostAddress() + ":" + packet.getPort()
                                + " TX=" + txPackets);
                        updateMediaStatus();
                    }
                } catch (SocketTimeoutException ignored) {
                    if (mediaRunning && txPackets > 0) updateMediaStatus();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "uplink error", e);
            if (mediaRunning) MediaBridgeService.setStatus("uplink error: " + safe(e));
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
            }
        }
    }

    private void updateMediaStatus() {
        InetAddress addr = remoteRtpAddress;
        MediaBridgeService.setStatus(
                "media "
                        + (addr == null ? "?" : addr.getHostAddress())
                        + ":" + remoteRtpPort
                        + " · RTP TX=" + txPackets
                        + " RX=" + rxPackets
        );
    }

    private static int readExact(AudioRecord record, short[] out, int wanted) {
        int offset = 0;
        while (offset < wanted) {
            int n = record.read(out, offset, wanted - offset);
            if (n <= 0) return offset;
            offset += n;
        }
        return offset;
    }

    private void sendSip(InetAddress address, int port, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        sipSocket.send(new DatagramPacket(bytes, bytes.length, address, port));
        Log.i(TAG, "SIP TX " + address.getHostAddress() + ":" + port + " " + firstLine(text));
    }

    private static String response(String request, int code, String reason, String sdp) {
        String via = header(request, "Via");
        String from = header(request, "From");
        String to = header(request, "To");
        String callId = header(request, "Call-ID");
        String cseq = header(request, "CSeq");

        if (to != null && !to.toLowerCase(Locale.US).contains(";tag=")) {
            to = to + ";tag=osbm5";
        }

        String body = sdp == null ? "" : sdp;
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        StringBuilder out = new StringBuilder();
        out.append("SIP/2.0 ").append(code).append(' ').append(reason).append("\r\n");
        if (via != null) out.append("Via: ").append(via).append("\r\n");
        if (from != null) out.append("From: ").append(from).append("\r\n");
        if (to != null) out.append("To: ").append(to).append("\r\n");
        if (callId != null) out.append("Call-ID: ").append(callId).append("\r\n");
        if (cseq != null) out.append("CSeq: ").append(cseq).append("\r\n");
        out.append("Server: OpenSIMBridge-M5\r\n");
        out.append("Allow: INVITE, ACK, BYE, CANCEL, OPTIONS, INFO\r\n");

        if (code >= 200 && code < 300 && request.startsWith("INVITE ")) {
            out.append("Contact: <sip:osb@").append(localIpv4()).append(":5060>\r\n");
        }

        if (sdp != null) {
            out.append("Content-Type: application/sdp\r\n");
        }

        out.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        out.append("\r\n");
        out.append(body);
        return out.toString();
    }

    private static String firstLine(String text) {
        if (text == null) return null;
        int p = text.indexOf("\r\n");
        if (p < 0) p = text.indexOf('\n');
        return p < 0 ? text.trim() : text.substring(0, p).trim();
    }

    private static String header(String text, String name) {
        String[] lines = text.split("\\r?\\n");
        String prefix = name.toLowerCase(Locale.US) + ":";
        for (String line : lines) {
            String low = line.toLowerCase(Locale.US);
            if (low.startsWith(prefix)) {
                return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return null;
    }

    private static int parseAudioPort(String sdp) {
        String[] lines = sdp.split("\\r?\\n");
        for (String line : lines) {
            if (line.startsWith("m=audio ")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    try { return Integer.parseInt(parts[1]); }
                    catch (Exception ignored) {}
                }
            }
        }
        return -1;
    }

    private static InetAddress parseConnectionAddress(String sdp, InetAddress fallback) {
        String[] lines = sdp.split("\\r?\\n");
        for (String line : lines) {
            if (line.startsWith("c=IN IP4 ")) {
                String host = line.substring("c=IN IP4 ".length()).trim();
                try {
                    InetAddress parsed = InetAddress.getByName(host);
                    if (!parsed.isAnyLocalAddress() && !parsed.isMulticastAddress()) {
                        return parsed;
                    }
                } catch (Exception ignored) {}
            }
        }
        return fallback;
    }

    private static boolean offersPcmu(String sdp) {
        String[] lines = sdp.split("\\r?\\n");
        for (String line : lines) {
            if (!line.startsWith("m=audio ")) continue;
            String[] parts = line.trim().split("\\s+");
            for (int i = 3; i < parts.length; i++) {
                if ("0".equals(parts[i])) return true;
            }
        }
        return false;
    }

    static String localIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                String fallback = null;
                for (NetworkInterface iface : Collections.list(interfaces)) {
                    if (!iface.isUp() || iface.isLoopback()) continue;
                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    for (InetAddress a : Collections.list(addresses)) {
                        if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                        String ip = a.getHostAddress();
                        if ("192.168.43.1".equals(ip)) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
                if (fallback != null) return fallback;
            }
        } catch (Exception ignored) {}
        return "192.168.43.1";
    }

    private static byte linearToMuLaw(short sample) {
        int pcm = sample;
        int sign = (pcm >> 8) & 0x80;
        if (sign != 0) pcm = -pcm;
        if (pcm > 32635) pcm = 32635;
        pcm += 0x84;

        int exponent = 7;
        for (int mask = 0x4000; (pcm & mask) == 0 && exponent > 0; mask >>= 1) {
            exponent--;
        }

        int mantissa = (pcm >> (exponent + 3)) & 0x0f;
        return (byte) (~(sign | (exponent << 4) | mantissa));
    }

    private static short muLawToLinear(byte value) {
        int u = (~value) & 0xff;
        int sign = u & 0x80;
        int exponent = (u >> 4) & 0x07;
        int mantissa = u & 0x0f;

        int sample = ((mantissa << 3) + 0x84) << exponent;
        sample -= 0x84;
        if (sign != 0) sample = -sample;

        if (sample > 32767) sample = 32767;
        if (sample < -32768) sample = -32768;
        return (short) sample;
    }

    private static String safe(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
