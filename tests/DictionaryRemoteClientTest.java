package com.yuen.jpdict;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** No sockets and no real credential: verify the allowlist and transport bounds. */
public final class DictionaryRemoteClientTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static final String ORIGIN = "https://test.dict.invalid";
    private static final String NOTE = DictionaryRemoteClient.NOTE_URL + "?language=en&word=father";
    private static final Map<String, String> AUTH = Map.of("Authorization", "NIS test-only", "Origin", ORIGIN,
            "Cookie", "must-not-forward", "X-Other", "must-not-forward");
    static final class FakeConnection extends HttpURLConnection {
        byte[] bytes = "{\"data\":{\"word\":\"father\",\"note\":\"original QA note\"}}".getBytes(StandardCharsets.UTF_8);
        int status = 200, declared = -1, reads;
        boolean disconnected;
        String type = "application/json; charset=utf-8";
        FakeConnection(URL url) { super(url); }
        public void connect() { }
        public void disconnect() { disconnected = true; }
        public boolean usingProxy() { return false; }
        public int getResponseCode() { return status; }
        public String getContentType() { return type; }
        public int getContentLength() { return declared; }
        public InputStream getInputStream() { reads++; return new ByteArrayInputStream(bytes); }
    }
    public static void main(String[] arguments) throws Exception {
        List<FakeConnection> opened = new ArrayList<>();
        DictionaryRemoteClient client = new DictionaryRemoteClient(ORIGIN, url -> {
            FakeConnection connection = new FakeConnection(url); opened.add(connection); return connection;
        });
        String[] blocked = {
                "http://api.frdic.com/api/open/v1/studylist/note?language=en&word=father",
                NOTE.replace("api.frdic.com", "api.frdic.com.evil.example"),
                NOTE.replace("api.frdic.com", "evil.example@api.frdic.com"),
                NOTE.replace("api.frdic.com", "api.frdic.com:443"),
                NOTE.replace("/note?", "/note/extra?"),
                NOTE.replace("/note?", "/%6Eote?"),
                NOTE + "#fragment", NOTE + "&key=private", NOTE + "&word=other",
                NOTE.replace("language=en", "language=ja"), NOTE.replace("father", "%0Aprivate"),
                NOTE.replace("father", "%"), NOTE.replace("father", ""),
                NOTE.replace("father", "x".repeat(513)),
                "https://cdn.jsdelivr.net/npm/eruda/extra", DictionaryRemoteClient.DEBUG_URL + "?version=latest",
                "https://example.com/script.js", "file:///data/private"
        };
        for (String url : blocked) check(client.request("GET", url, AUTH) == null, "Reject non-allowlisted request");
        for (String method : new String[]{"POST", "PUT", "DELETE", "PATCH", "HEAD"})
            check(client.request(method, NOTE, AUTH) == null, "Reject write method " + method);
        check(client.request("GET", NOTE, Map.of("Origin", "https://other.dict.invalid", "Authorization", "NIS QA")) == null,
                "Reject cross-dictionary initiating origin");
        check(opened.isEmpty(), "Rejected requests open no connection");
        DictionaryRemoteClient.Response preflight = client.request("OPTIONS", NOTE,
                Map.of("Origin", ORIGIN, "Access-Control-Request-Method", "GET", "Access-Control-Request-Headers", "authorization, content-type"));
        check(preflight.status == 204 && opened.isEmpty(), "CORS preflight does not contact upstream");
        check(preflight.headers.get("Access-Control-Allow-Origin").equals(ORIGIN)
                && !preflight.headers.containsKey("Access-Control-Allow-Credentials"), "CORS is scoped to the dictionary origin without cookies");
        check(client.request("OPTIONS", NOTE, Map.of("Access-Control-Request-Method", "POST")) == null,
                "Preflight cannot authorize writes");
        check(client.request("OPTIONS", NOTE, Map.of("Access-Control-Request-Method", "GET", "Access-Control-Request-Headers", "cookie")) == null,
                "Preflight cannot authorize arbitrary headers");
        check(client.request("GET", NOTE, Collections.emptyMap()).status == 401 && opened.isEmpty(), "Missing token fails locally");
        DictionaryRemoteClient.Response note = client.request("GET", NOTE, AUTH);
        FakeConnection request = opened.get(0);
        check(note.status == 200 && new String(note.body, StandardCharsets.UTF_8).contains("father"), "Original note JSON is returned");
        check(request.getRequestProperty("Authorization").equals("NIS test-only")
                && request.getRequestProperty("Cookie") == null && request.getRequestProperty("X-Other") == null,
                "Only the script-provided token is forwarded");
        check(!request.getInstanceFollowRedirects() && !request.getUseCaches() && request.getRequestMethod().equals("GET")
                && request.getConnectTimeout() <= 8000 && request.getReadTimeout() <= 8000 && request.disconnected,
                "Native transport is bounded, read-only, uncached and disconnected");
        check(DictionaryRemoteClient.noteUrl(DictionaryRemoteClient.NOTE_URL + "?word=rock%26roll&language=en").endsWith("word=rock%26roll"),
                "Canonical query preserves an encoded ampersand inside the word");
        for (String language : new String[]{"en", "fr", "de", "es"})
            check(DictionaryRemoteClient.noteUrl(NOTE.replace("language=en", "language=" + language)) != null, "Supported language " + language);
        failure(302, null, null, 502, "Redirect rejected without following");
        failure(401, null, null, 401, "Authorization failure is distinct from network failure");
        failure(403, null, null, 403, "Rate limiting is retained");
        failure(200, MAX(), null, 502, "Declared oversized response rejected");
        failure(200, null, new byte[MAX()], 502, "Streaming oversized response rejected");
        DictionaryRemoteClient html = new DictionaryRemoteClient(ORIGIN, url -> {
            FakeConnection connection = new FakeConnection(url); connection.type = "text/html"; return connection;
        });
        check(html.request("GET", NOTE, AUTH).status == 502, "Unexpected upstream HTML cannot enter the dictionary");
        check(!DictionaryRemoteClient.validDebugScript("window.eruda={};".getBytes(StandardCharsets.UTF_8)), "Modified debug script fails integrity");
        int beforeDebug = opened.size();
        check(client.request("GET", DictionaryRemoteClient.DEBUG_URL, AUTH).status == 502, "Both debug sources must satisfy the fixed hash");
        check(opened.size() == beforeDebug + 2 && opened.subList(beforeDebug, opened.size()).stream()
                .allMatch(c -> c.getRequestProperty("Authorization") == null), "Debug download never receives a dictionary token");
        client.close(); int beforeClosed = opened.size();
        check(client.request("GET", NOTE, AUTH).status == 410 && opened.size() == beforeClosed, "Closed view opens no new request");
        if (arguments.length > 0) {
            byte[] official = java.nio.file.Files.readAllBytes(new File(arguments[0]).toPath());
            check(DictionaryRemoteClient.validDebugScript(official), "Downloaded official pinned release matches the fixed hash");
            official[0] ^= 1;
            check(!DictionaryRemoteClient.validDebugScript(official), "One-byte artifact corruption is rejected");
        }
        System.out.println("Dictionary remote policy: " + checks + " assertions passed");
    }
    private static int MAX() { return DictionaryRemoteClient.MAX_BYTES + 1; }
    private static void failure(int status, Integer declared, byte[] body, int expected, String label) {
        List<FakeConnection> opened = new ArrayList<>();
        DictionaryRemoteClient client = new DictionaryRemoteClient(ORIGIN, url -> {
            FakeConnection connection = new FakeConnection(url); connection.status = status;
            if (declared != null) connection.declared = declared;
            if (body != null) connection.bytes = body;
            opened.add(connection); return connection;
        });
        check(client.request("GET", NOTE, AUTH).status == expected && opened.size() == 1 && opened.get(0).disconnected, label);
        client.close();
    }
}
