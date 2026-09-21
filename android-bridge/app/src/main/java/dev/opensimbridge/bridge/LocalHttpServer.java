package dev.opensimbridge.bridge;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class LocalHttpServer {
    static final int PORT = 8787;

    private final Context context;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;

    LocalHttpServer(Context context) {
        this.context = context.getApplicationContext();
    }

    void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(PORT);
        running = true;

        Thread acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    pool.execute(() -> handle(socket));
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }, "osb-http-accept");
        acceptThread.start();
    }

    void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            InetAddress remote = client.getInetAddress();
            if (!(remote.isLoopbackAddress() || remote.isSiteLocalAddress())) {
                write(client, 403, "text/plain; charset=utf-8", "Local network only");
                return;
            }

            client.setSoTimeout(4000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)
            );

            SimpleHttpRequest request = SimpleHttpRequest.read(reader);

            if ("GET".equals(request.method) && "/health".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        "{\"ok\":true,\"service\":\"open-sim-bridge\"}");
                return;
            }

            if ("GET".equals(request.method) && "/api/pair".equals(request.path)) {
                if (PairingStore.isLocked(context)) {
                    write(client, 429, "application/json; charset=utf-8",
                            "{\"error\":\"temporarily_locked\"}");
                    return;
                }

                if (!PairingStore.verify(context, request.params.get("code"))) {
                    write(client, 401, "application/json; charset=utf-8",
                            "{\"error\":\"invalid_pairing_code\"}");
                    return;
                }

                write(client, 200, "application/json; charset=utf-8",
                        "{\"token\":\"" + TokenStore.getOrCreate(context) + "\"}");
                return;
            }

            if ("GET".equals(request.method) && "/".equals(request.path)) {
                write(client, 200, "text/html; charset=utf-8", pageHtml());
                return;
            }

            if (!TokenStore.matches(context, request.params.get("token"))) {
                write(client, 401, "application/json; charset=utf-8",
                        "{\"error\":\"unauthorized\"}");
                return;
            }

            if ("GET".equals(request.method) && "/api/messages".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        SmsStore.asJson(context));
                return;
            }

            if ("GET".equals(request.method) && "/api/capabilities".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        TelephonyController.capabilities(context));
                return;
            }

            if ("GET".equals(request.method) && "/api/call/state".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        CallStateStore.asJson());
                return;
            }

            if ("POST".equals(request.method) && "/api/sms/send".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        TelephonyController.sendSms(
                                context,
                                request.params.get("to"),
                                request.params.get("body")
                        ));
                return;
            }

            if ("POST".equals(request.method) && "/api/call/dial".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        TelephonyController.dial(context, request.params.get("number")));
                return;
            }

            if ("POST".equals(request.method) && "/api/call/answer".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        TelephonyController.answer(context));
                return;
            }

            if ("POST".equals(request.method) && "/api/call/end".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        TelephonyController.end(context));
                return;
            }

            if ("GET".equals(request.method) && "/api/audio/status".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        AudioProbe.status(context));
                return;
            }

            if ("POST".equals(request.method) && "/api/audio/route".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        AudioProbe.route("speaker".equals(request.params.get("target"))));
                return;
            }

            if ("POST".equals(request.method) && "/api/audio/capture-probe".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        AudioProbe.capture(context, request.params.get("source")));
                return;
            }

            if ("POST".equals(request.method) && "/api/audio/full-scan".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        AudioProbe.fullCaptureScan(context));
                return;
            }

            if ("POST".equals(request.method) && "/api/audio/tone".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        AudioProbe.playTone(request.params.get("stream")));
                return;
            }

            write(client, 404, "application/json; charset=utf-8",
                    "{\"error\":\"not_found\"}");
        } catch (Exception ignored) {
        }
    }

    private static void write(Socket socket, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers =
                "HTTP/1.1 " + status + " " + statusText(status) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 429: return "Too Many Requests";
            default: return "Error";
        }
    }

    private static String pageHtml() {
        return "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"light dark\">"
                + "<title>Open SIM Bridge</title>"
                + "<style>"
                + "body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;margin:20px;max-width:760px}"
                + "input,textarea,button{font:inherit;padding:12px;margin:5px 0;width:100%;box-sizing:border-box}"
                + "button{min-height:46px}.row{display:flex;gap:8px}.row button{flex:1}"
                + ".card,.msg{border:1px solid #7775;border-radius:14px;padding:14px;margin:12px 0}"
                + ".sender{font-weight:700}.time,.muted{opacity:.65;font-size:.88em}"
                + ".warn{padding:12px;border:1px solid #b8860b;border-radius:12px}"
                + ".ok{font-weight:700}.hidden{display:none}"
                + "</style></head><body>"
                + "<h2>Open SIM Bridge — Telephony Prototype</h2>"
                + "<div class=\"warn\">Use harmless test SMS and calls only. M1 encryption is not finished yet.</div>"
                + "<p id=\"status\">Pairing required</p>"
                + "<div id=\"pairBox\" class=\"card\">"
                + "<input id=\"code\" inputmode=\"numeric\" pattern=\"[0-9]*\" maxlength=\"6\" placeholder=\"6-digit pairing code\">"
                + "<button onclick=\"pair()\">Pair</button></div>"
                + "<div id=\"controls\" class=\"hidden\">"
                + "<div class=\"card\"><b>Device</b><div id=\"caps\" class=\"muted\">Loading capabilities…</div></div>"
                + "<div class=\"card\"><b>Call state</b><div id=\"callState\" class=\"ok\">UNKNOWN</div>"
                + "<div id=\"caller\" class=\"muted\"></div>"
                + "<input id=\"dialNumber\" inputmode=\"tel\" placeholder=\"Phone number\">"
                + "<button onclick=\"dial()\">Dial</button>"
                + "<div class=\"row\"><button onclick=\"answer()\">Answer</button><button onclick=\"endCall()\">End / reject</button></div>"
                + "<div id=\"callResult\" class=\"muted\"></div></div>"
                + "<div class=\"card\"><b>Send SMS</b>"
                + "<input id=\"smsTo\" inputmode=\"tel\" placeholder=\"Phone number\">"
                + "<textarea id=\"smsBody\" rows=\"3\" placeholder=\"Test message\"></textarea>"
                + "<button onclick=\"sendSms()\">Send SMS</button>"
                + "<div id=\"smsResult\" class=\"muted\"></div></div>"
                + "<div class=\"card\"><b>M4 Audio Lab</b>"
                + "<div class=\"muted\">Use only during a harmless test call. Keep the other phone nearby so you can confirm what it hears.</div>"
                + "<div id=\"audioStatus\" class=\"muted\">Audio status not loaded</div>"
                + "<div class=\"row\"><button onclick=\"routeAudio('speaker')\">Route speaker</button><button onclick=\"routeAudio('earpiece')\">Route earpiece</button></div>"
                + "<select id=\"audioSource\" style=\"font:inherit;padding:12px;margin:5px 0;width:100%;box-sizing:border-box\">"
                + "<option value=\"mic\">MIC</option><option value=\"voice_communication\">VOICE_COMMUNICATION</option>"
                + "<option value=\"voice_recognition\">VOICE_RECOGNITION</option><option value=\"unprocessed\">UNPROCESSED</option>"
                + "<option value=\"voice_call\">VOICE_CALL (expected restricted)</option>"
                + "<option value=\"voice_downlink\">VOICE_DOWNLINK (expected restricted)</option>"
                + "<option value=\"voice_uplink\">VOICE_UPLINK (expected restricted)</option></select>"
                + "<button onclick=\"captureProbe()\">Capture 0.9s audio probe</button>"
                + "<button onclick=\"fullAudioScan()\">Run full audio scan</button>"
                + "<div class=\"row\"><button onclick=\"playTone('music')\">Play low test tone (music)</button><button onclick=\"playTone('voice_call')\">Play low test tone (voice-call stream)</button></div>"
                + "<div id=\"audioResult\" class=\"muted\"></div></div>"
                + "<button onclick=\"forgetPairing()\">Forget pairing</button>"
                + "<h3>Incoming SMS</h3><div id=\"messages\"></div>"
                + "</div>"
                + "<script>"
                + "let timer=null;"
                + "function esc(s){return String(s||'').replace(/[&<>\"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c];});}"
                + "function token(){return localStorage.getItem('osb_token')||'';}"
                + "function setConnected(v){document.getElementById('pairBox').className=v?'hidden':'card';document.getElementById('controls').className=v?'':'hidden';document.getElementById('status').textContent=v?'Connected locally':'Pairing required';}"
                + "async function pair(){const c=document.getElementById('code').value.trim();if(!/^\\d{6}$/.test(c)){status.textContent='Enter 6 digits';return;}try{const r=await fetch('/api/pair?code='+encodeURIComponent(c),{cache:'no-store'});if(r.status===429){status.textContent='Wait 30 seconds and retry';return;}if(!r.ok){status.textContent='Pairing failed';return;}const d=await r.json();localStorage.setItem('osb_token',d.token);setConnected(true);startPolling();loadCapabilities();}catch(e){status.textContent='Cannot reach bridge';}}"
                + "async function apiGet(path){const r=await fetch(path+'?token='+encodeURIComponent(token()),{cache:'no-store'});if(r.status===401){forgetPairing();throw new Error('unauthorized');}return r.json();}"
                + "async function apiPost(path,data){data.token=token();const body=new URLSearchParams(data);const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body.toString(),cache:'no-store'});if(r.status===401){forgetPairing();throw new Error('unauthorized');}return r.json();}"
                + "async function loadCapabilities(){try{const d=await apiGet('/api/capabilities');caps.textContent='Android API '+d.sdk_int+' · SMS '+(d.send_sms?'OK':'NO')+' · Call '+(d.call_phone?'OK':'NO')+' · Answer '+((d.incall_active||(d.public_answer_api&&d.answer_phone_calls))?'OK':'NO')+' · Default dialer '+(d.default_dialer?'YES':'NO')+' · End/Reject '+(d.incall_end_supported?'OK':'NO');}catch(e){}}"
                + "async function load(){try{const m=await apiGet('/api/messages');messages.innerHTML=(m.messages||[]).map(x=>'<div class=\"msg\"><div class=\"sender\">'+esc(x.sender)+'</div><div>'+esc(x.body)+'</div><div class=\"time\">'+new Date(x.received_at_ms).toLocaleString()+'</div></div>').join('');const c=await apiGet('/api/call/state');callState.textContent=c.state||'UNKNOWN';caller.textContent=c.number?('Number: '+c.number):'';}catch(e){}}"
                + "function startPolling(){if(timer)clearInterval(timer);load();timer=setInterval(load,1000);}"
                + "async function sendSms(){smsResult.textContent='Sending…';try{const d=await apiPost('/api/sms/send',{to:smsTo.value.trim(),body:smsBody.value});smsResult.textContent=d.code+': '+d.message;}catch(e){smsResult.textContent='Request failed';}}"
                + "async function dial(){callResult.textContent='Dialing…';try{const d=await apiPost('/api/call/dial',{number:dialNumber.value.trim()});callResult.textContent=d.code+': '+d.message;}catch(e){callResult.textContent='Request failed';}}"
                + "async function answer(){try{const d=await apiPost('/api/call/answer',{});callResult.textContent=d.code+': '+d.message;}catch(e){callResult.textContent='Request failed';}}"
                + "async function endCall(){try{const d=await apiPost('/api/call/end',{});callResult.textContent=d.code+': '+d.message;}catch(e){callResult.textContent='Request failed';}}"
                + "async function refreshAudio(){try{const d=await apiGet('/api/audio/status');audioStatus.textContent='Mode '+d.mode_name+' · speaker '+(d.speakerphone?'ON':'OFF')+' · InCallService '+(d.incall_service_active?'active':'inactive')+' · RECORD_AUDIO '+(d.record_audio?'OK':'NO');}catch(e){audioStatus.textContent='Audio status failed';}}"
                + "async function routeAudio(target){audioResult.textContent='Routing…';try{const d=await apiPost('/api/audio/route',{target:target});audioResult.textContent=JSON.stringify(d);refreshAudio();}catch(e){audioResult.textContent='Route request failed';}}"
                + "async function captureProbe(){audioResult.textContent='Listening for 0.9s… have the other side speak now';try{const d=await apiPost('/api/audio/capture-probe',{source:audioSource.value});audioResult.textContent=JSON.stringify(d);refreshAudio();}catch(e){audioResult.textContent='Capture probe failed';}}"
                + "async function fullAudioScan(){audioResult.textContent='Scanning all audio inputs… keep speaker OFF, stay quiet, and have the other phone speak continuously for a few seconds';try{const d=await apiPost('/api/audio/full-scan',{});audioResult.textContent=JSON.stringify(d);refreshAudio();}catch(e){audioResult.textContent='Full audio scan failed';}}"
                + "async function playTone(stream){audioResult.textContent='Starting low-level 700 Hz tone…';try{const d=await apiPost('/api/audio/tone',{stream:stream});audioResult.textContent=JSON.stringify(d);}catch(e){audioResult.textContent='Tone test failed';}}"
                + "function forgetPairing(){localStorage.removeItem('osb_token');if(timer)clearInterval(timer);timer=null;setConnected(false);}"
                + "if(token()){setConnected(true);startPolling();loadCapabilities();refreshAudio();}else{setConnected(false);}"
                + "</script></body></html>";
    }
}
