package com.boatarde.regatasimulator.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final String username;
    private final String password;
    private final WebSecurityProperties webProperties;
    private final ObjectMapper objectMapper;

    public SecurityConfig(@Value("${web-admin.username}") String username,
                          @Value("${web-admin.password}") String password, WebSecurityProperties webProperties,
                          ObjectMapper objectMapper) {
        this.username = username;
        this.password = password;
        this.webProperties = webProperties;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AntPathRequestMatcher api = new AntPathRequestMatcher("/api/**");
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(request -> !api.matches(request));
        http
            .cors(cors -> {})
            .authorizeHttpRequests((requests) -> requests
                .requestMatchers("/api/login", "/api/csrf", "/login.html", "/create/**", "/*.js", "/*.css")
                .permitAll()
                .requestMatchers("/api/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .requestCache(cache -> cache.requestCache(requestCache))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, failure) -> {
                    if (api.matches(request)) {
                        writeProblem(response, HttpStatus.UNAUTHORIZED);
                    } else {
                        new LoginUrlAuthenticationEntryPoint("/login.html").commence(request, response, failure);
                    }
                })
                .accessDeniedHandler((request, response, failure) -> {
                    if (api.matches(request)) {
                        writeProblem(response, HttpStatus.FORBIDDEN);
                    } else {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    }
                }))
            .headers(headers -> headers
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable) // Disable X-Frame-Options
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("frame-ancestors " + String.join(" ", webProperties.getFrameAncestors()))
                )
            )
            .formLogin((form) -> form
                .loginPage("/login.html")
                .loginProcessingUrl("/api/login")
                .successHandler(customAuthenticationSuccessHandler())
                .failureHandler((request, response, failure) -> {
                    if (isAjax(request)) {
                        writeProblem(response, HttpStatus.UNAUTHORIZED);
                    } else {
                        response.sendRedirect("/login.html?error=true");
                    }
                })
            )
            .logout((logout) -> logout
                .logoutUrl("/api/logout")
                .logoutSuccessHandler((request, response, authentication) -> {
                    if (isAjax(request)) {
                        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                    } else {
                        response.sendRedirect("/login.html");
                    }
                })
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .maximumSessions(1)
                .expiredSessionStrategy(event -> {
                    if (api.matches(event.getRequest())) {
                        writeProblem(event.getResponse(), HttpStatus.UNAUTHORIZED);
                    } else {
                        event.getResponse().sendRedirect("/login.html");
                    }
                }));

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        UserDetails user = User.builder()
            .username(username)
            .password(encoder.encode(password))
            .roles("ADMIN")
            .build();

        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationSuccessHandler customAuthenticationSuccessHandler() {
        SavedRequestAwareAuthenticationSuccessHandler browser = new SavedRequestAwareAuthenticationSuccessHandler();
        return (request, response, authentication) -> {
            if (isAjax(request)) {
                new HttpSessionRequestCache().removeRequest(request, response);
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            } else {
                browser.onAuthenticationSuccess(request, response, authentication);
            }
        };
    }

    private boolean isAjax(HttpServletRequest request) {
        return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status,
            status == HttpStatus.UNAUTHORIZED ? "Autenticação necessária." : "Acesso negado."));
    }
}
