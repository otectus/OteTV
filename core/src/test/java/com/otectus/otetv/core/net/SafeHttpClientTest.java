package com.otectus.otetv.core.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the HTTP client against a loopback server reached through an explicit LAN grant. */
class SafeHttpClientTest {
    private ServerSocket server;
    private Thread thread;
    private final AtomicReference<String> lastRequest = new AtomicReference<>();
    private volatile Responder responder;

    interface Responder {
        void respond(String requestHead, OutputStream out) throws IOException;
    }

    @BeforeEach
    void start() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    StringBuilder head = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        head.append(line).append('\n');
                    }
                    lastRequest.set(head.toString());
                    responder.respond(head.toString(), s.getOutputStream());
                } catch (IOException ignored) {
                    // server closed
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
    }

    private SafeHttpClient client() {
        DestinationPolicy policy = new DestinationPolicy(List.of(new LanGrant("127.0.0.1", server.getLocalPort())));
        return new SafeHttpClient(policy, DestinationPolicy.SYSTEM_RESOLVER, (SSLSocketFactory) SSLSocketFactory.getDefault(), "OteTV-test");
    }

    private SafeUrl url(String path) throws UrlRejectedException {
        return SafeUrl.parse("http://127.0.0.1:" + server.getLocalPort() + path);
    }

    @Test
    void sendsRangeAndParsesPartialContent() throws Exception {
        byte[] body = "0123456789".getBytes(StandardCharsets.US_ASCII);
        responder = (head, out) -> {
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 2-5/10\r\nContent-Length: 4\r\n\r\n").getBytes());
            out.write(body, 2, 4);
            out.flush();
        };
        try (HttpResponse r = client().execute(new SafeHttpClient.Request(url("/v.webm")).range(2, 5), new CancellationToken())) {
            assertTrue(r.isPartial());
            assertEquals(10, r.rangeTotal());
            assertArrayEquals("2345".getBytes(), r.readAll());
        }
        assertTrue(lastRequest.get().contains("Range: bytes=2-5"));
        assertTrue(lastRequest.get().contains("Host: 127.0.0.1:" + server.getLocalPort()));
    }

    @Test
    void decodesChunkedBodies() throws Exception {
        responder = (head, out) -> {
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nWiki\r\n5;ext=1\r\npedia\r\n0\r\nX-Trailer: y\r\n\r\n".getBytes());
            out.flush();
        };
        try (HttpResponse r = client().execute(new SafeHttpClient.Request(url("/c")), new CancellationToken())) {
            assertEquals("Wikipedia", new String(r.readAll(), StandardCharsets.US_ASCII));
        }
    }

    @Test
    void enforcesBodyLimit() throws Exception {
        responder = (head, out) -> {
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".getBytes());
            out.write(new byte[100]);
            out.flush();
        };
        assertThrows(HttpException.class, () -> client().execute(new SafeHttpClient.Request(url("/big")).maxBodyBytes(10), new CancellationToken()));
    }

    @Test
    void followsRedirectsOnlyToPermittedDestinations() throws Exception {
        int port = server.getLocalPort();
        responder = (head, out) -> {
            if (head.startsWith("GET /start")) {
                out.write(("HTTP/1.1 302 Found\r\nLocation: /final\r\nContent-Length: 0\r\n\r\n").getBytes());
            } else if (head.startsWith("GET /escape")) {
                out.write(("HTTP/1.1 302 Found\r\nLocation: http://10.0.0.1:" + port + "/x\r\nContent-Length: 0\r\n\r\n").getBytes());
            } else {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".getBytes());
            }
            out.flush();
        };
        try (HttpResponse r = client().execute(new SafeHttpClient.Request(url("/start")), new CancellationToken())) {
            assertEquals("ok", new String(r.readAll(), StandardCharsets.US_ASCII));
            assertEquals("/final", r.url().path());
        }
        assertThrows(DestinationBlockedException.class, () -> client().execute(new SafeHttpClient.Request(url("/escape")), new CancellationToken()));
    }

    @Test
    void refusesRedirectLoops() throws Exception {
        responder = (head, out) -> {
            out.write("HTTP/1.1 302 Found\r\nLocation: /loop\r\nContent-Length: 0\r\n\r\n".getBytes());
            out.flush();
        };
        assertThrows(HttpException.class, () -> client().execute(new SafeHttpClient.Request(url("/loop")).maxRedirects(3), new CancellationToken()));
    }

    @Test
    void cancellationAbortsBlockedReads() throws Exception {
        responder = (head, out) -> {
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n".getBytes());
            out.flush();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        };
        CancellationToken token = new CancellationToken();
        HttpResponse r = client().execute(new SafeHttpClient.Request(url("/slow")), token);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            token.cancel();
        });
        canceller.start();
        long start = System.nanoTime();
        assertThrows(IOException.class, () -> r.body().read(new byte[4096]));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000, "cancel should unblock quickly");
        r.close();
    }

    @Test
    void rejectsMalformedResponses() throws Exception {
        responder = (head, out) -> {
            out.write("SSH-2.0-OpenSSH\r\n\r\n".getBytes());
            out.flush();
        };
        assertThrows(HttpException.class, () -> client().execute(new SafeHttpClient.Request(url("/x")), new CancellationToken()));
    }
}
