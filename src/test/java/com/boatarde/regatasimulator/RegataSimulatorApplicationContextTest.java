package com.boatarde.regatasimulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.configuration.TelegramBotRegistration;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.service.SourceService;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegataSimulatorApplicationContextTest {

    @TempDir static Path storage;
    @MockBean private RegataSimulatorBot bot;
    @MockBean private TelegramBotRegistration registration;
    @Autowired private ApplicationContext context;
    @Autowired private JsonDBTemplate database;
    @Autowired private SourceService sourceService;
    @Autowired private MockMvc mvc;

    @DynamicPropertySource
    static void temporaryStorage(DynamicPropertyRegistry registry) {
        registry.add("regata-simulator.database.path", () -> directory("db"));
        registry.add("regata-simulator.sources.path", () -> directory("sources"));
        registry.add("regata-simulator.templates.path", () -> directory("templates"));
    }

    private static String directory(String name) {
        try {
            return Files.createDirectories(storage.resolve(name)).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void loadsRealApplicationWithTemporaryCollectionsAndNoExternalSideEffects() {
        for (String collection : new String[]{"users", "templates", "sources", "memes"}) {
            assertThat(database.collectionExists(collection)).isTrue();
        }
        assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
            .isFalse();
        assertThat(context.getEnvironment().getProperty("regata-simulator.database.path"))
            .startsWith(storage.toString());
        verifyNoInteractions(registration, bot);
    }

    @Test
    void permitsPublicPagesButProtectsTheSourceApi() throws Exception {
        mvc.perform(get("/login.html")).andExpect(status().isOk());
        mvc.perform(get("/create/index.html")).andExpect(status().isOk());
        mvc.perform(get("/api/sources")).andExpect(status().is3xxRedirection());
        verifyNoInteractions(registration, bot);
    }

    @Test
    void retainsObservedAllowedAndDisallowedPreflightBehavior() throws Exception {
        mvc.perform(options("/api/sources")
                .header("Origin", "https://boatarde.dev")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "https://boatarde.dev"));
        mvc.perform(options("/api/sources")
                .header("Origin", "https://untrusted.invalid")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void authenticatesWithSyntheticTestCredentials() throws Exception {
        mvc.perform(post("/api/login")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("username", "phase0-admin")
                .param("password", "phase0-test-password"))
            .andExpect(status().is3xxRedirection());
        verifyNoInteractions(registration, bot);
    }

    @Test
    void realJsonDbAndMediaLifecyclePreservesNullableImportedSourceMetadata() throws Exception {
        Source source = new Source();
        source.setId(UUID.randomUUID());
        source.setDescription("isolated imported fixture");
        source.setStatus(Status.REVIEW);
        source.setWeight(10);
        Path media = Files.write(Files.createDirectories(storage.resolve("sources").resolve(source.getId().toString()))
            .resolve("source.png"), new byte[]{1, 2, 3});
        database.insert(source);

        Source loaded = sourceService.getSource(source.getId()).orElseThrow();
        assertThat(loaded.getMessage()).isNull();
        assertThat(sourceService.loadSourceAsResource(loaded).exists()).isTrue();
        sourceService.approveSource(loaded);
        assertThat(sourceService.getSource(source.getId()).orElseThrow().getStatus()).isEqualTo(Status.APPROVED);
        sourceService.deleteSource(loaded);
        assertThat(sourceService.getSource(source.getId())).isEmpty();
        assertThat(media).doesNotExist();
        verifyNoInteractions(registration, bot);
    }
}