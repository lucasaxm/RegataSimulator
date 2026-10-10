package com.boatarde.regatasimulator.flows.simulator;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.WorkflowManager;
import com.boatarde.regatasimulator.flows.WorkflowStepRegistration;
import com.boatarde.regatasimulator.models.AreaCorner;
import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import com.boatarde.regatasimulator.service.RouterService;
import com.boatarde.regatasimulator.service.SourceService;
import com.boatarde.regatasimulator.repository.jsondb.JsonDbSourceRepository;
import com.boatarde.regatasimulator.util.TelegramUtils;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Runs the real synchronous runner and production steps; only external boundaries are faked. */
class MemeWorkflowTest {

    @TempDir Path root;
    private Path templatesRoot;
    private JsonDBTemplate database;
    private RegataSimulatorBot bot;
    private RouterService router;
    private final Map<UUID, Source> sources = new LinkedHashMap<>();
    private final Map<UUID, Template> templates = new LinkedHashMap<>();
    private final List<Meme> history = new ArrayList<>();
    private final List<SendPhoto> sent = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException, TelegramApiException {
        Path sourcesRoot = Files.createDirectories(root.resolve("sources"));
        templatesRoot = Files.createDirectories(root.resolve("templates"));
        database = mock(JsonDBTemplate.class);
        bot = mock(RegataSimulatorBot.class);
        when(database.findAndModify(anyString(), any(io.jsondb.query.Update.class), eq(Source.class)))
            .thenAnswer(invocation -> bindPreview(sources.values(), invocation.getArgument(0), invocation.getArgument(1)));
        when(database.findAndModify(anyString(), any(io.jsondb.query.Update.class), eq(Template.class)))
            .thenAnswer(invocation -> bindPreview(templates.values(), invocation.getArgument(0), invocation.getArgument(1)));
        when(database.findAll(Source.class)).thenAnswer(invocation -> new ArrayList<>(sources.values()));
        when(database.findAll(Meme.class)).thenAnswer(invocation -> new ArrayList<>(history));
        when(database.find(anyString(), eq(Source.class)))
            .thenAnswer(invocation -> new ArrayList<>(sources.values().stream().filter(s -> s.getStatus() == Status.APPROVED).toList()));
        when(database.find(anyString(), eq(Template.class)))
            .thenAnswer(invocation -> new ArrayList<>(templates.values().stream().filter(t -> t.getStatus() == Status.APPROVED).toList()));
        doAnswer(invocation -> { Source source = invocation.getArgument(0); sources.put(source.getId(), source); return null; })
            .when(database).insert(any(Source.class));
        doAnswer(invocation -> { Template template = invocation.getArgument(0); templates.put(template.getId(), template); return null; })
            .when(database).insert(any(Template.class));
        doAnswer(invocation -> { history.add(invocation.getArgument(0)); return null; }).when(database).insert(any(Meme.class));
        when(bot.execute(any(SendMessage.class))).thenReturn(message(222));
        for (int i = 0; i < 3; i++) {
            Source source = new Source();
            source.setId(UUID.randomUUID()); source.setDescription("fixture " + i);
            source.setStatus(Status.APPROVED); source.setWeight(10);
            sources.put(source.getId(), source);
            Files.writeString(Files.createDirectories(sourcesRoot.resolve(source.getId().toString())).resolve("source.png"), "fake source");
        }
        seedTemplate(List.of(area(1, 1)));
        seedTemplate(List.of(area(1, 1), area(2, 2)));
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC);
        WorkflowManager manager = new WorkflowManager(List.of(
            new CreateSourceStep(sourcesRoot.toString(), database, 10, new SourceService(new JsonDbSourceRepository(database))),
            new CreateTemplateStep(templatesRoot.toString(), database, 10),
            new GetRandomTemplateStep(templatesRoot.toString(), database),
            new GetRandomSourceStep(sourcesRoot.toString(), database, clock),
            new FakeImageMagickStep(), new SendMemeStep(999L, database)));
        router = new RouterService(manager, List.of());
    }

    @Test
    void completeSourceSubmissionPreviewKeepsSubmittedSourceAndDoesNotPublishHistory() throws Exception {
        try (MockedStatic<TelegramUtils> telegram = externalTelegram()) {
            router.startFlow(submission("source: new source"), bot, WorkflowAction.CREATE_SOURCE);

            Source submitted = sources.values().stream().filter(s -> s.getStatus() == Status.REVIEW).findFirst().orElseThrow();
            assertThat(submitted.getDescription()).isEqualTo("new source");
            assertThat(submitted.getWeight()).isEqualTo(10);
            assertThat(submitted.getPreviewChatId()).isEqualTo(1234L);
            assertThat(submitted.getPreviewMessageId()).isEqualTo(333);
            assertThat(submitted.getMessage().getMessageId()).isEqualTo(111);
            assertPreviewKeyboard(submitted.getId(), "source");
            verify(database).upsert(any(Author.class));
            verify(database, never()).upsert(any(Source.class));
            assertThat(history).isEmpty();
            try (var paths = Files.walk(root)) {
                assertThat(paths.filter(p -> p.getFileName().toString().equals("final_output.png")).findAny()).isEmpty();
            }
        }
    }

    @Test
    void completeTemplateSubmissionPreviewUsesItsGeometryAndDoesNotPublishHistory() {
        String csv = """
            Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background
            1,1,0,0,100,0,100,100,0,100,1
            2,2,100,0,200,0,200,100,100,100,0
            """.stripTrailing();
        try (MockedStatic<TelegramUtils> telegram = externalTelegram()) {
            router.startFlow(submission(csv), bot, WorkflowAction.CREATE_TEMPLATE);

            Template submitted = templates.values().stream().filter(t -> t.getStatus() == Status.REVIEW).findFirst().orElseThrow();
            assertThat(submitted.getAreas()).hasSize(2);
            assertThat(submitted.getWeight()).isEqualTo(10);
            assertThat(submitted.getPreviewChatId()).isEqualTo(1234L);
            assertThat(submitted.getPreviewMessageId()).isEqualTo(333);
            assertThat(submitted.getMessage().getMessageId()).isEqualTo(111);
            assertPreviewKeyboard(submitted.getId(), "template");
            assertThat(history).isEmpty();
            verify(database, never()).upsert(any(Template.class));
        }
    }

    @Test
    void completePublishingSelectsApprovedMediaRendersSendsAndRecordsOrderedSources() {
        try (MockedStatic<TelegramUtils> telegram = externalTelegram()) {
            router.startFlow(null, bot, WorkflowAction.GET_RANDOM_TEMPLATE);

            assertThat(sent).hasSize(1);
            assertThat(sent.getFirst().getChatId()).isEqualTo("999");
            assertThat(sent.getFirst().getReplyMarkup()).isNull();
            assertThat(history).hasSize(1);
            Meme meme = history.getFirst();
            Template template = templates.get(meme.getTemplateId());
            assertThat(template.getStatus()).isEqualTo(Status.APPROVED);
            assertThat(template.getWeight()).isEqualTo(9);
            assertThat(meme.getSourceIds()).hasSize(template.getAreas().size()).doesNotHaveDuplicates();
            assertThat(meme.getSourceIds()).allSatisfy(id -> {
                assertThat(sources.get(id).getStatus()).isEqualTo(Status.APPROVED);
                assertThat(sources.get(id).getWeight()).isEqualTo(9);
            });
            assertThat(sent.getFirst().getPhoto().getNewMediaFile()).doesNotExist();
        }
    }

    private MockedStatic<TelegramUtils> externalTelegram() {
        MockedStatic<TelegramUtils> telegram = mockStatic(TelegramUtils.class);
        telegram.when(() -> TelegramUtils.downloadTelegramFile(eq(bot), eq("synthetic-file"), any(Path.class), anyString()))
            .thenAnswer(invocation -> ImageTestFactory.image(((Path) invocation.getArgument(2)).resolve((String) invocation.getArgument(3))));
        telegram.when(() -> TelegramUtils.executeSendMediaBotMethod(eq(bot), any(SendPhoto.class)))
            .thenAnswer(invocation -> {
                SendPhoto photo = invocation.getArgument(1);
                assertThat(photo.getPhoto().getNewMediaFile()).exists();
                sent.add(photo);
                return message(333);
            });
        return telegram;
    }

    private <T extends CommonEntity> T bindPreview(java.util.Collection<T> items, String query,
                                                 io.jsondb.query.Update changes) {
        T item = items.stream().filter(candidate -> query.contains(candidate.getId().toString())
            && candidate.getStatus() == Status.REVIEW).findFirst().orElse(null);
        if (item != null) {
            item.setPreviewChatId((Long) changes.getUpdateData().get("previewChatId"));
            item.setPreviewMessageId((Integer) changes.getUpdateData().get("previewMessageId"));
        }
        return item;
    }

    private void assertPreviewKeyboard(UUID id, String type) {
        assertThat(sent).hasSize(1);
        SendPhoto photo = sent.getFirst();
        assertThat(photo.getChatId()).isEqualTo("1234");
        assertThat(photo.getReplyToMessageId()).isEqualTo(111);
        InlineKeyboardMarkup keyboard = (InlineKeyboardMarkup) photo.getReplyMarkup();
        assertThat(keyboard.getKeyboard().getFirst().getFirst().getCallbackData())
            .isEqualTo(id + ":" + type + ":confirm");
        assertThat(keyboard.getKeyboard().getLast().getFirst().getCallbackData())
            .isEqualTo(id + ":" + type + ":cancel");
        assertThat(photo.getPhoto().getNewMediaFile()).doesNotExist();
    }

    private Update submission(String caption) {
        Message message = message(111);
        Document document = new Document();
        document.setFileId("synthetic-file"); document.setFileName("upload.png"); document.setMimeType("image/png");
        message.setDocument(document); message.setCaption(caption);
        Update update = new Update(); update.setMessage(message); return update;
    }

    private Message message(int id) {
        Chat chat = new Chat(); chat.setId(1234L); chat.setType("private");
        User user = new User(); user.setId(42L); user.setFirstName("Fixture author"); user.setIsBot(false);
        Message message = new Message(); message.setChat(chat); message.setFrom(user); message.setMessageId(id); message.setDate(100);
        return message;
    }

    private void seedTemplate(List<TemplateArea> areas) throws IOException {
        Template template = new Template(); template.setId(UUID.randomUUID()); template.setStatus(Status.APPROVED);
        template.setWeight(10); template.setAreas(areas); templates.put(template.getId(), template);
        Files.writeString(Files.createDirectories(templatesRoot.resolve(template.getId().toString())).resolve("template.png"), "fake template");
    }

    private TemplateArea area(int index, int source) {
        return TemplateArea.builder().index(index).source(source).background(true)
            .topLeft(corner(0, 0)).topRight(corner(100, 0)).bottomRight(corner(100, 100)).bottomLeft(corner(0, 100)).build();
    }

    private AreaCorner corner(int x, int y) { return AreaCorner.builder().x(x).y(y).build(); }

    @WorkflowStepRegistration(WorkflowAction.BUILD_MEME_STEP)
    private static class FakeImageMagickStep extends BuildMemeStep {
        FakeImageMagickStep() { super("unused-magick"); }

        @Override protected Process startProcess(ProcessBuilder builder) throws IOException {
            Process process = mock(Process.class);
            when(process.getInputStream()).thenReturn(new ByteArrayInputStream("400 300\n".getBytes(StandardCharsets.UTF_8)));
            when(process.getErrorStream()).thenReturn(java.io.InputStream.nullInputStream());
            try {
                when(process.waitFor(org.mockito.ArgumentMatchers.anyLong(), eq(java.util.concurrent.TimeUnit.MILLISECONDS)))
                    .thenReturn(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            if (!builder.command().contains("identify")) Files.writeString(Path.of(builder.command().getLast()), "fake output");
            return process;
        }
    }
}