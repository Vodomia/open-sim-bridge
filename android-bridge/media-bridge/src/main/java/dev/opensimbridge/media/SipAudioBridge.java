package dev.opensimbridge.media;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

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
    private static final int SIP_PORT = 5060;
    private static final int SAMPLE_RATE = 8000;
    private static final int SAMPLES_PER_PACKET = 160;

    private volatile boolean running;
    private volatile boolean mediaRunning;

    private DatagramSocket sipSocket;
    private DatagramSocket rtpSocket;
    private Thread sipThread;
    private Thread txThread;
    private Thread rxThread;

    private volatile InetAddress remoteRtpAddress;
    private volatile int remoteRtpPort;

    void start() throws Exception {
        if (running) return;

        sipSocket = new DatagramSocket(SIP_PORT);
        sipSocket.setSoTimeout(1000);

        rtpSocket = new DatagramSocket(0);
        rtpSocket.setSoTimeout(1000);

        running = true;
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

                handleSip(request, packet.getAddress(), packet.getPort());
            } catch (SocketTimeoutException ignored) {
            } catch (Exception e) {
                if (running) {
                    MediaBridgeService.setStatus("SIP error: " + safe(e));
                }
            }
        }
    }

    private void handleSip(String request, InetAddress peer, int peerPort) throws Exception {
        String first = firstLine(request);
        if (first == null) return;

        if (first.startsWith("INVITE ")) {
            int offeredPort = parseAudioPort(request);
            if (offeredPort <= 0 || !offersPcmu(request)) {
                sendSip(peer, peerPort, response(request, 488, "Not Acceptable Here", null));
                return;
            }

            stopMedia();
            remoteRtpAddress = peer;
            remoteRtpPort = offeredPort;

            sendSip(peer, peerPort, response(request, 100, "Trying", null));

            String ip = localIpv4();
            String sdp =
                    "v=0\r\n"
                    + "o=osb 1 1 IN IP4 " + ip + "\r\n"
                    + "s=OpenSIMBridge\r\n"
                    + "c=IN IP4 " + ip + "\r\n"
                    + "t=0 0\r\n"
                    + "m=audio " + rtpSocket.getLocalPort() + " RTP/AVP 0\r\n"
                    + "a=rtpmap:0 PCMU/8000\r\n"
                    + "a=ptime:20\r\n"
                    + "a=sendrecv\r\n";

            sendSip(peer, peerPort, response(request, 200, "OK", sdp));
            startMedia();
            MediaBridgeService.setStatus(
                    "media connected to " + peer.getHostAddress() + ":" + offeredPort
            );
            return;
        }

        if (first.startsWith("ACK ")) {
            if (!mediaRunning && remoteRtpAddress != null && remoteRtpPort > 0) {
                startMedia();
            }
            return;
        }

        if (first.startsWith("BYE ") || first.startsWith("CANCEL ")) {
            stopMedia();
            sendSip(peer, peerPort, response(request, 200, "OK", null));
            MediaBridgeService.setStatus("listening on sip:osb@" + localIpv4() + ":5060");
            return;
        }

        if (first.startsWith("OPTIONS ")
                || first.startsWith("INFO ")
                || first.startsWith("REGISTER ")) {
            sendSip(peer, peerPort, response(request, 200, "OK", null));
            return;
        }

        if (!first.startsWith("SIP/2.0 ")) {
            sendSip(peer, peerPort, response(request, 501, "Not Implemented", null));
        }
    }

    private void startMedia() {
        if (mediaRunning || remoteRtpAddress == null || remoteRtpPort <= 0) return;

        mediaRunning = true;
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

        remoteRtpAddress = null;
        remoteRtpPort = 0;
    }

    private void sendAudioLoop() {
        AudioRecord record = null;

        try {
            int min = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int bufferBytes = Math.max(min * 4, 4096);

            record = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_DOWNLINK,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
            );

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                MediaBridgeService.setStatus("VOICE_DOWNLINK AudioRecord init failed");
                mediaRunning = false;
                return;
            }

            record.startRecording();

            short[] pcm = new short[SAMPLES_PER_PACKET];
            byte[] packet = new byte[12 + SAMPLES_PER_PACKET];
            Random random = new Random();
            int seq = random.nextInt(65536);
            long timestamp = random.nextInt() & 0xffffffffL;
            int ssrc = random.nextInt();
            boolean marker = true;

            while (mediaRunning) {
                int got = readExact(record, pcm, SAMPLES_PER_PACKET);
                if (got != SAMPLES_PER_PACKET) continue;

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

                for (int i = 0; i < SAMPLES_PER_PACKET; i++) {
                    packet[12 + i] = linearToMuLaw(pcm[i]);
                }

                InetAddress dst = remoteRtpAddress;
                int port = remoteRtpPort;
                if (dst == null || port <= 0) break;

                rtpSocket.send(new DatagramPacket(packet, packet.length, dst, port));

                seq = (seq + 1) & 0xffff;
                timestamp = (timestamp + SAMPLES_PER_PACKET) & 0xffffffffL;
            }
        } catch (Exception e) {
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
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            int bufferBytes = Math.max(min * 4, 4096);

            track = new AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                    AudioTrack.MODE_STREAM
            );

            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                MediaBridgeService.setStatus("VOICE_CALL AudioTrack init failed");
                mediaRunning = false;
                return;
            }

            track.setVolume(1.0f);
            track.play();

            byte[] data = new byte[2048];
            short[] pcm = new short[1600];

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
                    if (payload > pcm.length) payload = pcm.length;

                    for (int i = 0; i < payload; i++) {
                        pcm[i] = muLawToLinear(data[offset + i]);
                    }

                    int written = 0;
                    while (written < payload && mediaRunning) {
                        int n = track.write(pcm, written, payload - written);
                        if (n <= 0) break;
                        written += n;
                    }
                } catch (SocketTimeoutException ignored) {
                }
            }
        } catch (Exception e) {
            if (mediaRunning) MediaBridgeService.setStatus("uplink error: " + safe(e));
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
            }
        }
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
