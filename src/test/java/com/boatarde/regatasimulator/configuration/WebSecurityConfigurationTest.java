package com.boatarde.regatasimulator.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.session.web.http.CookieSerializer;

import static org.assertj.core.api.Assertions.assertThat;

class WebSecurityConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(CorsConfig.class, SessionConfig.class);

    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.com", "https://example.com/path", "https://example.com:0",
        "https://example.com:65536", "https://user@example.com", "https://example.com?q=1", "ftp://example.com"})
    void rejectsInvalidOrigins(String origin) {
        runner.withPropertyValues("regata-simulator.web.allowed-origins[0]=" + origin)
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", ".example.com", "example.com:443", "bad_domain.com", ""})
    void rejectsInvalidCookieDomains(String domain) {
        runner.withPropertyValues("regata-simulator.web.cookie-domain=" + domain)
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "stage", "default"})
    void deployedProfilesUseSecureHostOnlyLaxCookies(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(cookie(context.getBean(CookieSerializer.class)))
                .contains("JSESSIONID=", "Secure", "HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Domain=");
        });
    }

    @Test
    void devMayExplicitlyUseHttpAndDomainMayBeConfigured() {
        runner.withPropertyValues("spring.profiles.active=dev", "regata-simulator.web.cookie-secure=false",
            "regata-simulator.web.cookie-domain=example.com").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(cookie(context.getBean(CookieSerializer.class)))
                    .contains("Domain=example.com", "HttpOnly", "SameSite=Lax").doesNotContain("Secure");
            });
    }

    @Test
    void productionCannotOptOutOfSecureCookies() {
        runner.withPropertyValues("spring.profiles.active=prod", "regata-simulator.web.cookie-secure=false")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsUnsafeSameSiteAndCspDirectives() {
        runner.withPropertyValues("regata-simulator.web.cookie-same-site=invalid")
            .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("regata-simulator.web.frame-ancestors[0]=*; script-src *")
            .run(context -> assertThat(context).hasFailed());
    }

    private String cookie(CookieSerializer serializer) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        serializer.writeCookieValue(new CookieSerializer.CookieValue(new MockHttpServletRequest(), response, "test"));
        return response.getHeader("Set-Cookie");
    }
}