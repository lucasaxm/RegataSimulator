package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.application.creatorauth.CreatorAuthenticationFailure;
import com.boatarde.regatasimulator.application.creatorauth.CreatorSessions;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** Created only inside the creator chain; intentionally not a servlet-filter bean. */
final class CreatorBearerFilter extends OncePerRequestFilter {
    private final CreatorSessions sessions;
    private final LoginAttemptLimiter limiter;
    private final ObjectMapper mapper;

    CreatorBearerFilter(CreatorSessions sessions, LoginAttemptLimiter limiter, ObjectMapper mapper) {
        this.sessions = sessions;
        this.limiter = limiter;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        boolean bootstrap = "POST".equals(request.getMethod())
            && (request.getContextPath() + "/api/creator/session").equals(request.getRequestURI());
        if (bootstrap && !limiter.reserve(request.getRemoteAddr(), request.getRemoteAddr())) {
            CreatorHttpProblem.write(mapper, response, HttpStatus.TOO_MANY_REQUESTS);
            return;
        }
        // Even an upstream/admin context must never confer creator identity.
        SecurityContextHolder.clearContext();
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (!headers.isEmpty()) {
            try {
                if (headers.size() != 1 || !headers.getFirst().matches("(?i:Bearer) [A-Za-z0-9_-]{43}")) {
                    throw new CreatorAuthenticationFailure();
                }
                var actor = sessions.authenticate(headers.getFirst().substring(7));
                var authentication = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                    List.of(new SimpleGrantedAuthority("ROLE_CREATOR")));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            } catch (CreatorAuthenticationFailure ignored) {
                CreatorHttpProblem.write(mapper, response, HttpStatus.UNAUTHORIZED);
                return;
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
