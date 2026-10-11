package com.boatarde.regatasimulator.configuration;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Installed only inside Security, after CSRF and before credential authentication. */
final class LoginThrottleFilter extends OncePerRequestFilter {
    private final LoginAttemptLimiter limiter;
    LoginThrottleFilter(LoginAttemptLimiter limiter) { this.limiter=limiter; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if (request.getMethod().equals("POST") && request.getRequestURI().equals(request.getContextPath()+"/api/login")
            && !limiter.reserve(request.getRemoteAddr(),request.getParameter("username"))) {
            response.setStatus(429); response.setContentType("application/problem+json"); response.setHeader("Cache-Control","no-store");
            response.getWriter().write("{\"status\":429,\"detail\":\"Não foi possível autenticar agora.\"}");
            return;
        }
        chain.doFilter(request,response);
    }
}