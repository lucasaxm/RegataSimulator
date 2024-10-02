package com.boatarde.regatasimulator.configuration;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final String username;
    private final String password;

    public SecurityConfig(@Value("${web-admin.username}") String username,
                          @Value("${web-admin.password}") String password) {
        this.username = username;
        this.password = password;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests((requests) -> requests
                .requestMatchers("/api/login", "/login.html", "/create/**", "/*.js", "/*.css")
                .permitAll()
                .anyRequest().authenticated()
            )
            .headers(headers -> headers
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable) // Disable X-Frame-Options
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("frame-ancestors https://*.telegram.org https://telegram.org 'self'")
                )
            )
            .formLogin((form) -> form
                .loginPage("/login.html")
                .loginProcessingUrl("/api/login")
                .successHandler(customAuthenticationSuccessHandler())
                .failureUrl("/login.html?error=true")
            )
            .logout((logout) -> logout
                .logoutUrl("/api/logout")
                .logoutSuccessUrl("/login.html")
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .maximumSessions(1)
                .expiredUrl("/login.html"))
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

    @Bean
    public AuthenticationSuccessHandler customAuthenticationSuccessHandler() {
        return new SimpleUrlAuthenticationSuccessHandler() {
            @Override
            public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                                Authentication authentication) throws IOException, ServletException {
                SavedRequest savedRequest = new HttpSessionRequestCache().getRequest(request, response);
                if (savedRequest == null) {
                    super.onAuthenticationSuccess(request, response, authentication);
                    return;
                }
                String targetUrl = savedRequest.getRedirectUrl();
                getRedirectStrategy().sendRedirect(request, response, targetUrl);
            }
        };
    }
}
