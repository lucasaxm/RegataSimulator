package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.application.creatorauth.CreatorSessions;
import com.boatarde.regatasimulator.application.creatorauth.TelegramInitDataVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.filter.CorsFilter;

import java.security.SecureRandom;
import java.time.Clock;

@Configuration
public class CreatorSecurityConfig {
    @Bean
    public TelegramInitDataVerifier telegramInitDataVerifier(
        @Value("${telegram.bots.regata-simulator.token}") String botToken, Clock clock) {
        return new TelegramInitDataVerifier(botToken, clock);
    }

    @Bean
    public CreatorSessions creatorSessions(Clock clock) {
        return new CreatorSessions(clock, new SecureRandom(), 1024);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain creatorSecurityFilterChain(HttpSecurity http, CreatorSessions sessions,
        Clock clock, WebSecurityProperties properties, ObjectMapper mapper) throws Exception {
        http.securityMatcher("/api/creator/**")
            .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .cors(cors -> { })
            .addFilterBefore(new CreatorBearerFilter(sessions,
                new LoginAttemptLimiter(clock, 20, 60000, 2048), mapper), CorsFilter.class)
            .authorizeHttpRequests(requests -> requests
                .requestMatchers(HttpMethod.POST, "/api/creator/session").permitAll()
                .anyRequest().hasRole("CREATOR"))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, failure) ->
                    CreatorHttpProblem.write(mapper, response, HttpStatus.UNAUTHORIZED))
                .accessDeniedHandler((request, response, failure) ->
                    CreatorHttpProblem.write(mapper, response, HttpStatus.FORBIDDEN)))
            .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "frame-ancestors " + String.join(" ", properties.getFrameAncestors()))));
        return http.build();
    }
}
