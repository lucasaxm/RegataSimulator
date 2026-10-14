package com.boatarde.regatasimulator.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@ContextConfiguration(classes = {WebConfig.class, SecurityConfig.class, CorsConfig.class, SessionConfig.class})
@ActiveProfiles("test")
class CreatorStaticRouteTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"/create/editor", "/create/editor/"})
    void anonymousEditorRoutesForwardOnlyToPackagedEditor(String route) throws Exception {
        mvc.perform(get(route)).andExpect(status().isOk())
            .andExpect(forwardedUrl("/create/editor/index.html"));
    }

    @Test
    void legacyCreatorForwardIsUnchanged() throws Exception {
        mvc.perform(get("/create")).andExpect(status().isOk())
            .andExpect(forwardedUrl("/create/index.html"));
    }

    @Test
    void anonymousGeneratedResourcesMatchManifestAndRetainNotices() throws Exception {
        String base = "static/create/editor/";
        var manifestBytes = new ClassPathResource(base + "manifest.json").getContentAsByteArray();
        var manifest = mapper.readTree(manifestBytes);
        assertThat(manifest.path("schemaVersion").asInt()).isEqualTo(1);
        String script = manifest.path("assets").path("script").asText();
        String style = manifest.path("assets").path("style").asText();
        assertThat(script).matches("editor-[A-Z0-9]+\\.js");
        assertThat(style).matches("editor-[A-Z0-9]+\\.css");
        for (String name : new String[] {"index.html", script, style, "licenses.txt"}) {
            byte[] bytes = new ClassPathResource(base + name).getContentAsByteArray();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertThat(hash).isEqualTo(manifest.path("files").path(name).path("sha256").asText());
            mvc.perform(get("/create/editor/" + name)).andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(header().string("Content-Security-Policy",
                    "frame-ancestors 'self' https://*.telegram.org https://telegram.org"));
        }
        String html = new ClassPathResource(base + "index.html").getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("/create/editor/" + script, "/create/editor/" + style)
            .doesNotContain("__EDITOR_", "cdn.jsdelivr", "unpkg.com");
        assertThat(html).contains("https://telegram.org/js/telegram-web-app.js");
        assertThat(manifest.path("hostedIntegrationScripts").get(0).path("url").asText())
            .isEqualTo("https://telegram.org/js/telegram-web-app.js");
        String notice = new ClassPathResource(base + "licenses.txt").getContentAsString(StandardCharsets.UTF_8);
        assertThat(notice).contains("konva@10.7.1", "Permission is hereby granted", "Copyright");
        assertThat(manifest.path("runtimeDependencies")).hasSize(1);
        assertThat(manifest.path("runtimeDependencies").get(0).path("name").asText()).isEqualTo("konva");
        mvc.perform(get("/create/editor/not-a-resource.js")).andExpect(status().isNotFound());
    }
}