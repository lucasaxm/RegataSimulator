package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.controller.AdminController;
import com.boatarde.regatasimulator.controller.CsrfController;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import com.boatarde.regatasimulator.service.ScheduledTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(controllers = {AdminController.class, CsrfController.class}, properties = {
    "regata-simulator.web.allowed-origins[0]=https://boatarde.dev",
    "regata-simulator.web.allowed-origins[1]=http://localhost:3000"})
@Import({SecurityConfig.class, CorsConfig.class, SessionConfig.class})
@ContextConfiguration(classes = {AdminController.class, CsrfController.class, SecurityConfig.class, CorsConfig.class, SessionConfig.class})
@ActiveProfiles("test")
class WebSecurityFilterTest {
    @Autowired private MockMvc mvc;
    @MockBean private ScheduledTaskService tasks;
    @Autowired private ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"https://boatarde.dev", "http://localhost:3000"})
    void permitsExplicitCredentiallessPreflightButNotAnonymousActualRequests(String origin) throws Exception {
        mvc.perform(options("/api/admin/post_meme").header("Origin", origin)
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "X-CSRF-TOKEN"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(get("/api/admin/post_meme").header("Origin", origin)).andExpect(status().isUnauthorized());
        verifyNoInteractions(tasks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://boatarde.dev.evil.invalid", "https://evilboatarde.dev", "http://boatarde.dev",
        "https://boatarde.dev:8443", "http://localhost:3001", "null"})
    void rejectsLookalikesAndWrongSchemesOrPorts(String origin) throws Exception {
        mvc.perform(options("/api/admin/post_meme").header("Origin", origin)
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void noOriginRequiresAuthenticationAndSameOriginNeedsNoCorsGrant() throws Exception {
        mvc.perform(get("/api/admin/post_meme")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/post_meme").header("Origin", "http://localhost").with(user("test").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
            .andExpect(header().string("Content-Security-Policy",
                "frame-ancestors 'self' https://*.telegram.org https://telegram.org"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"post_meme", "create_backup"})
    void adminOperationsArePostOnlyAndRequireAdminAndCsrf(String operation) throws Exception {
        String url = "/api/admin/" + operation;
        mvc.perform(get(url).with(user("admin").roles("ADMIN"))).andExpect(status().isMethodNotAllowed());
        mvc.perform(post(url).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user("admin").roles("ADMIN")).with(csrf().useInvalidToken()))
            .andExpect(status().isForbidden()).andExpect(jsonPath("status").value(403));
        mvc.perform(post(url).with(user("viewer").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(tasks);
        mvc.perform(post(url).with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
        if (operation.equals("post_meme")) verify(tasks).generateMeme();
        else verify(tasks).createBackup();
    }

    @Test
    void browserPagesRedirectButApisReturnGenericProblems() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/admin/post_meme")).andExpect(status().isUnauthorized())
            .andExpect(header().string("Content-Type", "application/problem+json"))
            .andExpect(jsonPath("detail").value("Autenticação necessária."));
    }

    @Test
    void loginAndLogoutRequireCsrfAndXorTokenRefreshAcrossAuthentication() throws Exception {
        mvc.perform(post("/api/login")).andExpect(status().isForbidden());
        var initial = mvc.perform(get("/api/csrf")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        Cookie anonymous = initial.getResponse().getCookie("JSESSIONID");
        String before = mapper.readTree(initial.getResponse().getContentAsString()).get("token").asText();
        var login = mvc.perform(post("/api/login").cookie(anonymous).header("X-CSRF-TOKEN", before)
                .header("X-Requested-With", "XMLHttpRequest").param("username", "phase0-admin")
                .param("password", "phase0-test-password"))
            .andExpect(status().isNoContent()).andReturn();
        Cookie authenticated = login.getResponse().getCookie("JSESSIONID");
        assertThat(authenticated).isNotNull();
        assertThat(authenticated.getValue()).isNotEqualTo(anonymous.getValue());
        mvc.perform(post("/api/admin/post_meme").cookie(authenticated).header("X-CSRF-TOKEN", before))
            .andExpect(status().isForbidden());
        var refreshed = mvc.perform(get("/api/csrf").cookie(authenticated)).andExpect(status().isOk()).andReturn();
        String after = mapper.readTree(refreshed.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/admin/post_meme").cookie(authenticated).header("X-CSRF-TOKEN", after))
            .andExpect(status().isOk());
        mvc.perform(post("/api/logout").cookie(authenticated)).andExpect(status().isForbidden());
        mvc.perform(post("/api/logout").cookie(authenticated).header("X-CSRF-TOKEN", after)
                .header("X-Requested-With", "XMLHttpRequest"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/post_meme").cookie(authenticated)).andExpect(status().isUnauthorized());
        var loggedOut = mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andReturn();
        Cookie fresh = loggedOut.getResponse().getCookie("JSESSIONID");
        mvc.perform(post("/api/login").cookie(fresh).header("X-CSRF-TOKEN", after))
            .andExpect(status().isForbidden());
        verify(tasks).generateMeme();
    }

    @Test
    void incorrectPasswordReturns401NotASuccessfulRedirectedLoginPage() throws Exception {
        mvc.perform(post("/api/login").with(csrf()).header("X-Requested-With", "XMLHttpRequest")
                .param("username", "phase0-admin").param("password", "wrong"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("status").value(401));
        verifyNoInteractions(tasks);
    }
}