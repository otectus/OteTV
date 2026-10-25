package com.otectus.otetv.core.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Decides which resolved addresses a client-side fetch may connect to. Public destinations are always allowed;
 * private ones only through an explicit {@link LanGrant} for that exact host and port; cloud metadata, multicast and
 * reserved space never. A name resolving to both public and non-public addresses is treated as a rebinding attempt
 * and refused. The caller must connect to one of the returned addresses (pinning) rather than re-resolving.
 */
public final class DestinationPolicy {
    /** DNS resolution, injectable for rebinding tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public static final Resolver SYSTEM_RESOLVER = InetAddress::getAllByName;

    private final Set<LanGrant> lanGrants;

    public DestinationPolicy(Collection<LanGrant> lanGrants) {
        this.lanGrants = Set.copyOf(lanGrants);
    }

    public static DestinationPolicy publicOnly() {
        return new DestinationPolicy(List.of());
    }

    public boolean hasGrantFor(SafeUrl url) {
        for (LanGrant grant : lanGrants) {
            if (grant.matches(url)) {
                return true;
            }
        }
        return false;
    }

    /** Plain http is only permitted to a granted LAN host. */
    public void checkScheme(SafeUrl url) throws DestinationBlockedException {
        if (!url.isHttps() && !hasGrantFor(url)) {
            throw new DestinationBlockedException(url.host(), AddressCategory.PUBLIC, "plain http requires a LAN grant");
        }
    }

    public List<InetAddress> resolveAllowed(SafeUrl url, Resolver resolver) throws DestinationBlockedException, UnknownHostException {
        checkScheme(url);
        InetAddress[] resolved = resolver.resolve(url.host());
        if (resolved == null || resolved.length == 0) {
            throw new UnknownHostException(url.host());
        }
        boolean granted = hasGrantFor(url);
        List<InetAddress> allowed = new ArrayList<>(resolved.length);
        AddressCategory worst = null;
        boolean sawPublic = false;
        for (InetAddress address : resolved) {
            AddressCategory category = IpPolicy.classify(address);
            if (category == AddressCategory.PUBLIC) {
                sawPublic = true;
                allowed.add(address);
            } else if (granted && category.isGrantable()) {
                allowed.add(address);
            } else {
                worst = category;
            }
        }
        if (worst != null && sawPublic) {
            throw new DestinationBlockedException(url.host(), worst, "mixed public and non-public answers");
        }
        if (allowed.isEmpty()) {
            throw new DestinationBlockedException(url.host(), worst == null ? AddressCategory.RESERVED : worst, null);
        }
        return allowed;
    }

    /** Re-checks the address a socket actually connected to. */
    public void checkConnected(SafeUrl url, InetAddress connected) throws DestinationBlockedException {
        AddressCategory category = IpPolicy.classify(connected);
        if (category == AddressCategory.PUBLIC) {
            return;
        }
        if (hasGrantFor(url) && category.isGrantable()) {
            return;
        }
        throw new DestinationBlockedException(url.host(), category, "connected address");
    }
}
