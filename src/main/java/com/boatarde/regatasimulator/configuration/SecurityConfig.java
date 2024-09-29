package com.boatarde.regatasimulator.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final String username;
    private final String password;
    private final boolean sslEnabled;

    public SecurityConfig(@Value("${web-admin.username}") String username,
                          @Value("${web-admin.password}") String password,
                          @Value("${server.ssl.enabled:false}") boolean sslEnabled) {
        this.username = username;
        this.password = password;
        this.sslEnabled = sslEnabled;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (sslEnabled) {
            http.requiresChannel(channel -> channel
                .anyRequest().requiresSecure());
        }

        http.authorizeHttpRequests((requests) -> requests
                .requestMatchers("/api/login", "/gallery/login.html", "/create/**", "/gallery/*.js", "/gallery/*.css")
                .permitAll()
                .anyRequest().authenticated()
            )
            .formLogin((form) -> form
                .loginPage("/gallery/login.html")
                .loginProcessingUrl("/api/login")
                .defaultSuccessUrl("/gallery/gallery.html", true)
                .failureUrl("/gallery/login.html?error=true")
            )
            .logout((logout) -> logout
                .logoutUrl("/api/logout")
                .logoutSuccessUrl("/gallery/login.html")
            )
            .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails user = User.withDefaultPasswordEncoder()
            .username(username)
            .password(password)
            .roles("USER")
            .build();

        return new InMemoryUserDetailsManager(user);
    }
}