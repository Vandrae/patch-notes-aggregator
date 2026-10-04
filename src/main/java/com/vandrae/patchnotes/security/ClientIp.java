package com.vandrae.patchnotes.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Turns a request into the key its client is rate limited by.
 *
 * <p>The address comes from {@link HttpServletRequest#getRemoteAddr()} and nothing else. Behind a reverse proxy Tomcat
 * replaces it with the real visitor's address from {@code X-Forwarded-For}, but only for requests that arrive from a
 * proxy it trusts (the {@code prod} profile enables this); a header from anyone else is ignored. Reading the header here
 * ourselves would let any visitor claim any address and so dodge the limit.
 */
final class ClientIp {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private ClientIp() {
    }

    static String keyOf(HttpServletRequest request) {
        return keyOfAddress(request.getRemoteAddr());
    }

    /**
     * IPv4 addresses are used as they are. An IPv6 address is cut to its first 64 bits, because whoever has one address
     * in a /64 can use any of the 2^64 others: counting each separately would give an attacker unlimited fresh buckets.
     */
    static String keyOfAddress(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        String literal = address.strip();
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if (IPV4.matcher(literal).matches()) {
            return literal;
        }
        if (literal.indexOf(':') < 0) {
            return literal; // not an address we understand; use it as is rather than guess
        }
        try {
            InetAddress parsed = InetAddress.getByName(literal); // a literal: no DNS lookup happens
            if (parsed instanceof Inet6Address v6) {
                return "v6:" + HexFormat.of().formatHex(v6.getAddress(), 0, 8);
            }
            return parsed.getHostAddress(); // an IPv4-mapped address (::ffff:1.2.3.4) comes back as plain IPv4
        } catch (UnknownHostException e) {
            return literal;
        }
    }
}
