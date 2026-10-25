package com.otectus.otetv.core.net;

import java.util.Locale;
import java.util.Objects;

/**
 * A deliberate, local-only permission to fetch media from one host and port inside a private network, for example
 * a home media server. Only the local player can create one; a Minecraft server can never send or imply a grant.
 */
public record LanGrant(String host, int port) {
    public LanGrant {
        Objects.requireNonNull(host, "host");
        host = host.toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.length() > 253) {
            throw new IllegalArgumentException("bad host");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("bad port");
        }
    }

    public boolean matches(SafeUrl url) {
        return url.host().equals(host) && url.port() == port;
    }

    @Override
    public String toString() {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }
}
