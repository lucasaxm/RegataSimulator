package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.controller.AdminController;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminController.class, properties = {
    "regata-simulator.web.allowed-origins[0]=https://boatarde.dev",
    "regata-simulator.web.allowed-origins[1]=http://localhost:3000"})
@Import({SecurityConfig.class, CorsConfig.class, SessionConfig.class})
@ContextConfiguration(classes = {AdminController.class, SecurityConfig.class, CorsConfig.class, SessionConfig.class})
@ActiveProfiles("test")
class WebSecurityFilterTest {
    @Autowired private MockMvc mvc;
    @MockBean private ScheduledTaskService tasks;

    @ParameterizedTest
    @ValueSource(strings = {"https://boatarde.dev", "http://localhost:3000"})
    void permitsExplicitCredentiallessPreflightButNotAnonymousActualRequests(String origin) throws Exception {
        mvc.perform(options("/api/admin/post_meme").header("Origin", origin)
                .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "X-CSRF-TOKEN"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(get("/api/admin/post_meme").header("Origin", origin)).andExpect(status().is3xxRedirection());
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
        mvc.perform(get("/api/admin/post_meme")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/admin/post_meme").header("Origin", "http://localhost").with(user("test")))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
            .andExpect(header().string("Content-Security-Policy",
                "frame-ancestors 'self' https://*.telegram.org https://telegram.org"));
    }
}