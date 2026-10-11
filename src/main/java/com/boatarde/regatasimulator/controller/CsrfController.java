package com.boatarde.regatasimulator.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    public record TokenResponse(String headerName, String parameterName, String token) { }

    @GetMapping("/api/csrf")
    public ResponseEntity<TokenResponse> csrf(CsrfToken csrf) {
        // Access materializes the deferred token. Security 6.2 returns its XOR-masked value.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new TokenResponse(csrf.getHeaderName(), csrf.getParameterName(), csrf.getToken()));
    }
}