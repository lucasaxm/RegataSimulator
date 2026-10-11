package com.boatarde.regatasimulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.configuration.TelegramBotRegistration;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.service.SourceService;
import com.boatarde.regatasimulator.service.SubmissionService;
import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.application.ImageRenderer;
import com.boatarde.regatasimulator.application.SubmissionOrigin;
import com.boatarde.regatasimulator.adapter.telegram.TelegramRouter;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import com.boatarde.regatasimulator.models.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.telegram.telegrambots.meta.api.objects.*;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
    @MockitoBean private RegataSimulatorBot bot;
    @MockitoBean private TelegramBotRegistration registration;
    @MockitoBean private TelegramGateway telegram;
    @MockitoBean private ImageRenderer renderer;
    @Autowired private SubmissionService submissions;
    @Autowired private TelegramRouter router;
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
        assertThat(context.containsBean(org.springframework.scheduling.config.TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
            .isFalse();
        assertThat(context.containsBean("workflowManager")).isFalse();
        assertThat(context.containsBean("routerService")).isFalse();
        assertThat(context.getBeanDefinitionNames()).noneMatch(name -> name.endsWith("Step"));
        assertThat(context.getBeansOfType(com.boatarde.regatasimulator.adapter.telegram.TelegramRouter.class)).hasSize(1);
        assertThat(context.getEnvironment().getProperty("regata-simulator.database.path"))
            .startsWith(storage.toString());
        verifyNoInteractions(registration, bot);
    }

    @Test
    void permitsPublicPagesButProtectsTheSourceApi() throws Exception {
        mvc.perform(get("/login.html")).andExpect(status().isOk());
        mvc.perform(get("/create/index.html")).andExpect(status().isOk());
        mvc.perform(get("/api/sources")).andExpect(status().isUnauthorized());
        verifyNoInteractions(registration, bot);
    }

    @Test
    void deniesCrossOriginPreflightByDefault() throws Exception {
        mvc.perform(options("/api/sources")
                .header("Origin", "https://boatarde.dev")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/sources")
                .header("Origin", "https://untrusted.invalid")
                .header("Access-Control-Request-Method", "POST"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void authenticatesWithSyntheticTestCredentials() throws Exception {
        mvc.perform(post("/api/login")
            .with(csrf())
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

    @ParameterizedTest
    @CsvSource({"source,confirm,true,false", "source,confirm,false,true", "template,confirm,true,false",
        "template,confirm,false,true", "source,cancel,true,false", "template,cancel,true,false"})
    void submissionPreviewCallbackAndHttpModerationShareRealStoredState(String type, String action,
                                                                      boolean approved, boolean notificationFails) throws Exception {
        boolean sourceType = type.equals("source");
        var areas = List.of(TemplateArea.builder().index(1).source(1).topLeft(new AreaCorner(0, 0))
            .topRight(new AreaCorner(20, 0)).bottomRight(new AreaCorner(20, 20)).bottomLeft(new AreaCorner(0, 20)).build());
        CommonEntity pool = sourceType ? new Template() : new Source();
        pool.setId(UUID.randomUUID()); pool.setStatus(Status.APPROVED); pool.setWeight(10);
        if (pool instanceof Template template) template.setAreas(areas);
        else ((Source) pool).setDescription("isolated pool");
        database.insert(pool);
        String poolKind = sourceType ? "template" : "source";
        ImageTestFactory.image(Files.createDirectories(storage.resolve(poolKind + "s").resolve(pool.getId().toString()))
            .resolve(poolKind + ".png"));
        Message original = new Message(); original.setMessageId(111); original.setDate(100);
        Chat chat = new Chat(); chat.setId(123L); chat.setType("private"); original.setChat(chat);
        User owner = new User(); owner.setId(42L); owner.setFirstName("Fixture"); original.setFrom(owner);
        var destination = new TelegramGateway.Destination(123, 111, null);
        when(telegram.download(eq("fixture-file"), any(), anyString())).thenAnswer(call ->
            ImageTestFactory.image(((Path) call.getArgument(1)).resolve((String) call.getArgument(2))));
        when(telegram.sendText(any())).thenReturn(new TelegramGateway.Delivery(123, 222, null));
        Path job = Files.createTempDirectory(storage, "render-");
        when(renderer.render(any())).thenReturn(new ImageRenderer.RenderedImage(ImageTestFactory.image(job.resolve("final_output.png")), job));
        when(telegram.sendPhoto(any())).thenAnswer(call -> {
            TelegramGateway.Photo photo = call.getArgument(0);
            assertThat(photo.file()).exists();
            assertThat(photo.buttons().type()).isEqualTo(type);
            return new TelegramGateway.Delivery(123, 333, null);
        });
        var upload = new SubmissionService.Upload("fixture-file", "upload.png", Author.builder().id(42L).firstName("Fixture").build(),
            new SubmissionOrigin(destination, original));
        var result = sourceType ? submissions.submitSource(new SubmissionService.SourceSubmission("isolated " + UUID.randomUUID(), upload))
            : submissions.submitTemplate(new SubmissionService.TemplateSubmission(areas, upload));
        Class<? extends CommonEntity> entityType = sourceType ? Source.class : Template.class;
        CommonEntity submitted = database.findById(result.id(), entityType);
        assertThat(submitted.getStatus()).isEqualTo(Status.REVIEW);
        assertThat(submitted.getPreviewMessageId()).isEqualTo(333);
        assertThat(submitted.getMessage().getMessageId()).isEqualTo(111);
        assertThat(database.<Author>findById(42L, Author.class).getFirstName()).isEqualTo("Fixture");
        assertThat(job).doesNotExist();
        Path uploaded = storage.resolve(type + "s").resolve(result.id().toString()).resolve(type + ".png");
        assertThat(uploaded).exists();
        Message preview = new Message(); preview.setChat(chat); preview.setMessageId(333);
        PhotoSize photo = new PhotoSize(); photo.setFileId("fixture-preview"); preview.setPhoto(List.of(photo));
        CallbackQuery callback = new CallbackQuery(); callback.setId("fixture-callback"); callback.setFrom(owner);
        callback.setMessage(preview); callback.setData(result.id() + ":" + type + ":" + action);
        Update update = new Update(); update.setCallbackQuery(callback);
        router.route(update, "fixture_bot");
        JsonDBTemplate reopened = new JsonDBTemplate(storage.resolve("db").toString(), "com.boatarde.regatasimulator.models");
        CommonEntity stored = reopened.findById(result.id(), entityType);
        if (action.equals("cancel")) {
            assertThat(stored).isNull(); assertThat(uploaded).doesNotExist();
            verify(telegram).deleteMessage(123, 333);
        } else {
            assertThat(stored.getStatus()).isEqualTo(Status.REVIEW);
            assertThat(stored.getPreviewMessageId()).isNull();
            verify(telegram).forwardPreview(anyLong(), eq("fixture-preview"), contains(result.id().toString()));
            clearInvocations(telegram);
            router.route(update, "fixture_bot");
            verify(telegram).acknowledge("fixture-callback", com.boatarde.regatasimulator.service.ReviewCallbackService.REJECTED);
            verifyNoMoreInteractions(telegram);
            if (notificationFails) doThrow(new IllegalStateException("fixture transport failure")).when(telegram).sendText(any());
            String body = "{\"" + type + "Id\":\"" + result.id() + "\",\"approved\":" + approved + ",\"reason\":\"fixture reason\"}";
            var request = post("/api/" + type + "s/review").contentType(MediaType.APPLICATION_JSON).content(body);
            mvc.perform(request.with(user("viewer").roles("USER")).with(csrf())).andExpect(status().isForbidden());
            mvc.perform(post("/api/" + type + "s/review").with(user("admin").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
            var response = mvc.perform(post("/api/" + type + "s/review").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
            if (notificationFails) response.andExpect(header().string("X-Notification-Status", "failed"));
            assertThat(database.findById(result.id(), entityType).getStatus())
                .isEqualTo(approved ? Status.APPROVED : Status.REJECTED);
            mvc.perform(post("/api/" + type + "s/review").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
            assertThat(uploaded).exists();
        }
        assertThat(database.findAll(Meme.class)).isEmpty();
        verifyNoInteractions(registration, bot);
    }
}