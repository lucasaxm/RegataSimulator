package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.application.creatorauth.CreatorActor;
import com.boatarde.regatasimulator.application.creatorauth.CreatorSessions;
import com.boatarde.regatasimulator.controller.AdminController;
import com.boatarde.regatasimulator.controller.ApiExceptionHandler;
import com.boatarde.regatasimulator.controller.CreatorSessionController;
import com.boatarde.regatasimulator.controller.CsrfController;
import com.boatarde.regatasimulator.service.ScheduledTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.boatarde.regatasimulator.application.creatorauth.SyntheticInitData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {CreatorSessionController.class, CreatorSecurityFilterTest.Probe.class,
    AdminController.class, CsrfController.class})
@ContextConfiguration(classes = {CreatorSessionController.class, CreatorSecurityFilterTest.Probe.class,
    AdminController.class, CsrfController.class, ApiExceptionHandler.class, SecurityConfig.class,
    CreatorSecurityConfig.class, CorsConfig.class, SessionConfig.class, CreatorSecurityFilterTest.FixedTime.class})
@ActiveProfiles("test")
class CreatorSecurityFilterTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CreatorSessions sessions;
    @MockitoBean ScheduledTaskService tasks;
    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @RestController
    static class Probe {
        @GetMapping("/api/creator/test-protected")
        Map<String, Object> protectedGet(Authentication authentication) {
            CreatorActor actor = (CreatorActor) authentication.getPrincipal();
            return Map.of("id", actor.id(), "authority", authentication.getAuthorities().iterator().next().getAuthority(),
                "credentialsCleared", authentication.getCredentials() == null);
        }
    }

    private MockHttpServletRequestBuilder bootstrap(String body) {
        String ip = "198.51.100." + ADDRESSES.incrementAndGet();
        return post("/api/creator/session").contentType(MediaType.APPLICATION_JSON).content(body)
            .with(request -> { request.setRemoteAddr(ip); return request; });
    }

    private String body(String signed) throws Exception { return mapper.writeValueAsString(Map.of("initData", signed)); }

    private String token() throws Exception {
        var result = mvc.perform(bootstrap(body(valid())))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("tokenType").value("Bearer"))
            .andExpect(jsonPath("expiresAt").value(NOW.plusSeconds(1800).toString())).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(3);
        String token = mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        return token;
    }

    @Test void realBootstrapWithoutCsrfGrantsOnlyServerDerivedCreatorAndPreservesHeaders() throws Exception {
        String token = token();
        mvc.perform(get("/api/creator/test-protected").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("id").value(42))
            .andExpect(jsonPath("authority").value("ROLE_CREATOR"))
            .andExpect(jsonPath("credentialsCleared").value(true))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(header().doesNotExist("X-Frame-Options"))
            .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'self' https://*.telegram.org https://telegram.org"));
        verifyNoInteractions(tasks);
    }

    @Test void invalidSignatureAndMalformedClosedJsonHaveGenericErrors() throws Exception {
        String signedWrong = sign(Map.of("auth_date", Long.toString(NOW.getEpochSecond()), "user", "{\"id\":42}"), "wrong-bot");
        mvc.perform(bootstrap(body(signedWrong))).andExpect(status().isUnauthorized())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("detail").value("Autenticação necessária."))
            .andExpect(header().string("Cache-Control", "no-store"));
        for (String invalid : new String[]{"", "null", "[]", "{}", "{", "{\"initData\":3}",
            "{\"initData\":\"x\",\"id\":42}", "{\"initData\":\"x\",\"initData\":\"y\"}", "{\"initData\":\"x\"} {}"}) {
            mvc.perform(bootstrap(invalid)).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("detail").value("Requisição inválida."));
        }
        mvc.perform(bootstrap("x").contentType(MediaType.TEXT_PLAIN)).andExpect(status().isUnsupportedMediaType())
            .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(tasks);
    }

    @Test void actualBodyBytesAreBoundedDespiteMissingOrFalseContentLength() throws Exception {
        for (String length : new String[]{"-1", "1"}) {
            mvc.perform(bootstrap(" ".repeat(32769)).header("Content-Length", length).header("Transfer-Encoding", "chunked"))
                .andExpect(status().is(413)).andExpect(header().string("Cache-Control", "no-store"));
        }
        // Exactly the admission limit is parsed (then rejected as malformed), not rejected by length.
        mvc.perform(bootstrap(" ".repeat(32768))).andExpect(status().isBadRequest());
    }

    @Test void throttleReservesBeforeMalformedBodyParsingAndDoesNotTrustForwardedHeaders() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(bootstrap("{").with(r -> { r.setRemoteAddr("203.0.113.200"); return r; })
                .header("X-Forwarded-For", "192.0.2." + i)).andExpect(status().isBadRequest());
        }
        mvc.perform(bootstrap(" ".repeat(32769)).with(r -> { r.setRemoteAddr("203.0.113.200"); return r; }))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("detail").value("Não foi possível autenticar agora."));
        mvc.perform(bootstrap(body(valid())).with(r -> { r.setRemoteAddr("203.0.113.201"); return r; }))
            .andExpect(status().isOk());
    }

    @Test void rejectsDuplicateMalformedOversizeOrQueryCredentialsWithoutSessionFallback() throws Exception {
        String token = token();
        for (String header : new String[]{"Basic " + token, "Bearer " + token + " ", "Bearer  " + token,
            "Bearer " + "x".repeat(20000), "Bearer " + token + ",Bearer " + token}) {
            mvc.perform(get("/api/creator/test-protected").header("Authorization", header))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get("/api/creator/test-protected").header("Authorization", "Bearer " + token, "Bearer " + token))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/creator/test-protected").param("access_token", token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/creator/test-protected").cookie(new Cookie("accessToken", token)))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/creator/test-protected").with(user("admin").roles("ADMIN")))
            .andExpect(status().isUnauthorized());
    }

    @Test void actualAdminSessionCookieCannotAuthenticateCreatorAndCreatorCannotAuthenticateAdmin() throws Exception {
        var initial = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        Cookie anonymous = initial.getResponse().getCookie("JSESSIONID");
        String csrfToken = mapper.readTree(initial.getResponse().getContentAsString()).get("token").asText();
        var login = mvc.perform(post("/api/login").cookie(anonymous).header("X-CSRF-TOKEN", csrfToken)
                .header("X-Requested-With", "XMLHttpRequest").param("username", "phase0-admin")
                .param("password", "phase0-test-password"))
            .andExpect(status().isNoContent()).andReturn();
        Cookie admin = login.getResponse().getCookie("JSESSIONID");
        assertThat(admin).isNotNull();
        mvc.perform(get("/api/creator/test-protected").cookie(admin)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/post_meme").cookie(admin)).andExpect(status().isForbidden());
        String token = token();
        mvc.perform(get("/api/creator/test-protected").cookie(admin).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(jsonPath("id").value(42));
        mvc.perform(get("/api/templates").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/post_meme").with(csrf()).header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/post_meme").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        verifyNoInteractions(tasks);
    }

    @Test void corsDenialRemainsForbiddenNoStoreAndNoAuthorizationHeaderExpansion() throws Exception {
        mvc.perform(options("/api/creator/session").header("Origin", "https://evil.invalid")
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "Authorization"))
            .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void fullRegistryFailsClosedWith503WithoutRevokingExistingToken() throws Exception {
        var actor = new CreatorActor(42, null, null, null);
        var retained = sessions.issue(actor);
        while (true) {
            try { sessions.issue(actor); }
            catch (CreatorSessions.CapacityFailure ignored) { break; }
        }
        mvc.perform(bootstrap(body(valid()))).andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/creator/test-protected").header("Authorization", "Bearer " + retained.accessToken()))
            .andExpect(status().isOk());
    }
}
