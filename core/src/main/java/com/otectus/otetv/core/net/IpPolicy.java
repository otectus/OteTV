package com.otectus.otetv.core.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Classifies IPv4 and IPv6 addresses by reachability, following the OWASP SSRF guidance referenced in the brief.
 * IPv6 forms that embed an IPv4 address (mapped, compatible, NAT64 well-known prefix, 6to4) are classified by the
 * embedded address, so {@code ::ffff:127.0.0.1} and {@code 2002:7f00:0001::} are loopback, not public.
 */
public final class IpPolicy {
    private IpPolicy() {
    }

    public static boolean isPublic(InetAddress address) {
        return classify(address) == AddressCategory.PUBLIC;
    }

    public static AddressCategory classify(InetAddress address) {
        if (address instanceof Inet4Address) {
            return classifyV4(address.getAddress());
        }
        if (address instanceof Inet6Address) {
            return classifyV6(address.getAddress());
        }
        return AddressCategory.RESERVED;
    }

    static AddressCategory classifyV4(byte[] a) {
        int b0 = a[0] & 0xFF;
        int b1 = a[1] & 0xFF;
        int b2 = a[2] & 0xFF;
        int b3 = a[3] & 0xFF;
        if (b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255) {
            return AddressCategory.BROADCAST;
        }
        if (b0 == 0) {
            return AddressCategory.UNSPECIFIED;
        }
        if (b0 == 10) {
            return AddressCategory.PRIVATE;
        }
        if (b0 == 100 && (b1 & 0xC0) == 64) {
            return AddressCategory.CARRIER_GRADE_NAT;
        }
        if (b0 == 127) {
            return AddressCategory.LOOPBACK;
        }
        if (b0 == 169 && b1 == 254) {
            return (b2 == 169 && b3 == 254) ? AddressCategory.CLOUD_METADATA : AddressCategory.LINK_LOCAL;
        }
        if (b0 == 172 && (b1 & 0xF0) == 16) {
            return AddressCategory.PRIVATE;
        }
        if (b0 == 192 && b1 == 0 && b2 == 0) {
            return AddressCategory.RESERVED;
        }
        if (b0 == 192 && b1 == 0 && b2 == 2) {
            return AddressCategory.DOCUMENTATION;
        }
        if (b0 == 192 && b1 == 88 && b2 == 99) {
            return AddressCategory.RESERVED;
        }
        if (b0 == 192 && b1 == 168) {
            return AddressCategory.PRIVATE;
        }
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            return AddressCategory.BENCHMARKING;
        }
        if (b0 == 198 && b1 == 51 && b2 == 100) {
            return AddressCategory.DOCUMENTATION;
        }
        if (b0 == 203 && b1 == 0 && b2 == 113) {
            return AddressCategory.DOCUMENTATION;
        }
        if (b0 >= 224 && b0 <= 239) {
            return AddressCategory.MULTICAST;
        }
        if (b0 >= 240) {
            return AddressCategory.RESERVED;
        }
        return AddressCategory.PUBLIC;
    }

    static AddressCategory classifyV6(byte[] a) {
        boolean allZeroFirst10 = true;
        for (int i = 0; i < 10; i++) {
            if (a[i] != 0) {
                allZeroFirst10 = false;
                break;
            }
        }
        if (allZeroFirst10) {
            boolean first12Zero = a[10] == 0 && a[11] == 0;
            if (first12Zero) {
                boolean rest = a[12] == 0 && a[13] == 0 && a[14] == 0;
                if (rest && a[15] == 0) {
                    return AddressCategory.UNSPECIFIED;
                }
                if (rest && a[15] == 1) {
                    return AddressCategory.LOOPBACK;
                }
                // ::a.b.c.d (deprecated IPv4-compatible): judge by the embedded address, never better than it.
                return nonPublicOr(classifyV4(new byte[]{a[12], a[13], a[14], a[15]}), AddressCategory.RESERVED);
            }
            if ((a[10] & 0xFF) == 0xFF && (a[11] & 0xFF) == 0xFF) {
                return classifyV4(new byte[]{a[12], a[13], a[14], a[15]});
            }
        }
        int w0 = ((a[0] & 0xFF) << 8) | (a[1] & 0xFF);
        int w1 = ((a[2] & 0xFF) << 8) | (a[3] & 0xFF);
        // 64:ff9b::/96 well-known NAT64 prefix embeds an IPv4 address in the low 32 bits.
        if (w0 == 0x0064 && w1 == 0xff9b) {
            boolean wellKnown = true;
            for (int i = 4; i < 12; i++) {
                if (a[i] != 0) {
                    wellKnown = false;
                    break;
                }
            }
            if (wellKnown) {
                return classifyV4(new byte[]{a[12], a[13], a[14], a[15]});
            }
            return AddressCategory.RESERVED; // 64:ff9b:1::/48 local-use NAT64
        }
        if (w0 == 0x0100 && w1 == 0 && a[4] == 0 && a[5] == 0 && a[6] == 0 && a[7] == 0) {
            return AddressCategory.RESERVED; // 100::/64 discard-only
        }
        if (w0 == 0x2001) {
            if (w1 == 0x0db8) {
                return AddressCategory.DOCUMENTATION;
            }
            if (w1 == 0x0000) {
                return AddressCategory.RESERVED; // Teredo can tunnel to private IPv4 space
            }
            if ((w1 & 0xFFF0) == 0x0010 || (w1 & 0xFFF0) == 0x0020) {
                return AddressCategory.RESERVED; // ORCHID / ORCHIDv2
            }
            if (w1 < 0x0200) {
                return AddressCategory.RESERVED; // 2001::/23 IETF protocol assignments
            }
        }
        if (w0 == 0x2002) {
            // 6to4 embeds the IPv4 relay address in bits 16..47.
            return nonPublicOr(classifyV4(new byte[]{a[2], a[3], a[4], a[5]}), AddressCategory.PUBLIC);
        }
        if ((w0 & 0xFE00) == 0xFC00) {
            return AddressCategory.UNIQUE_LOCAL;
        }
        if ((w0 & 0xFFC0) == 0xFE80) {
            return AddressCategory.LINK_LOCAL;
        }
        if ((w0 & 0xFFC0) == 0xFEC0) {
            return AddressCategory.PRIVATE; // deprecated site-local
        }
        if ((w0 & 0xFF00) == 0xFF00) {
            return AddressCategory.MULTICAST;
        }
        if ((w0 & 0xE000) != 0x2000) {
            return AddressCategory.RESERVED; // outside 2000::/3 global unicast
        }
        return AddressCategory.PUBLIC;
    }

    private static AddressCategory nonPublicOr(AddressCategory embedded, AddressCategory fallback) {
        return embedded == AddressCategory.PUBLIC ? fallback : embedded;
    }
}
