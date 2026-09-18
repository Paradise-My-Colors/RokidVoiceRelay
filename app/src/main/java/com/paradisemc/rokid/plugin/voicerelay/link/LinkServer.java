package com.paradisemc.rokid.plugin.voicerelay.link;

import org.json.JSONObject;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.modes.GCMSIVBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Local HTTP transports only authenticated AES-GCM-SIV envelopes, never message plaintext.
 * The master key is delivered out of band in the phone-generated AIUI setup ZIP.
 * Every connection derives a fresh key from a phone-generated 256-bit salt.
 */
public final class LinkServer implements Closeable {
    public interface Commands { JSONObject execute(JSONObject request) throws Exception; }
    private static final int MAX_BODY = 128 * 1024;
    private static final long CHALLENGE_MS = 30_000, SESSION_MS = 10 * 60_000;
    private final byte[] master;
    private final int requestedPort;
    private final Commands commands;
    private final Consumer<String> state;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> challenges = new LinkedHashMap<>();
    private final ArrayDeque<Long> attempts = new ArrayDeque<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Object commandLock = new Object();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(8), r -> { Thread t = new Thread(r, "voice-link-http"); t.setDaemon(true); return t; });
    private ServerSocket listener;
    private volatile boolean running;
    private Session active;
    private static final class Session {
        final String sid, salt; final byte[] key;
        final long created = System.nanoTime();
        long touched = created, seq;
        Session(String sid, String salt, byte[] key) { this.sid = sid; this.salt = salt; this.key = key; }
    }
    public LinkServer(byte[] master, int port, Commands commands, Consumer<String> state) {
        if (master.length != 32) throw new IllegalArgumentException("Invalid link key");
        this.master = master.clone(); this.requestedPort = port; this.commands = commands; this.state = state;
    }
    public void start() throws IOException {
        listener = new ServerSocket(); listener.setReuseAddress(true); listener.bind(new InetSocketAddress(requestedPort)); running = true;
        Thread accept = new Thread(() -> {
            while (running) try {
                Socket socket = listener.accept();
                InetAddress address = socket.getInetAddress();
                if (!address.isLoopbackAddress() && !address.isSiteLocalAddress()) { socket.close(); continue; }
                sockets.add(socket);
                try { workers.execute(() -> serve(socket)); }
                catch (RejectedExecutionException e) { sockets.remove(socket); socket.close(); }
            } catch (IOException e) { if (running) state.accept("Network link stopped. Tap Start phone link."); }
        }, "voice-link-listener"); accept.setDaemon(true); accept.start();
        state.accept("Phone link ready · LINK 1.1.0");
    }
    public int port() { return listener.getLocalPort(); }
    private String randomHex(int length) { byte[] b = new byte[length]; random.nextBytes(b); return hex(b); }
    private static long age(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private synchronized JSONObject challenge() throws Exception {
        long now = System.nanoTime();
        while (!attempts.isEmpty() && age(attempts.peek()) > 60_000) attempts.remove();
        if (attempts.size() >= 40) throw new HttpError(429);
        attempts.add(now);
        challenges.values().removeIf(s -> age(s.created) > CHALLENGE_MS);
        while (challenges.size() >= 16) challenges.remove(challenges.keySet().iterator().next());
        String sid = randomHex(16), salt = randomHex(32);
        Session s = new Session(sid, salt, derive(master, salt)); challenges.put(sid, s);
        return new JSONObject().put("protocol", 1).put("sid", sid).put("salt", salt);
    }
    public static byte[] derive(byte[] master, String salt) throws Exception {
        if (master.length != 32 || !salt.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid link material");
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(master, "HmacSHA256"));
        return mac.doFinal(("voice-relay-link-v1:" + salt).getBytes(StandardCharsets.UTF_8));
    }
    public static byte[] crypt(boolean encrypt, byte[] key, String sid, long seq, boolean response, byte[] input) throws Exception {
        if (!sid.matches("[a-f0-9]{32}") || seq < 1 || seq > 0xffffffffL) throw new IllegalArgumentException("Invalid envelope");
        byte[] nonce = new byte[12]; nonce[0] = (byte)(response ? 1 : 0); ByteBuffer.wrap(nonce).putLong(4, seq);
        byte[] aad = ("voice-relay-link-v1:" + sid + ":" + seq + ":" + (response ? "response" : "request")).getBytes(StandardCharsets.UTF_8);
        GCMSIVBlockCipher cipher = new GCMSIVBlockCipher();
        cipher.init(encrypt, new AEADParameters(new KeyParameter(key), 128, nonce, aad));
        byte[] output = new byte[cipher.getOutputSize(input.length)];
        int count = cipher.processBytes(input, 0, input.length, output, 0); count += cipher.doFinal(output, count);
        return Arrays.copyOf(output, count);
    }
    private JSONObject request(String path, JSONObject envelope) throws Exception {
        String sid = envelope.optString("sid"); long seq = envelope.optLong("seq", -1);
        if (!sid.matches("[a-f0-9]{32}") || seq < 1 || seq > 0xffffffffL) throw new HttpError(401);
        Session session; JSONObject query;
        synchronized (this) {
            boolean opening = path.equals("/v1/open");
            session = opening ? challenges.get(sid) : active;
            if (session == null || !session.sid.equals(sid) || age(session.touched) > SESSION_MS ||
                (opening && (age(session.created) > CHALLENGE_MS || seq != 1)) || (!opening && seq != session.seq + 1)) throw new HttpError(401);
            try {
                query = new JSONObject(new String(crypt(false, session.key, sid, seq, false, Base64.getDecoder().decode(envelope.getString("box"))), StandardCharsets.UTF_8));
            } catch (Exception e) { throw new HttpError(401); }
            if (opening && !"hello".equals(query.optString("op"))) throw new HttpError(401);
            session.seq = seq; session.touched = System.nanoTime();
            if (opening) { challenges.remove(sid); active = session; }
        }
        JSONObject answer;
        if (path.equals("/v1/open")) {
            answer = new JSONObject().put("ok", true).put("value", new JSONObject().put("protocol", 1).put("build", "LINK 1.1.0"));
            state.accept("Private link confirmed. Waiting for Inbox…");
        } else {
            synchronized (commandLock) {
                synchronized (this) { if (active != session || !running) throw new HttpError(401); }
                try { answer = commands.execute(query); }
                catch (Exception e) { answer = new JSONObject().put("ok", false).put("error", "Phone operation failed. Check the phone and delivery receipt."); }
                if ("inbox".equals(query.optString("op")) && answer.optBoolean("ok")) state.accept("Connected through relay · Inbox ready · LINK 1.1.0");
            }
        }
        answer.put("requestHash", hex(MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(envelope.getString("box")))));
        byte[] encrypted = crypt(true, session.key, sid, seq, true, answer.toString().getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("sid", sid).put("seq", seq).put("box", Base64.getEncoder().encodeToString(encrypted));
    }
    private static final class HttpError extends Exception { final int status; HttpError(int status) { this.status = status; } }
    private void serve(Socket socket) {
        try (Socket owned = socket) {
            socket.setSoTimeout(8000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            int status = 200; String result;
            try {
                String line = line(in, 2048); String[] first = line.split(" ");
                if (first.length != 3 || !first[2].equals("HTTP/1.1")) throw new HttpError(400);
                String method = first[0], path = first[1];
                int length = 0, total = line.length(); boolean hasLength = false;
                while (!(line = line(in, 4096)).isEmpty()) {
                    total += line.length(); if (total > 8192) throw new HttpError(431);
                    int colon = line.indexOf(':'); if (colon < 1) throw new HttpError(400);
                    String key = line.substring(0, colon).toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
                    if (key.equals("transfer-encoding")) throw new HttpError(400);
                    if (key.equals("content-length")) {
                        if (hasLength) throw new HttpError(400); hasLength = true;
                        try { length = Integer.parseInt(value); } catch (NumberFormatException e) { throw new HttpError(400); }
                        if (length < 0 || length > MAX_BODY) throw new HttpError(413);
                    }
                }
                if (method.equals("GET") && path.equals("/v1/ping")) result = "{\"app\":\"VoiceRelayLink\",\"protocol\":1,\"build\":\"LINK 1.1.0\"}";
                else if (method.equals("GET") && path.equals("/v1/challenge")) result = challenge().toString();
                else if (method.equals("POST") && (path.equals("/v1/open") || path.equals("/v1/call"))) {
                    if (!hasLength || length == 0) throw new HttpError(400);
                    byte[] body = new byte[length]; int pos = 0;
                    while (pos < length) { int n = in.read(body, pos, length - pos); if (n < 0) throw new HttpError(400); pos += n; }
                    JSONObject envelope; try { envelope = new JSONObject(new String(body, StandardCharsets.UTF_8)); } catch (Exception e) { throw new HttpError(400); }
                    result = request(path, envelope).toString();
                } else throw new HttpError(404);
            } catch (HttpError e) { status = e.status; result = "{\"error\":\"Phone link request rejected\"}"; }
            catch (Exception e) { status = 500; result = "{\"error\":\"Phone link unavailable\"}"; }
            byte[] body = result.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 " + status + " Result\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body); out.flush();
        } catch (IOException ignored) {} finally { sockets.remove(socket); }
    }
    private static String line(InputStream in, int max) throws IOException, HttpError {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); int value;
        while ((value = in.read()) >= 0) {
            if (value == '\n') { String text = b.toString("US-ASCII"); if (!text.endsWith("\r")) throw new HttpError(400); return text.substring(0, text.length() - 1); }
            if (b.size() >= max) throw new HttpError(431); b.write(value);
        }
        throw new EOFException();
    }
    public static String hex(byte[] value) { StringBuilder b = new StringBuilder(); for (byte v : value) b.append(String.format(Locale.ROOT, "%02x", v & 255)); return b.toString(); }
    public static byte[] unhex(String value) {
        if (!value.matches("(?:[a-f0-9]{2})+")) throw new IllegalArgumentException("Invalid key");
        byte[] out = new byte[value.length() / 2]; for (int i = 0; i < out.length; i++) out[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16); return out;
    }
    @Override public void close() {
        running = false; synchronized (this) { active = null; challenges.clear(); }
        if (listener != null) try { listener.close(); } catch (IOException ignored) {}
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {}
        workers.shutdownNow(); Arrays.fill(master, (byte)0);
    }
}
