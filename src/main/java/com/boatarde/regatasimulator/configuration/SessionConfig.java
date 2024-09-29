package com.boatarde.regatasimulator.configuration;

import org.springframework.beans.factory.annotation.Value;
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

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;


    @Bean
    public MapSessionRepository sessionRepository() {
        return new MapSessionRepository(new ConcurrentHashMap<>());
    }

    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("JSESSIONID");
        serializer.setCookiePath("/");

        if (sslEnabled) {
            serializer.setUseSecureCookie(true);
            serializer.setSameSite("None");
        } else {
            serializer.setUseSecureCookie(false);
            serializer.setSameSite("Lax");
        }

        // Uncomment and adjust if needed for your domain
        // serializer.setDomainName("boatarde.dev.br");

        return serializer;
    }
}
