package dev.opensimbridge.bridge;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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
                    if (running) {
                        e.printStackTrace();
                    }
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

            client.setSoTimeout(3000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)
            );

            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.length() > 4096) {
                write(client, 400, "text/plain; charset=utf-8", "Bad request");
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equals(parts[0])) {
                write(client, 405, "text/plain; charset=utf-8", "GET only");
                return;
            }

            String target = parts[1];
            String path = target;
            String query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }

            Map<String, String> params = parseQuery(query);

            if ("/health".equals(path)) {
                write(client, 200, "application/json; charset=utf-8",
                        "{\"ok\":true,\"service\":\"open-sim-bridge\"}");
                return;
            }

            if ("/api/pair".equals(path)) {
                if (PairingStore.isLocked(context)) {
                    write(client, 429, "application/json; charset=utf-8",
                            "{\"error\":\"temporarily_locked\"}");
                    return;
                }

                if (!PairingStore.verify(context, params.get("code"))) {
                    write(client, 401, "application/json; charset=utf-8",
                            "{\"error\":\"invalid_pairing_code\"}");
                    return;
                }

                write(client, 200, "application/json; charset=utf-8",
                        "{\"token\":\"" + TokenStore.getOrCreate(context) + "\"}");
                return;
            }

            if ("/api/messages".equals(path)) {
                if (!TokenStore.matches(context, params.get("token"))) {
                    write(client, 401, "application/json; charset=utf-8",
                            "{\"error\":\"unauthorized\"}");
                    return;
                }
                write(client, 200, "application/json; charset=utf-8", SmsStore.asJson(context));
                return;
            }

            if ("/".equals(path)) {
                write(client, 200, "text/html; charset=utf-8", pageHtml());
                return;
            }

            write(client, 404, "text/plain; charset=utf-8", "Not found");
        } catch (Exception ignored) {
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        if (query == null || query.isEmpty()) return out;

        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                out.put(
                        URLDecoder.decode(key, "UTF-8"),
                        URLDecoder.decode(value, "UTF-8")
                );
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static void write(Socket socket, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        );

        writer.write("HTTP/1.1 " + status + " " + statusText(status) + "\r\n");
        writer.write("Content-Type: " + contentType + "\r\n");
        writer.write("Content-Length: " + bytes.length + "\r\n");
        writer.write("Cache-Control: no-store\r\n");
        writer.write("X-Content-Type-Options: nosniff\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.flush();
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
                + "body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;margin:24px;max-width:760px}"
                + "input,button{font:inherit;padding:12px;margin:4px 0;width:100%;box-sizing:border-box}"
                + ".msg{border:1px solid #7775;border-radius:12px;padding:12px;margin:10px 0}"
                + ".sender{font-weight:700}.time{opacity:.6;font-size:.85em}.warn{padding:12px;border:1px solid #b8860b;border-radius:12px}"
                + "</style></head><body>"
                + "<h2>Open SIM Bridge — M0.1</h2>"
                + "<div class=\"warn\">Prototype only. Do not use real banking OTPs until encrypted pairing (M1) is complete.</div>"
                + "<p id=\"status\">Enter the 6-digit pairing code shown on the J7 Prime.</p>"
                + "<div id=\"pairBox\">"
                + "<input id=\"code\" inputmode=\"numeric\" pattern=\"[0-9]*\" maxlength=\"6\" autocomplete=\"one-time-code\" placeholder=\"6-digit pairing code\">"
                + "<button onclick=\"pair()\">Pair</button>"
                + "</div>"
                + "<button id=\"forget\" onclick=\"forgetPairing()\" style=\"display:none\">Forget pairing</button>"
                + "<div id=\"messages\"></div>"
                + "<script>"
                + "let timer=null;"
                + "function esc(s){return String(s).replace(/[&<>\\\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\\\"':'&quot;'}[c]));}"
                + "function setConnected(v){"
                + " document.getElementById('pairBox').style.display=v?'none':'block';"
                + " document.getElementById('forget').style.display=v?'block':'none';"
                + " document.getElementById('status').textContent=v?'Connected locally':'Pairing required';"
                + "}"
                + "async function pair(){"
                + " const c=document.getElementById('code').value.trim();"
                + " if(!/^\\d{6}$/.test(c)){document.getElementById('status').textContent='Enter 6 digits';return;}"
                + " try{const r=await fetch('/api/pair?code='+encodeURIComponent(c),{cache:'no-store'});"
                + " if(r.status===429){document.getElementById('status').textContent='Too many attempts. Wait 30 seconds.';return;}"
                + " if(!r.ok){document.getElementById('status').textContent='Pairing code is incorrect or expired';return;}"
                + " const d=await r.json();localStorage.setItem('osb_token',d.token);setConnected(true);startPolling();"
                + " }catch(e){document.getElementById('status').textContent='Cannot reach local bridge';}"
                + "}"
                + "function startPolling(){if(timer)clearInterval(timer);load();timer=setInterval(load,1000);}"
                + "function forgetPairing(){localStorage.removeItem('osb_token');if(timer)clearInterval(timer);timer=null;document.getElementById('messages').innerHTML='';setConnected(false);}"
                + "async function load(){"
                + " const t=localStorage.getItem('osb_token');if(!t){setConnected(false);return;}"
                + " try{const r=await fetch('/api/messages?token='+encodeURIComponent(t),{cache:'no-store'});"
                + " if(r.status===401){localStorage.removeItem('osb_token');setConnected(false);return;}"
                + " if(!r.ok)return;"
                + " const d=await r.json();setConnected(true);"
                + " document.getElementById('messages').innerHTML=(d.messages||[]).map(m=>"
                + " '<div class=\"msg\"><div class=\"sender\">'+esc(m.sender)+'</div><div>'+esc(m.body)+'</div><div class=\"time\">'+new Date(m.received_at_ms).toLocaleString()+'</div></div>'"
                + " ).join('');"
                + " }catch(e){document.getElementById('status').textContent='Waiting for local bridge…';}"
                + "}"
                + "if(localStorage.getItem('osb_token')){setConnected(true);startPolling();}else{setConnected(false);}"
                + "</script></body></html>";
    }
}
