package com.boatarde.regatasimulator.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.util.List;

@Data
@Validated
@ConfigurationProperties("regata-simulator.web")
public class WebSecurityProperties {
    @NotNull
    private List<String> allowedOrigins = List.of();
    private boolean allowCredentials = true;
    @NotNull
    private List<String> frameAncestors = List.of("'self'", "https://*.telegram.org", "https://telegram.org");
    private String cookieDomain;
    private boolean cookieSecure = true;
    @NotNull
    private String cookieSameSite = "Lax";

    @AssertTrue(message = "Web origins must be explicit HTTP(S) origins without paths or wildcards")
    public boolean isOriginsValid() {
        return allowedOrigins != null && allowedOrigins.stream().allMatch(WebSecurityProperties::isOrigin);
    }

    @AssertTrue(message = "Frame ancestors must be self or valid HTTP(S) origins")
    public boolean isFrameAncestorsValid() {
        return frameAncestors != null && !frameAncestors.isEmpty() && frameAncestors.stream()
            .allMatch(value -> "'self'".equals(value) || "https://*.telegram.org".equals(value) || isOrigin(value));
    }

    @AssertTrue(message = "Cookie domain must be a hostname without scheme, port, path or leading dot")
    public boolean isCookieDomainValid() {
        if (cookieDomain == null) {
            return true;
        }
        if (cookieDomain.length() > 253 || !cookieDomain.contains(".")) {
            return false;
        }
        for (String label : cookieDomain.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || !label.matches("[A-Za-z0-9-]+")
                || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
        }
        return true;
    }

    @AssertTrue(message = "SameSite must be Lax, Strict or secure None")
    public boolean isSameSiteValid() {
        return "Lax".equals(cookieSameSite) || "Strict".equals(cookieSameSite)
            || ("None".equals(cookieSameSite) && cookieSecure);
    }

    private static boolean isOrigin(String value) {
        if (value == null || value.contains("*") || value.contains(";") || value.contains("\\")) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            return ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getRawPath().isEmpty() && uri.getQuery() == null && uri.getFragment() == null
                && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535)
                && !value.endsWith(":");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}