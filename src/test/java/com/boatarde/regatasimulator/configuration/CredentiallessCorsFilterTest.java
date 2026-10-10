package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.controller.AdminController;
import com.boatarde.regatasimulator.service.ScheduledTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminController.class, properties = {
    "regata-simulator.web.allowed-origins[0]=https://example.com",
    "regata-simulator.web.allow-credentials=false"})
@ContextConfiguration(classes = {AdminController.class, SecurityConfig.class, CorsConfig.class, SessionConfig.class})
@ActiveProfiles("test")
class CredentiallessCorsFilterTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ScheduledTaskService tasks;

    @Test
    void credentiallessCorsGrantsOnlyTheExactOriginAndNeverGrantsAuthentication() throws Exception {
        mvc.perform(options("/api/admin/post_meme").header("Origin", "https://example.com")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://example.com"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(options("/api/admin/post_meme").header("Origin", "https://example.com.evil.invalid")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/post_meme").header("Origin", "https://example.com"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(tasks);
        mvc.perform(post("/api/admin/post_meme").header("Origin", "https://example.com")
                .with(user("admin").roles("ADMIN")).with(csrf()))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        verify(tasks).generateAdminMeme();
    }
}