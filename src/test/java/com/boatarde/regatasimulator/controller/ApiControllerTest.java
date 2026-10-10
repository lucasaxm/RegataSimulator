package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.configuration.CorsConfig;
import com.boatarde.regatasimulator.configuration.SecurityConfig;
import com.boatarde.regatasimulator.configuration.SessionConfig;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.GalleryResponse;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import com.boatarde.regatasimulator.adapter.telegram.TelegramRouter;
import com.boatarde.regatasimulator.service.SourceImporterService;
import com.boatarde.regatasimulator.service.SourceService;
import com.boatarde.regatasimulator.service.TemplateService;
import com.boatarde.regatasimulator.service.ModerationService;
import com.boatarde.regatasimulator.application.TelegramGateway;
import com.opencsv.exceptions.CsvException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.User;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({SourceController.class, TemplateController.class})
@ContextConfiguration(classes = {SourceController.class, TemplateController.class, ApiExceptionHandler.class,
    SecurityConfig.class, CorsConfig.class, SessionConfig.class, ModerationService.class})
@org.springframework.context.annotation.Import(ApiControllerTest.AuditTestConfiguration.class)
@ActiveProfiles("test")
class ApiControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private SourceService sources;
    @MockBean private TemplateService templates;
    @MockBean private SourceImporterService importer;
    @MockBean private TelegramRouter router;
    @MockBean private RegataSimulatorBot bot;
    @MockBean private TelegramGateway telegram;
    @MockBean private com.boatarde.regatasimulator.repository.AuditRepository audits;
    @TempDir private Path storage;

    @org.springframework.boot.test.context.TestConfiguration
    static class AuditTestConfiguration {
        @org.springframework.context.annotation.Bean
        com.boatarde.regatasimulator.repository.MetadataUnitOfWork metadata() { return Runnable::run; }
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() { return java.time.Clock.systemUTC(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=0", "page=-1", "page=abc", "page=2147483648", "perPage=0", "perPage=-1",
        "perPage=101", "perPage=abc", "status=WRONG", "userId=abc"})
    void invalidListParametersReturnGeneric400BeforeServices(String query) throws Exception {
        for (String type : List.of("sources", "templates")) {
            mvc.perform(admin(get("/api/" + type + "?" + query))).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("detail").value("Requisição inválida."));
        }
        verifyNoInteractions(sources, templates, bot, router);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"page\":0,\"perPage\":12}", "{\"page\":1,\"perPage\":101}",
        "{\"page\":1,\"perPage\":0}", "{\"page\":\"abc\",\"perPage\":12}", "{}", "null", "{"})
    void invalidSearchBodiesReturn400(String body) throws Exception {
        mvc.perform(admin(post("/api/sources/search").contentType(MediaType.APPLICATION_JSON).content(body)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("detail").value("Requisição inválida."));
        verifyNoInteractions(sources, templates, bot, router);
    }

    @Test
    void longQueryAndReasonAreRejected() throws Exception {
        mvc.perform(admin(post("/api/sources/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"page\":1,\"perPage\":12,\"query\":\"" + "x".repeat(201) + "\"}")))
            .andExpect(status().isBadRequest());
        for (String type : List.of("sources", "templates")) {
            mvc.perform(admin(post("/api/" + type + "/review").contentType(MediaType.APPLICATION_JSON)
                    .content(review(type, UUID.randomUUID(), true, "x".repeat(1001)))))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(sources, templates, bot, router);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"sourceId\":null,\"templateId\":null,\"approved\":true,\"reason\":\"\"}",
        "{\"sourceId\":\"invalid\",\"templateId\":\"invalid\",\"approved\":true,\"reason\":\"\"}"})
    void invalidReviewIdsAndNullBodiesReturn400(String body) throws Exception {
        for (String type : List.of("sources", "templates")) {
            mvc.perform(admin(post("/api/" + type + "/review").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(sources, templates, bot, router);
    }

    @Test
    void missingOrNullApprovalAndReasonAreInvalid() throws Exception {
        for (String type : List.of("sources", "templates")) {
            String valid = review(type, UUID.randomUUID(), true, "");
            for (String body : List.of(valid.replace("\"approved\":true,", ""),
                valid.replace("\"approved\":true", "\"approved\":null"), valid.replace("\"reason\":\"\"", "\"reason\":null"))) {
                mvc.perform(admin(post("/api/" + type + "/review").contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(sources, templates, bot, router);
    }

    @Test
    void validPaginationBoundaryAndOptionalNullQueryRemainSupported() throws Exception {
        when(sources.getSources(1, 100, null, null)).thenReturn(new GalleryResponse<>(List.of(), 0));
        when(templates.getTemplates(1, 100, null, null)).thenReturn(new GalleryResponse<>(List.of(), 0));
        when(sources.search(any())).thenReturn(new GalleryResponse<>(List.of(), 0));
        mvc.perform(admin(get("/api/sources?perPage=100"))).andExpect(status().isOk());
        mvc.perform(admin(get("/api/templates?perPage=100"))).andExpect(status().isOk());
        mvc.perform(admin(post("/api/sources/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"page\":1,\"perPage\":100,\"query\":null}"))).andExpect(status().isOk());
    }

    @Test
    void missingIdsReturn404AndMalformedPathIdsReturn400() throws Exception {
        UUID id = UUID.randomUUID();
        when(sources.getSource(id)).thenReturn(Optional.empty());
        when(templates.getTemplate(id)).thenReturn(Optional.empty());
        for (String type : List.of("sources", "templates")) {
            mvc.perform(admin(get("/api/" + type + "/invalid.json"))).andExpect(status().isBadRequest());
            mvc.perform(admin(get("/api/" + type + "/" + id + ".json"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("detail").value("Item não encontrado."));
            mvc.perform(admin(get("/api/" + type + "/" + id + ".png"))).andExpect(status().isNotFound());
            mvc.perform(admin(delete("/api/" + type + "/" + id))).andExpect(status().isNotFound());
            mvc.perform(admin(post("/api/" + type + "/review").contentType(MediaType.APPLICATION_JSON)
                    .content(review(type, id, true, "")))).andExpect(status().isNotFound());
        }
        verifyNoInteractions(router, bot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void previouslyReviewedItemsReturn409ForBothDecisions(String state) throws Exception {
        Source source = source();
        source.setStatus(Status.valueOf(state));
        Template template = template();
        template.setStatus(Status.valueOf(state));
        when(sources.getSource(source.getId())).thenReturn(Optional.of(source));
        when(templates.getTemplate(template.getId())).thenReturn(Optional.of(template));
        for (boolean approved : List.of(true, false)) {
            mvc.perform(admin(post("/api/sources/review").contentType(MediaType.APPLICATION_JSON)
                    .content(review("sources", source.getId(), approved, "reason"))))
                .andExpect(status().isConflict());
            mvc.perform(admin(post("/api/templates/review").contentType(MediaType.APPLICATION_JSON)
                    .content(review("templates", template.getId(), approved, "reason"))))
                .andExpect(status().isConflict());
        }
        verifyNoInteractions(router, bot);
    }

    @Test
    void concurrentServiceReviewConflictAlsoReturns409() throws Exception {
        Source source = source();
        when(sources.getSource(source.getId())).thenReturn(Optional.of(source));
        doThrow(new ApplicationFailure(ApplicationFailure.Kind.CONFLICT, "private status context"))
            .when(sources).approveSource(source);
        mvc.perform(admin(post("/api/sources/review").contentType(MediaType.APPLICATION_JSON)
                .content(review("sources", source.getId(), true, ""))))
            .andExpect(status().isConflict()).andExpect(jsonPath("detail").value("O item não está mais em revisão."));
        verifyNoInteractions(router, bot);
    }

    @Test
    void approvalAndRejectionOfNullOriginRemain204() throws Exception {
        Source source = source();
        Template template = template();
        when(sources.getSource(source.getId())).thenReturn(Optional.of(source));
        when(templates.getTemplate(template.getId())).thenReturn(Optional.of(template));
        mvc.perform(admin(post("/api/sources/review").contentType(MediaType.APPLICATION_JSON)
                .content(review("sources", source.getId(), false, "reason")))).andExpect(status().isNoContent());
        mvc.perform(admin(post("/api/templates/review").contentType(MediaType.APPLICATION_JSON)
                .content(review("templates", template.getId(), true, "")))).andExpect(status().isNoContent());
        verify(sources).rejectSource(source);
        verify(templates).approveTemplate(template);
        verifyNoInteractions(router, bot);
    }

    @Test
    void galleryAndSingleDtosPreserveNamesAndGeometryButExcludePrivateMessageAndBindingFields() throws Exception {
        Source source = source();
        source.setMessage(origin());
        source.setPreviewChatId(999L);
        source.setPreviewMessageId(42);
        Template template = template();
        template.setMessage(origin());
        template.setPreviewChatId(999L);
        template.setAreas(List.of(TemplateArea.builder().index(1).source(1).build()));
        when(sources.getSources(1, 12, null, null)).thenReturn(new GalleryResponse<>(List.of(source), 1));
        when(templates.getTemplates(1, 12, null, null)).thenReturn(new GalleryResponse<>(List.of(template), 1));
        when(sources.search(any())).thenReturn(new GalleryResponse<>(List.of(source), 1));
        when(sources.getSource(source.getId())).thenReturn(Optional.of(source));
        when(templates.getTemplate(template.getId())).thenReturn(Optional.of(template));
        for (String type : List.of("sources", "templates")) {
            mvc.perform(admin(get("/api/" + type))).andExpect(status().isOk())
                .andExpect(jsonPath("totalItems").value(1)).andExpect(jsonPath("items[0].message.from.first_name").value("Test"))
                .andExpect(jsonPath("items[0].message.chat").doesNotExist())
                .andExpect(jsonPath("items[0].message.text").doesNotExist())
                .andExpect(jsonPath("items[0].previewChatId").doesNotExist())
                .andExpect(jsonPath("items[0].previewMessageId").doesNotExist());
        }
        mvc.perform(admin(get("/api/templates/" + template.getId() + ".json")))
            .andExpect(status().isOk()).andExpect(jsonPath("areas[0].source").value(1))
            .andExpect(jsonPath("message.photo").doesNotExist()).andExpect(jsonPath("previewChatId").doesNotExist());
        mvc.perform(admin(get("/api/sources/" + source.getId() + ".json")))
            .andExpect(status().isOk()).andExpect(jsonPath("description").value("description"))
            .andExpect(jsonPath("message.chat").doesNotExist());
        mvc.perform(admin(post("/api/sources/search").contentType(MediaType.APPLICATION_JSON)
                .content("{\"page\":1,\"perPage\":12}")))
            .andExpect(status().isOk()).andExpect(jsonPath("items[0].previewChatId").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({"png,image/png", "jpg,image/jpeg", "jpeg,image/jpeg"})
    void imageMimeMatchesBytesNotTheLegacyPngUrl(String extension, String mime) throws Exception {
        Source source = source();
        Template template = template();
        Path image = ImageTestFactory.image(storage.resolve("actual." + extension));
        // Deliberately mismatched filename: sniff the bytes, not the suffix.
        Path renamed = Files.move(image, storage.resolve("mislabeled.png"));
        when(sources.getSource(source.getId())).thenReturn(Optional.of(source));
        when(templates.getTemplate(template.getId())).thenReturn(Optional.of(template));
        when(sources.loadSourceAsResource(source)).thenReturn(new FileSystemResource(renamed));
        when(templates.loadTemplateAsResource(template)).thenReturn(new FileSystemResource(renamed));
        mvc.perform(admin(get("/api/sources/" + source.getId() + ".png"))).andExpect(status().isOk())
            .andExpect(content().contentType(mime)).andExpect(content().bytes(Files.readAllBytes(renamed)));
        mvc.perform(admin(get("/api/templates/" + template.getId() + ".png"))).andExpect(status().isOk())
            .andExpect(content().contentType(mime));
    }

    @Test
    void invalidStoredImageReturnsGeneric500AndMissingMedia404() throws Exception {
        Source source = source();
        when(sources.getSource(source.getId())).thenReturn(Optional.of(source));
        when(sources.loadSourceAsResource(source)).thenReturn(new FileSystemResource(Files.writeString(storage.resolve("bad.png"), "private data")));
        mvc.perform(admin(get("/api/sources/" + source.getId() + ".png"))).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("detail").value("Não foi possível concluir a operação."));
        when(sources.loadSourceAsResource(source)).thenReturn(new FileSystemResource(storage.resolve("missing.png")));
        mvc.perform(admin(get("/api/sources/" + source.getId() + ".png"))).andExpect(status().isNotFound());
    }

    @Test
    void importRetainsListAndAddsTypedPerRowReport() throws Exception {
        Source source = source();
        when(importer.importFromCsv("csv")).thenReturn(List.of(source));
        var row = new SourceImporterService.RowResult(2, "name", SourceImporterService.Outcome.FAILED,
            SourceImporterService.Reason.MEDIA_FAILURE);
        when(importer.importReport("csv")).thenReturn(new SourceImporterService.ImportReport(List.of(source), List.of(row), false));
        mvc.perform(admin(post("/api/sources/import").contentType(MediaType.TEXT_PLAIN).content("csv")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(source.getId().toString()))
            .andExpect(jsonPath("$[0].previewChatId").doesNotExist());
        mvc.perform(admin(post("/api/sources/import/report").contentType(MediaType.TEXT_PLAIN).content("csv")))
            .andExpect(status().isOk()).andExpect(jsonPath("created[0].id").value(source.getId().toString()))
            .andExpect(jsonPath("rows[0].row").value(2)).andExpect(jsonPath("rows[0].reason").value("MEDIA_FAILURE"));
    }

    @Test
    void bothCsvExceptionTypesAre400ButPersistenceErrorsAre500() throws Exception {
        when(importer.importFromCsv("bad")).thenThrow(new IOException("private filesystem context"));
        when(importer.importReport("bad")).thenThrow(new CsvException("private input"));
        for (String url : List.of("/api/sources/import", "/api/sources/import/report")) {
            mvc.perform(admin(post(url).contentType(MediaType.TEXT_PLAIN).content("bad")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("detail").value("Requisição inválida."));
        }
        doThrow(new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "private error")).when(importer).importFromCsv("bad");
        mvc.perform(admin(post("/api/sources/import").contentType(MediaType.TEXT_PLAIN).content("bad")))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("detail").value("Não foi possível concluir a operação."));
    }

    @Test
    void unavailableAndUnexpectedErrorsHaveSafe503And500Responses() throws Exception {
        when(sources.getSources(1, 12, null, null)).thenThrow(new ApplicationFailure(ApplicationFailure.Kind.UNAVAILABLE, "/secret/path"));
        mvc.perform(admin(get("/api/sources"))).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("detail").value("Operação indisponível no momento."));
        doThrow(new IllegalStateException("secret token")).when(sources).getSources(1, 12, null, null);
        mvc.perform(admin(get("/api/sources"))).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("detail").value("Não foi possível concluir a operação."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/sources/review", "/api/templates/review", "/api/sources/import", "/api/sources/import/report",
        "/api/sources/search", "/api/sources/reset_weights", "/api/templates/reset_weights", "/api/templates/initialize_source_ids"})
    void everyPostApiRequiresAdminAndCsrf(String url) throws Exception {
        mvc.perform(post(url).with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(user("viewer").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(sources, templates, importer, router, bot);
    }

    @Test
    void readAndDeleteApisAlsoRequireAdminAndDeleteCsrf() throws Exception {
        for (String type : List.of("sources", "templates")) {
            mvc.perform(get("/api/" + type)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/" + type).with(user("viewer").roles("USER"))).andExpect(status().isForbidden());
            mvc.perform(delete("/api/" + type + "/" + UUID.randomUUID()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        }
        verifyNoInteractions(sources, templates, importer, router, bot);
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.with(user("admin").roles("ADMIN")).with(csrf());
    }

    private String review(String type, UUID id, boolean approved, String reason) {
        return "{\"" + (type.equals("sources") ? "sourceId" : "templateId") + "\":\"" + id
            + "\",\"approved\":" + approved + ",\"reason\":\"" + reason + "\"}";
    }

    private Source source() {
        Source source = new Source();
        source.setId(UUID.randomUUID());
        source.setDescription("description");
        source.setStatus(Status.REVIEW);
        source.setWeight(10);
        return source;
    }

    private Template template() {
        Template template = new Template();
        template.setId(UUID.randomUUID());
        template.setStatus(Status.REVIEW);
        return template;
    }

    private Message origin() {
        User from = new User();
        from.setId(1L);
        from.setFirstName("Test");
        from.setLastName("Author");
        Chat chat = new Chat();
        chat.setId(999L);
        Message message = new Message();
        message.setFrom(from);
        message.setChat(chat);
        message.setText("private submission text");
        return message;
    }
}