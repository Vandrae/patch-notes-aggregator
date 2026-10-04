package com.vandrae.patchnotes.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTest {

    @Test
    void anIpv4AddressIsTheKeyAsItIs() {
        assertThat(ClientIp.keyOfAddress("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv6AddressesInTheSameSlash64ShareAKeyBecauseOneUserOwnsAllOfThem() {
        String one = ClientIp.keyOfAddress("2001:db8:abcd:12::1");
        String other = ClientIp.keyOfAddress("2001:db8:abcd:12:ffff:ffff:ffff:ffff");
        String differentNetwork = ClientIp.keyOfAddress("2001:db8:abcd:13::1");

        assertThat(one).isEqualTo(other).startsWith("v6:");
        assertThat(differentNetwork).isNotEqualTo(one);
    }

    @Test
    void anIpv4MappedIpv6AddressIsTheSameClientAsTheIpv4() {
        assertThat(ClientIp.keyOfAddress("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void aZoneSuffixIsIgnored() {
        assertThat(ClientIp.keyOfAddress("fe80::1%eth0")).isEqualTo(ClientIp.keyOfAddress("fe80::1"));
    }

    @Test
    void missingOrUnrecognisableAddressesStillGetAKeyAndNeverTriggerALookup() {
        assertThat(ClientIp.keyOfAddress(null)).isEqualTo("unknown");
        assertThat(ClientIp.keyOfAddress("  ")).isEqualTo("unknown");
        assertThat(ClientIp.keyOfAddress("some-host.example.com")).isEqualTo("some-host.example.com"); // not resolved
        assertThat(ClientIp.keyOfAddress("not:an:address:zzzz")).isEqualTo("not:an:address:zzzz");
    }

    @Test
    void theAddressComesOnlyFromTheConnectionAndNeverFromAHeaderTheClientSent() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.20");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        request.addHeader("Forwarded", "for=5.6.7.8");

        // behind a trusted proxy Tomcat swaps getRemoteAddr for the real visitor; the raw headers are never trusted here
        assertThat(ClientIp.keyOf(request)).isEqualTo("198.51.100.20");
    }
}
