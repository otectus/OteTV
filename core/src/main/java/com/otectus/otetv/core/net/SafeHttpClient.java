package com.otectus.otetv.core.net;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The client-side media fetch broker's transport (section 11.3): HTTP/1.1 over a socket connected to an address the
 * {@link DestinationPolicy} approved, with TLS SNI and hostname verification against the original host name, so a
 * validated address can be pinned without weakening certificate checks. Every redirect hop is re-parsed and
 * re-validated; https-to-http downgrades are refused. One request per connection keeps the state machine small.
 */
public final class SafeHttpClient {
    public static final int DEFAULT_MAX_REDIRECTS = 5;

    /** Request options. Instances are mutable builders; do not share across threads while building. */
    public static final class Request {
        private final SafeUrl url;
        private long rangeStart = -1;
        private long rangeEnd = -1;
        private int maxRedirects = DEFAULT_MAX_REDIRECTS;
        private int connectTimeoutMs = 10_000;
        private int readTimeoutMs = 15_000;
        private long maxBodyBytes = Long.MAX_VALUE;
        private String accept = "*/*";
        private boolean head;

        public Request(SafeUrl url) {
            this.url = url;
        }

        public Request range(long start, long endInclusive) {
            this.rangeStart = start;
            this.rangeEnd = endInclusive;
            return this;
        }

        public Request rangeFrom(long start) {
            this.rangeStart = start;
            this.rangeEnd = -1;
            return this;
        }

        public Request maxRedirects(int value) {
            this.maxRedirects = Math.max(0, Math.min(10, value));
            return this;
        }

        public Request timeouts(int connectMs, int readMs) {
            this.connectTimeoutMs = connectMs;
            this.readTimeoutMs = readMs;
            return this;
        }

        public Request maxBodyBytes(long value) {
            this.maxBodyBytes = value;
            return this;
        }

        public Request accept(String value) {
            this.accept = value;
            return this;
        }

        public Request head() {
            this.head = true;
            return this;
        }

        public SafeUrl url() {
            return url;
        }
    }

    private final DestinationPolicy policy;
    private final DestinationPolicy.Resolver resolver;
    private final SSLSocketFactory sslFactory;
    private final String userAgent;

    public SafeHttpClient(DestinationPolicy policy, DestinationPolicy.Resolver resolver, SSLSocketFactory sslFactory, String userAgent) {
        this.policy = policy;
        this.resolver = resolver;
        this.sslFactory = sslFactory;
        this.userAgent = userAgent.replaceAll("[\\r\\n]", "");
    }

    public static SafeHttpClient create(DestinationPolicy policy, String userAgent) {
        return new SafeHttpClient(policy, DestinationPolicy.SYSTEM_RESOLVER, (SSLSocketFactory) SSLSocketFactory.getDefault(), userAgent);
    }

    public DestinationPolicy policy() {
        return policy;
    }

    public HttpResponse execute(Request request, CancellationToken token) throws IOException, DestinationBlockedException {
        SafeUrl current = request.url;
        for (int hop = 0; hop <= request.maxRedirects; hop++) {
            token.throwIfCancelled();
            HttpResponse response = executeOnce(current, request, token);
            int status = response.status();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = response.header("location");
                response.close();
                if (location == null) {
                    throw new HttpException(status, "redirect without Location");
                }
                SafeUrl next;
                try {
                    next = current.resolve(location);
                } catch (UrlRejectedException e) {
                    throw new HttpException(status, "rejected redirect target: " + e.reason());
                }
                if (current.isHttps() && !next.isHttps()) {
                    throw new DestinationBlockedException(next.host(), AddressCategory.PUBLIC, "https to http downgrade");
                }
                current = next;
                continue;
            }
            return response;
        }
        throw new HttpException(-1, "too many redirects");
    }

    private HttpResponse executeOnce(SafeUrl url, Request request, CancellationToken token) throws IOException, DestinationBlockedException {
        List<InetAddress> addresses = policy.resolveAllowed(url, resolver);
        IOException last = null;
        int attempts = Math.min(2, addresses.size());
        for (int i = 0; i < attempts; i++) {
            InetAddress address = addresses.get(i);
            Socket raw = new Socket();
            token.register(raw);
            boolean handedOff = false;
            try {
                raw.connect(new InetSocketAddress(address, url.port()), request.connectTimeoutMs);
                policy.checkConnected(url, raw.getInetAddress());
                raw.setSoTimeout(request.readTimeoutMs);
                raw.setTcpNoDelay(true);
                Socket socket = raw;
                if (url.isHttps()) {
                    SSLSocket ssl = (SSLSocket) sslFactory.createSocket(raw, url.host(), url.port(), true);
                    SSLParameters params = ssl.getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!url.isIpLiteral()) {
                        params.setServerNames(List.of(new SNIHostName(url.host())));
                    }
                    ssl.setSSLParameters(params);
                    token.register(ssl);
                    ssl.startHandshake();
                    socket = ssl;
                }
                OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 4096);
                out.write(buildRequest(url, request).getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                InputStream in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
                HttpResponse response = HttpResponse.read(url, address, socket, in, request.maxBodyBytes, token, request.head);
                handedOff = true;
                return response;
            } catch (DestinationBlockedException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                token.throwIfCancelled();
            } finally {
                if (!handedOff) {
                    try {
                        raw.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                    token.unregister(raw);
                }
            }
        }
        throw last != null ? last : new IOException("no address attempted");
    }

    private String buildRequest(SafeUrl url, Request request) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(request.head ? "HEAD " : "GET ").append(url.pathAndQuery()).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(url.hostHeader()).append("\r\n");
        sb.append("User-Agent: ").append(userAgent).append("\r\n");
        sb.append("Accept: ").append(request.accept.replaceAll("[\\r\\n]", "")).append("\r\n");
        sb.append("Accept-Encoding: identity\r\n");
        if (request.rangeStart >= 0) {
            sb.append("Range: bytes=").append(request.rangeStart).append('-');
            if (request.rangeEnd >= request.rangeStart) {
                sb.append(request.rangeEnd);
            }
            sb.append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        return sb.toString();
    }
}
