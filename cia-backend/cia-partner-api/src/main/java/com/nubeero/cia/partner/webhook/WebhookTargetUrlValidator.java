package com.nubeero.cia.partner.webhook;

import com.nubeero.cia.common.exception.BusinessRuleException;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

public final class WebhookTargetUrlValidator {

    private static final String ERROR_CODE = "INVALID_WEBHOOK_TARGET_URL";

    private WebhookTargetUrlValidator() {
    }

    public static URI validate(String targetUrl) {
        URI uri = parse(targetUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw invalid("Webhook target URL must use HTTPS");
        }
        if (uri.getUserInfo() != null) {
            throw invalid("Webhook target URL must not include user credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw invalid("Webhook target URL must include a valid host");
        }

        String normalizedHost = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        rejectKnownInternalHostnames(normalizedHost);
        for (InetAddress address : resolve(normalizedHost)) {
            if (isInternalAddress(address)) {
                throw invalid("Webhook target URL must not resolve to a private or local address");
            }
        }
        return uri;
    }

    private static URI parse(String targetUrl) {
        try {
            return URI.create(targetUrl);
        } catch (IllegalArgumentException e) {
            throw invalid("Webhook target URL is not a valid URI");
        }
    }

    private static InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw invalid("Webhook target host could not be resolved");
        }
    }

    private static void rejectKnownInternalHostnames(String host) {
        if (host.equals("localhost")
                || host.endsWith(".localhost")
                || host.equals("metadata.google.internal")) {
            throw invalid("Webhook target URL must not use an internal hostname");
        }
    }

    private static boolean isInternalAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isReservedIpv4(address)
                || isUniqueLocalIpv6(address);
    }

    private static boolean isReservedIpv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        return first == 0
                || first == 10
                || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19));
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        int first = Byte.toUnsignedInt(address.getAddress()[0]);
        return (first & 0xfe) == 0xfc;
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException(ERROR_CODE, message);
    }
}
