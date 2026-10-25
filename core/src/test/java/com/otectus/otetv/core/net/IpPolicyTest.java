package com.otectus.otetv.core.net;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IpPolicyTest {
    @ParameterizedTest
    @CsvSource({
            "8.8.8.8, PUBLIC",
            "93.184.216.34, PUBLIC",
            "127.0.0.1, LOOPBACK",
            "127.255.255.254, LOOPBACK",
            "10.1.2.3, PRIVATE",
            "172.16.0.1, PRIVATE",
            "172.31.255.255, PRIVATE",
            "172.32.0.1, PUBLIC",
            "192.168.1.10, PRIVATE",
            "100.64.0.1, CARRIER_GRADE_NAT",
            "100.100.100.200, CARRIER_GRADE_NAT",
            "169.254.169.254, CLOUD_METADATA",
            "169.254.10.10, LINK_LOCAL",
            "0.0.0.0, UNSPECIFIED",
            "255.255.255.255, BROADCAST",
            "224.0.0.251, MULTICAST",
            "240.0.0.1, RESERVED",
            "192.0.2.5, DOCUMENTATION",
            "198.51.100.7, DOCUMENTATION",
            "203.0.113.9, DOCUMENTATION",
            "198.18.0.1, BENCHMARKING",
            "192.0.0.170, RESERVED",
            "192.88.99.1, RESERVED",
    })
    void classifiesIpv4(String address, AddressCategory expected) throws Exception {
        assertEquals(expected, IpPolicy.classify(InetAddress.getByName(address)));
    }

    @ParameterizedTest
    @CsvSource({
            "2606:4700:4700::1111, PUBLIC",
            "::1, LOOPBACK",
            "::, UNSPECIFIED",
            "::ffff:127.0.0.1, LOOPBACK",
            "::ffff:8.8.8.8, PUBLIC",
            "::127.0.0.1, LOOPBACK",
            "::8.8.8.8, RESERVED",
            "64:ff9b::10.0.0.1, PRIVATE",
            "64:ff9b::8.8.8.8, PUBLIC",
            "64:ff9b:1::1, RESERVED",
            "2002:7f00:1::, LOOPBACK",
            "2002:0808:0808::1, PUBLIC",
            "2001::1, RESERVED",
            "2001:db8::1, DOCUMENTATION",
            "fc00::1, UNIQUE_LOCAL",
            "fd00:ec2::254, UNIQUE_LOCAL",
            "fe80::1, LINK_LOCAL",
            "fec0::1, PRIVATE",
            "ff02::1, MULTICAST",
            "100::1, RESERVED",
            "4000::1, RESERVED",
    })
    void classifiesIpv6IncludingEmbeddedIpv4(String address, AddressCategory expected) throws Exception {
        assertEquals(expected, IpPolicy.classify(InetAddress.getByName(address)));
    }
}
