package com.otectus.otetv.core.net;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A parsed HTTP/1.1 response whose body streams from the pinned socket with size and cancellation limits. */
public final class HttpResponse implements Closeable {
    private static final int MAX_LINE = 8 * 1024;
    private static final int MAX_HEADERS = 100;
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private final SafeUrl url;
    private final InetAddress connectedAddress;
    private final int status;
    private final Map<String, List<String>> headers;
    private final Socket socket;
    private final InputStream body;
    private final long contentLength;
    private final long rangeStart;
    private final long rangeEnd;
    private final long rangeTotal;

    private HttpResponse(SafeUrl url, InetAddress connectedAddress, int status, Map<String, List<String>> headers,
                         Socket socket, InputStream body, long contentLength, long rangeStart, long rangeEnd, long rangeTotal) {
        this.url = url;
        this.connectedAddress = connectedAddress;
        this.status = status;
        this.headers = headers;
        this.socket = socket;
        this.body = body;
        this.contentLength = contentLength;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
        this.rangeTotal = rangeTotal;
    }

    static HttpResponse read(SafeUrl url, InetAddress address, Socket socket, InputStream in, long maxBody,
                             CancellationToken token, boolean headRequest) throws IOException {
        String statusLine = readLine(in);
        if (statusLine == null || !statusLine.startsWith("HTTP/1.")) {
            throw new HttpException(-1, "not an HTTP/1.x response");
        }
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || parts[1].length() != 3 || !parts[1].chars().allMatch(Character::isDigit)) {
            throw new HttpException(-1, "malformed status line");
        }
        int status = Integer.parseInt(parts[1]);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        int total = 0;
        int count = 0;
        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new HttpException(status, "truncated headers");
            }
            if (line.isEmpty()) {
                break;
            }
            total += line.length();
            if (++count > MAX_HEADERS || total > MAX_HEADER_BYTES) {
                throw new HttpException(status, "header block too large");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new HttpException(status, "malformed header");
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
        }

        long contentLength = -1;
        String cl = first(headers, "content-length");
        if (cl != null) {
            try {
                contentLength = Long.parseLong(cl.trim());
            } catch (NumberFormatException e) {
                throw new HttpException(status, "bad content-length");
            }
            if (contentLength < 0) {
                throw new HttpException(status, "negative content-length");
            }
        }
        long rs = -1;
        long re = -1;
        long rt = -1;
        String cr = first(headers, "content-range");
        if (cr != null && status == 206) {
            // bytes start-end/total
            String spec = cr.trim();
            if (!spec.startsWith("bytes ")) {
                throw new HttpException(status, "bad content-range");
            }
            try {
                String[] rangeAndTotal = spec.substring(6).split("/", 2);
                String[] se = rangeAndTotal[0].split("-", 2);
                rs = Long.parseLong(se[0].trim());
                re = Long.parseLong(se[1].trim());
                rt = rangeAndTotal.length > 1 && !rangeAndTotal[1].trim().equals("*") ? Long.parseLong(rangeAndTotal[1].trim()) : -1;
            } catch (RuntimeException e) {
                throw new HttpException(status, "bad content-range");
            }
            if (rs < 0 || re < rs || (rt >= 0 && re >= rt)) {
                throw new HttpException(status, "inconsistent content-range");
            }
        }

        InputStream bodyStream;
        String te = first(headers, "transfer-encoding");
        if (headRequest || status == 204 || status == 304 || (status >= 100 && status < 200)) {
            bodyStream = InputStream.nullInputStream();
        } else if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            bodyStream = new ChunkedInputStream(in);
        } else if (contentLength >= 0) {
            bodyStream = new BoundedInputStream(in, contentLength, false);
        } else {
            bodyStream = in;
        }
        if (contentLength > maxBody) {
            throw new HttpException(status, "body exceeds limit");
        }
        bodyStream = new LimitedInputStream(bodyStream, maxBody, token);
        return new HttpResponse(url, address, status, Collections.unmodifiableMap(headers), socket, bodyStream,
                contentLength, rs, re, rt);
    }

    private static String first(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') {
                    len--;
                }
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }
            buf.write(c);
            if (buf.size() > MAX_LINE) {
                throw new HttpException(-1, "line too long");
            }
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.ISO_8859_1);
    }

    public SafeUrl url() {
        return url;
    }

    public InetAddress connectedAddress() {
        return connectedAddress;
    }

    public int status() {
        return status;
    }

    public String header(String name) {
        return first(headers, name.toLowerCase(Locale.ROOT));
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    public long contentLength() {
        return contentLength;
    }

    public boolean isPartial() {
        return status == 206 && rangeStart >= 0;
    }

    public long rangeStart() {
        return rangeStart;
    }

    public long rangeEnd() {
        return rangeEnd;
    }

    /** Total resource length from Content-Range, or -1. */
    public long rangeTotal() {
        return rangeTotal;
    }

    public String contentType() {
        String ct = header("content-type");
        return ct == null ? "" : ct.toLowerCase(Locale.ROOT);
    }

    public InputStream body() {
        return body;
    }

    /** Reads the whole body; the limit given in the request still applies. */
    public byte[] readAll() throws IOException {
        try (InputStream in = body) {
            return in.readAllBytes();
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Streams exactly {@code remaining} bytes. */
    static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;
        private final boolean allowShort;

        BoundedInputStream(InputStream in, long length, boolean allowShort) {
            this.in = in;
            this.remaining = length;
            this.allowShort = allowShort;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b < 0) {
                if (!allowShort) {
                    throw new HttpException(-1, "body truncated");
                }
                return -1;
            }
            remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n < 0) {
                if (!allowShort) {
                    throw new HttpException(-1, "body truncated");
                }
                return -1;
            }
            remaining -= n;
            return n;
        }
    }

    /** Enforces the per-request body limit and cancellation on every read. */
    static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private final long limit;
        private final CancellationToken token;
        private long count;

        LimitedInputStream(InputStream in, long limit, CancellationToken token) {
            this.in = in;
            this.limit = limit;
            this.token = token;
        }

        @Override
        public int read() throws IOException {
            token.throwIfCancelled();
            int b = in.read();
            if (b >= 0 && ++count > limit) {
                throw new HttpException(-1, "body exceeds limit");
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            token.throwIfCancelled();
            int n = in.read(b, off, len);
            if (n > 0) {
                count += n;
                if (count > limit) {
                    throw new HttpException(-1, "body exceeds limit");
                }
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** RFC 9112 chunked transfer decoding with bounded chunk-size lines. Trailers are discarded. */
    static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private long chunkRemaining;
        private boolean eof;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        private boolean nextChunk() throws IOException {
            if (eof) {
                return false;
            }
            String line = readLine(in);
            if (line == null) {
                throw new HttpException(-1, "truncated chunked body");
            }
            int semi = line.indexOf(';');
            String size = (semi >= 0 ? line.substring(0, semi) : line).trim();
            if (size.isEmpty() || size.length() > 15) {
                throw new HttpException(-1, "bad chunk size");
            }
            try {
                chunkRemaining = Long.parseLong(size, 16);
            } catch (NumberFormatException e) {
                throw new HttpException(-1, "bad chunk size");
            }
            if (chunkRemaining < 0) {
                throw new HttpException(-1, "bad chunk size");
            }
            if (chunkRemaining == 0) {
                eof = true;
                String trailer;
                int trailers = 0;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                    if (++trailers > MAX_HEADERS) {
                        throw new HttpException(-1, "too many trailers");
                    }
                }
                return false;
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (chunkRemaining == 0) {
                if (!nextChunk()) {
                    return -1;
                }
            }
            int n = in.read(b, off, (int) Math.min(len, chunkRemaining));
            if (n < 0) {
                throw new HttpException(-1, "truncated chunk");
            }
            chunkRemaining -= n;
            if (chunkRemaining == 0) {
                String crlf = readLine(in);
                if (crlf == null || !crlf.isEmpty()) {
                    throw new HttpException(-1, "missing chunk terminator");
                }
            }
            return n;
        }
    }
}
