package dev.opensimbridge.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class SimpleHttpRequest {
    final String method;
    final String path;
    final Map<String, String> params;

    private SimpleHttpRequest(String method, String path, Map<String, String> params) {
        this.method = method;
        this.path = path;
        this.params = params;
    }

    static SimpleHttpRequest read(BufferedReader reader) throws IOException {
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.length() > 4096) {
            throw new IOException("Bad request line");
        }

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) throw new IOException("Malformed request");

        String method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];

        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }

        int contentLength = 0;
        String contentType = "";

        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();

            if ("content-length".equals(key)) {
                try {
                    contentLength = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }
            } else if ("content-type".equals(key)) {
                contentType = value.toLowerCase(Locale.US);
            }
        }

        if (contentLength < 0 || contentLength > 16384) {
            throw new IOException("Request body too large");
        }

        String body = "";
        if (contentLength > 0) {
            char[] buffer = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = reader.read(buffer, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }
            body = new String(buffer, 0, read);
        }

        Map<String, String> params = parseForm(query);
        if ("POST".equals(method)
                && contentType.startsWith("application/x-www-form-urlencoded")) {
            params.putAll(parseForm(body));
        }

        return new SimpleHttpRequest(method, path, params);
    }

    private static Map<String, String> parseForm(String value) {
        Map<String, String> out = new HashMap<>();
        if (value == null || value.isEmpty()) return out;

        for (String pair : value.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String item = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                out.put(
                        URLDecoder.decode(key, "UTF-8"),
                        URLDecoder.decode(item, "UTF-8")
                );
            } catch (Exception ignored) {
            }
        }
        return out;
    }
}
