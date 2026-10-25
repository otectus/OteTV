package com.otectus.otetv.worker.media;

import com.otectus.otetv.core.media.MediaSniffer;
import com.otectus.otetv.core.net.CancellationToken;
import com.otectus.otetv.core.net.DestinationBlockedException;
import com.otectus.otetv.core.net.HttpResponse;
import com.otectus.otetv.core.net.SafeHttpClient;
import com.otectus.otetv.core.net.SafeUrl;

import java.io.IOException;
import java.io.InputStream;

/**
 * Progressive media over the guarded HTTP client (section 11.3). Byte ranges are probed from the actual response,
 * never assumed from a filename or HEAD: a 206 with a Content-Range makes the source seekable, a 200 means the server
 * ignores ranges and the source is stream-only. Reconnects once mid-stream from the current offset.
 */
final class HttpByteSource implements ByteSource {
    static final class NotMediaException extends IOException {
        NotMediaException(String m) {
            super(m);
        }
    }

    private final SafeHttpClient client;
    private final SafeUrl url;
    private final CancellationToken token;
    private HttpResponse response;
    private InputStream body;
    private long position;
    private long size = -1;
    private boolean seekable;

    HttpByteSource(SafeHttpClient client, SafeUrl url, CancellationToken token) throws IOException, DestinationBlockedException {
        this.client = client;
        this.url = url;
        this.token = token;
        open(0, true);
    }

    private void open(long from, boolean first) throws IOException, DestinationBlockedException {
        close();
        SafeHttpClient.Request req = new SafeHttpClient.Request(url).rangeFrom(from).timeouts(10_000, 20_000)
                .accept("video/*, audio/*, application/octet-stream;q=0.8, */*;q=0.5");
        HttpResponse r = client.execute(req, token);
        int status = r.status();
        if (status != 200 && status != 206) {
            r.close();
            throw new IOException("HTTP " + status);
        }
        String type = r.contentType();
        if (type.startsWith("text/html") || type.startsWith("application/xhtml")) {
            r.close();
            throw new NotMediaException("server returned a web page (" + type + ")");
        }
        if (status == 206 && r.isPartial()) {
            if (r.rangeStart() != from) {
                r.close();
                throw new IOException("server returned the wrong range");
            }
            seekable = r.rangeTotal() > 0;
            size = r.rangeTotal();
        } else {
            if (from > 0) {
                r.close();
                throw new IOException("server ignored the byte range");
            }
            seekable = false;
            size = r.contentLength();
        }
        if (first) {
            Log.info("opened " + url.host() + " (" + (seekable ? "ranges" : "no ranges") + ", " + size + " bytes)");
        }
        response = r;
        body = r.body();
        position = from;
    }

    /** Peeks at the start of the resource for bounded content sniffing (type is never trusted alone). */
    MediaSniffer.Kind sniff(byte[] head, int length) {
        return MediaSniffer.sniff(head, length);
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public boolean seekable() {
        return seekable;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (size >= 0 && position >= size) {
            return -1;
        }
        try {
            int n = body.read(buf, off, len);
            if (n > 0) {
                position += n;
            }
            return n;
        } catch (IOException e) {
            if (token.isCancelled() || !seekable) {
                throw e;
            }
            Log.info("reconnecting after read failure at " + position);
            try {
                open(position, false);
            } catch (DestinationBlockedException blocked) {
                throw new IOException("destination blocked on reconnect");
            }
            int n = body.read(buf, off, len);
            if (n > 0) {
                position += n;
            }
            return n;
        }
    }

    @Override
    public void seek(long target) throws IOException {
        if (target == position) {
            return;
        }
        if (!seekable) {
            throw new IOException("source is not seekable");
        }
        try {
            open(target, false);
        } catch (DestinationBlockedException e) {
            throw new IOException("destination blocked");
        }
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public void close() {
        if (response != null) {
            response.close();
            response = null;
            body = null;
        }
    }
}
