package com.otectus.otetv.core.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DestinationPolicyTest {
    private static DestinationPolicy.Resolver fixed(String... addresses) {
        return host -> {
            InetAddress[] out = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                out[i] = InetAddress.getByName(addresses[i]);
            }
            return out;
        };
    }

    @Test
    void allowsPublicAnswers() throws Exception {
        List<InetAddress> allowed = DestinationPolicy.publicOnly()
                .resolveAllowed(SafeUrl.parse("https://media.example.com/a.webm"), fixed("93.184.216.34", "2606:2800:220:1::1"));
        assertEquals(2, allowed.size());
    }

    @Test
    void blocksNamesResolvingToPrivateSpace() {
        DestinationBlockedException e = assertThrows(DestinationBlockedException.class, () -> DestinationPolicy.publicOnly()
                .resolveAllowed(SafeUrl.parse("https://internal.example.com/"), fixed("10.0.0.5")));
        assertEquals(AddressCategory.PRIVATE, e.category());
    }

    @Test
    void treatsMixedAnswersAsRebinding() {
        DestinationBlockedException e = assertThrows(DestinationBlockedException.class, () -> DestinationPolicy.publicOnly()
                .resolveAllowed(SafeUrl.parse("https://rebind.example.com/"), fixed("93.184.216.34", "127.0.0.1")));
        assertEquals(AddressCategory.LOOPBACK, e.category());
    }

    @Test
    void simulatedRebindingBetweenLookupsIsCaughtAtConnectTime() throws Exception {
        // First lookup is public; a later answer would be loopback. The caller pins the first answer and re-checks
        // the connected address, so a second resolution can never be used.
        DestinationPolicy policy = DestinationPolicy.publicOnly();
        SafeUrl url = SafeUrl.parse("https://rebind.example.com/");
        InetAddress pinned = policy.resolveAllowed(url, fixed("93.184.216.34")).get(0);
        policy.checkConnected(url, pinned);
        assertThrows(DestinationBlockedException.class, () -> policy.checkConnected(url, InetAddress.getByName("127.0.0.1")));
    }

    @Test
    void lanGrantOpensOnlyThatHostAndPort() throws Exception {
        DestinationPolicy policy = new DestinationPolicy(List.of(new LanGrant("192.168.1.20", 8096)));
        policy.resolveAllowed(SafeUrl.parse("http://192.168.1.20:8096/v.webm"), fixed("192.168.1.20"));
        assertThrows(DestinationBlockedException.class,
                () -> policy.resolveAllowed(SafeUrl.parse("http://192.168.1.20:8097/v.webm"), fixed("192.168.1.20")));
        assertThrows(DestinationBlockedException.class,
                () -> policy.resolveAllowed(SafeUrl.parse("https://192.168.1.21:8096/v.webm"), fixed("192.168.1.21")));
    }

    @Test
    void cloudMetadataIsNeverGrantable() throws Exception {
        DestinationPolicy policy = new DestinationPolicy(List.of(new LanGrant("metadata.example.com", 443)));
        assertThrows(DestinationBlockedException.class,
                () -> policy.resolveAllowed(SafeUrl.parse("https://metadata.example.com/"), fixed("169.254.169.254")));
    }

    @Test
    void plainHttpNeedsAGrant() {
        DestinationBlockedException e = assertThrows(DestinationBlockedException.class, () -> DestinationPolicy.publicOnly()
                .resolveAllowed(SafeUrl.parse("http://media.example.com/a.webm"), fixed("93.184.216.34")));
        assertNull(e.category(), "refused before resolution, so no address class is claimed");
        assertEquals("plain http requires a LAN grant", e.describe());
    }

    @Test
    void unknownHostPropagates() {
        assertThrows(UnknownHostException.class, () -> DestinationPolicy.publicOnly()
                .resolveAllowed(SafeUrl.parse("https://nowhere.example/"), host -> new InetAddress[0]));
    }
}
