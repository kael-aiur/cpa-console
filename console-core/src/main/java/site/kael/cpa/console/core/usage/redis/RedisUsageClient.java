package site.kael.cpa.console.core.usage.redis;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLSocketFactory;

public class RedisUsageClient {
    private final String host;
    private final int port;
    private final String managementKey;
    private final Duration timeout;
    private final boolean tls;
    private final int batchSize;
    private volatile String lastQueueKey = "usage";

    public RedisUsageClient(String baseUrl, String redisAddress, String managementKey, Duration timeout, boolean tls, int batchSize) {
        String address = redisAddress == null || redisAddress.isBlank() ? deriveAddress(baseUrl) : redisAddress.trim();
        HostPort parsed = parseAddress(address);
        this.host = parsed.host(); this.port = parsed.port(); this.managementKey = managementKey == null ? "" : managementKey.trim();
        this.timeout = timeout; this.tls = tls; this.batchSize = batchSize;
    }

    public List<String> pull() throws IOException {
        try (Socket socket = open()) {
            RespReader reader = authenticate(socket);
            writeCommand(socket.getOutputStream(), "LPOP", "usage", Integer.toString(batchSize));
            RespValue response = reader.read();
            if (response.error() != null) {
                if (response.error().toLowerCase().contains("unsupported")) {
                    writeCommand(socket.getOutputStream(), "LPOP", "queue", Integer.toString(batchSize));
                    response = reader.read();
                    lastQueueKey = "queue";
                }
            }
            if (response.error() != null) throw new IOException(response.error());
            return response.strings();
        }
    }

    public String lastQueueKey() { return lastQueueKey; }

    public Subscription subscribe() throws IOException {
        Socket socket = open();
        try {
            RespReader reader = authenticate(socket);
            writeCommand(socket.getOutputStream(), "SUBSCRIBE", "usage");
            RespValue ack = reader.read();
            if (ack.array().size() < 2 || !"subscribe".equalsIgnoreCase(ack.array().get(0).text()) || !"usage".equals(ack.array().get(1).text())) {
                throw new IOException("unexpected Redis SUBSCRIBE response");
            }
            return new Subscription(socket, reader);
        } catch (Exception exception) {
            socket.close();
            if (exception instanceof IOException io) throw io;
            throw new IOException(exception);
        }
    }

    private RespReader authenticate(Socket socket) throws IOException {
        if (managementKey.isBlank()) throw new IOException("CPA management key is not configured");
        RespReader reader = new RespReader(socket.getInputStream());
        writeCommand(socket.getOutputStream(), "AUTH", managementKey);
        RespValue auth = reader.read();
        if (auth.error() != null) throw new IOException("Redis AUTH failed: " + auth.error());
        return reader;
    }

    private Socket open() throws IOException {
        Socket socket = tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
        socket.setSoTimeout(0);
        return socket;
    }

    public final class Subscription implements Closeable {
        private final Socket socket; private final RespReader reader;
        private Subscription(Socket socket, RespReader reader) { this.socket = socket; this.reader = reader; }
        public String receive() throws IOException {
            RespValue value = reader.read();
            if (value.error() != null) throw new IOException(value.error());
            List<RespValue> array = value.array();
            if (array.size() >= 3 && "message".equalsIgnoreCase(array.get(0).text()) && "usage".equals(array.get(1).text())) return array.get(2).text();
            return null;
        }
        @Override public void close() throws IOException { socket.close(); }
    }

    private static void writeCommand(OutputStream out, String... parts) throws IOException {
        StringBuilder builder = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String part : parts) builder.append('$').append(part.getBytes(StandardCharsets.UTF_8).length).append("\r\n").append(part).append("\r\n");
        out.write(builder.toString().getBytes(StandardCharsets.UTF_8)); out.flush();
    }

    private static String deriveAddress(String baseUrl) {
        try { URI uri = URI.create(baseUrl); return uri.getHost() + ":" + (uri.getPort() > 0 ? uri.getPort() : 8317); }
        catch (Exception ignored) { return "127.0.0.1:8317"; }
    }
    private static HostPort parseAddress(String address) {
        String value = address.replaceFirst("^[a-zA-Z]+://", "");
        int colon = value.lastIndexOf(':');
        if (colon > 0) return new HostPort(value.substring(0, colon), Integer.parseInt(value.substring(colon + 1)));
        return new HostPort(value, 8317);
    }
    private record HostPort(String host, int port) {}

    private record RespValue(String text, String error, List<RespValue> array) {
        static RespValue scalar(String value) { return new RespValue(value, null, List.of()); }
        static RespValue error(String value) { return new RespValue(null, value, List.of()); }
        static RespValue array(List<RespValue> value) { return new RespValue(null, null, value); }
        List<String> strings() { return array.stream().map(RespValue::text).toList(); }
    }
    private static final class RespReader {
        private final InputStream input;
        RespReader(InputStream input) { this.input = input; }
        RespValue read() throws IOException {
            int prefix = input.read(); if (prefix < 0) throw new EOFException("Redis connection closed");
            return switch (prefix) {
                case '+' -> RespValue.scalar(line());
                case '-' -> RespValue.error(line());
                case ':' -> RespValue.scalar(line());
                case '$' -> { int length = Integer.parseInt(line()); if (length < 0) yield RespValue.scalar(null); byte[] data = input.readNBytes(length); expectCRLF(); yield RespValue.scalar(new String(data, StandardCharsets.UTF_8)); }
                case '*' -> { int count = Integer.parseInt(line()); if (count < 0) yield RespValue.array(List.of()); List<RespValue> values = new ArrayList<>(count); for (int i = 0; i < count; i++) values.add(read()); yield RespValue.array(values); }
                default -> throw new IOException("invalid Redis RESP prefix: " + (char) prefix);
            };
        }
        private String line() throws IOException { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int previous = -1; int current; while ((current = input.read()) >= 0) { if (previous == '\r' && current == '\n') { byte[] value = bytes.toByteArray(); return new String(value, 0, value.length - 1, StandardCharsets.UTF_8); } bytes.write(current); previous = current; } throw new EOFException(); }
        private void expectCRLF() throws IOException { if (input.read() != '\r' || input.read() != '\n') throw new IOException("invalid Redis bulk terminator"); }
    }
}
