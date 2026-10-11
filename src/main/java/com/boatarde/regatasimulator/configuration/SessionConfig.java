package com.boatarde.regatasimulator.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import java.util.concurrent.ConcurrentHashMap;

@Configuration
@EnableSpringHttpSession
public class SessionConfig {

    @Bean
    public MapSessionRepository sessionRepository() {
        return new MapSessionRepository(new ConcurrentHashMap<>());
    }

    @Bean
    public CookieSerializer cookieSerializer(WebSecurityProperties properties, Environment environment) {
        boolean deployed = environment.acceptsProfiles(Profiles.of("prod", "stage"));
        if (!properties.isCookieSecure() && (deployed || !environment.acceptsProfiles(Profiles.of("dev", "test")))) {
            throw new IllegalArgumentException("Insecure session cookies require the dev or test profile");
        }
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("JSESSIONID");
        serializer.setCookiePath("/");
        if (properties.getCookieDomain() != null) {
            serializer.setDomainName(properties.getCookieDomain());
        }
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(properties.isCookieSecure());
        serializer.setSameSite(properties.getCookieSameSite());
        return serializer;
    }
}
