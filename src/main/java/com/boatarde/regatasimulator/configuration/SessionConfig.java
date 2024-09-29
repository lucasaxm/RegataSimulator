package com.boatarde.regatasimulator.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("JSESSIONID");
        serializer.setCookiePath("/");
        serializer.setDomainName("boatarde.dev.br"); // Set to your specific domain
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(true);
        return serializer;
    }
}
