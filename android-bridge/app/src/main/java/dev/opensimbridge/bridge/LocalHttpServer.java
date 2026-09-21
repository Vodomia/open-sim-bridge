package dev.opensimbridge.bridge;

import android.content.Context;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

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

            if ("GET".equals(request.method) && "/api/call/state".equals(request.path)) {
                write(client, 200, "application/json; charset=utf-8",
                        CallStateStore.asJson());
                return;
            }

            if ("GET".equals(request.method) && "/api/capabilities".equals(request.path)) {
                JSONObject obj = new JSONObject();
                try {
                    obj.put("sdk_int", Build.VERSION.SDK_INT);
                    obj.put("model", Build.MODEL);
                    obj.put("manufacturer", Build.MANUFACTURER);
                } catch (JSONException ignored) {
                }
                write(client, 200, "application/json; charset=utf-8", obj.toString());
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
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 429: return "Too Many Requests";
            default: return "Error";
        }
    }

    private static String pageHtml() {
        return "<!doctype html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"color-scheme\" content=\"light dark\">"
                + "<title>Open SIM Bridge</title>"
                + "<style>body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;margin:20px;max-width:760px}"
                + "input,button{font:inherit;padding:12px;margin:5px 0;width:100%;box-sizing:border-box}"
                + ".card,.msg{border:1px solid #7775;border-radius:14px;padding:14px;margin:12px 0}"
                + ".sender{font-weight:700}.time,.muted{opacity:.65;font-size:.88em}.hidden{display:none}</style>"
                + "</head><body><h2>Open SIM Bridge — Call State Probe</h2>"
                + "<p id=\"status\">Pairing required</p>"
                + "<div id=\"pairBox\" class=\"card\"><input id=\"code\" inputmode=\"numeric\" maxlength=\"6\" placeholder=\"6-digit pairing code\"><button onclick=\"pair()\">Pair</button></div>"
                + "<div id=\"controls\" class=\"hidden\">"
                + "<div class=\"card\"><b>Device</b><div id=\"device\" class=\"muted\">Loading…</div></div>"
                + "<div class=\"card\"><b>Call state</b><div id=\"callState\">UNKNOWN</div><div id=\"caller\" class=\"muted\"></div></div>"
                + "<h3>Incoming SMS</h3><div id=\"messages\"></div></div>"
                + "<script>"
                + "let timer=null;function token(){return localStorage.getItem('osb_token')||'';}"
                + "function esc(s){return String(s||'').replace(/[&<>\"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c];});}"
                + "function setConnected(v){pairBox.className=v?'hidden':'card';controls.className=v?'':'hidden';status.textContent=v?'Connected locally':'Pairing required';}"
                + "async function pair(){const c=code.value.trim();if(!/^\\d{6}$/.test(c)){status.textContent='Enter 6 digits';return;}const r=await fetch('/api/pair?code='+encodeURIComponent(c),{cache:'no-store'});if(!r.ok){status.textContent='Pairing failed';return;}const d=await r.json();localStorage.setItem('osb_token',d.token);setConnected(true);start();}"
                + "async function get(path){const r=await fetch(path+'?token='+encodeURIComponent(token()),{cache:'no-store'});if(r.status===401){localStorage.removeItem('osb_token');setConnected(false);throw new Error('unauthorized');}return r.json();}"
                + "async function load(){try{const c=await get('/api/call/state');callState.textContent=c.state||'UNKNOWN';caller.textContent=c.number?('Number: '+c.number):'';const m=await get('/api/messages');messages.innerHTML=(m.messages||[]).map(x=>'<div class=\"msg\"><div class=\"sender\">'+esc(x.sender)+'</div><div>'+esc(x.body)+'</div><div class=\"time\">'+new Date(x.received_at_ms).toLocaleString()+'</div></div>').join('');}catch(e){}}"
                + "async function caps(){try{const d=await get('/api/capabilities');device.textContent=d.manufacturer+' '+d.model+' · Android API '+d.sdk_int;}catch(e){}}"
                + "function start(){if(timer)clearInterval(timer);caps();load();timer=setInterval(load,700);}"
                + "if(token()){setConnected(true);start();}else setConnected(false);"
                + "</script></body></html>";
    }
}
