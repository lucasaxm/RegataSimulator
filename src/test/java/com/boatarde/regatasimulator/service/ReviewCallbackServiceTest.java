package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.jsondb.JsonDbSourceRepository;
import com.boatarde.regatasimulator.repository.jsondb.JsonDbTemplateRepository;
import io.jsondb.JsonDBTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.User;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewCallbackServiceTest {
    @TempDir Path root;
    private JsonDBTemplate db;
    private TelegramGateway telegram;
    private ReviewCallbackService callbacks;

    @BeforeEach
    void setUp() throws Exception {
        db = new JsonDBTemplate(Files.createDirectories(root.resolve("db")).toString(), "com.boatarde.regatasimulator.models");
        db.createCollection(Source.class); db.createCollection(Template.class);
        SourceService sources = new SourceService(new JsonDbSourceRepository(db));
        ReflectionTestUtils.setField(sources, "sourcesPathString", root.resolve("sources").toString());
        TemplateService templates = new TemplateService(root.resolve("templates").toString(), new JsonDbTemplateRepository(db));
        telegram = mock(TelegramGateway.class);
        callbacks = new ReviewCallbackService(sources, templates, telegram, 999);
    }

    static Stream<Arguments> rejectedCases() {
        return Stream.of(true, false).flatMap(source -> Stream.of("actor", "approved", "rejected", "origin", "author",
            "chat", "bindingChat", "bindingMessage", "unbound", "missing", "photo")
            .map(mode -> Arguments.of(source, mode)));
    }

    @ParameterizedTest
    @MethodSource("rejectedCases")
    void rejectedCasesOnlyAcknowledgeWithoutMutation(boolean sourceType, String mode) throws Exception {
        CommonEntity item = item(sourceType);
        switch (mode) {
            case "approved" -> item.setStatus(Status.APPROVED);
            case "rejected" -> item.setStatus(Status.REJECTED);
            case "origin" -> item.setMessage(null);
            case "author" -> item.getMessage().setFrom(null);
            case "chat" -> item.getMessage().setChat(null);
            case "bindingChat" -> item.setPreviewChatId(456L);
            case "bindingMessage" -> item.setPreviewMessageId(456);
            case "unbound" -> { item.setPreviewChatId(null); item.setPreviewMessageId(null); }
            default -> { /* Actor/photo/missing scenarios change the request or storage below. */ }
        }
        db.save(item, type(sourceType));
        if (mode.equals("missing")) db.remove(item, type(sourceType));
        var request = request(sourceType, item.getId(), ReviewCallbackService.Action.CONFIRM,
            mode.equals("actor") ? 999 : 42, mode.equals("photo") ? null : "preview-photo");
        callbacks.handle(request);
        verify(telegram).acknowledge("callback", ReviewCallbackService.REJECTED); verifyNoMoreInteractions(telegram);
        assertTrue(Files.exists(image(sourceType, item.getId())));
        CommonEntity stored = db.findById(item.getId(), type(sourceType));
        if (!mode.equals("missing")) assertEquals(item.getStatus(), stored.getStatus());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void confirmPreservesOriginalMessageConsumesBindingAndRejectsReplay(boolean sourceType) throws Exception {
        CommonEntity item = item(sourceType);
        var request = request(sourceType, item.getId(), ReviewCallbackService.Action.CONFIRM, 42, "preview-photo");
        callbacks.handle(request);
        CommonEntity stored = db.findById(item.getId(), type(sourceType));
        assertEquals(Status.REVIEW, stored.getStatus()); assertEquals(111, stored.getMessage().getMessageId());
        assertEquals(17, stored.getWeight()); assertNull(stored.getPreviewMessageId());
        var order = inOrder(telegram);
        order.verify(telegram).forwardPreview(eq(999L), eq("preview-photo"), contains(item.getId().toString()));
        order.verify(telegram).clearKeyboard(123, 333);
        order.verify(telegram).acknowledge("callback", (sourceType ? "Source" : "Template") + " enviado para aprovação.");
        clearInvocations(telegram);
        callbacks.handle(request);
        verify(telegram).acknowledge("callback", ReviewCallbackService.REJECTED); verifyNoMoreInteractions(telegram);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancelDoesNotRequirePhotoAndDeletesOnlyBoundItem(boolean sourceType) throws Exception {
        CommonEntity item = item(sourceType);
        callbacks.handle(request(sourceType, item.getId(), ReviewCallbackService.Action.CANCEL, 42, null));
        assertNull(db.findById(item.getId(), type(sourceType))); assertFalse(Files.exists(image(sourceType, item.getId())));
        verify(telegram).deleteMessage(123, 333);
        verify(telegram).acknowledge("callback", (sourceType ? "Source" : "Template") + " deletado.");
        verifyNoMoreInteractions(telegram);
    }

    @ParameterizedTest
    @CsvSource({"true,CONFIRM", "false,CONFIRM", "true,CANCEL", "false,CANCEL"})
    void concurrentConfirmationHasOneForwardAndOneConsumedReplay(boolean sourceType, ReviewCallbackService.Action replay) throws Exception {
        CommonEntity item = item(sourceType);
        var request = request(sourceType, item.getId(), ReviewCallbackService.Action.CONFIRM, 42, "preview-photo");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> { started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return null; })
            .when(telegram).forwardPreview(anyLong(), anyString(), anyString());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> callbacks.handle(request));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> callbacks.handle(request(sourceType, item.getId(), replay, 42, "preview-photo")));
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        verify(telegram).forwardPreview(anyLong(), anyString(), anyString());
        verify(telegram).acknowledge("callback", ReviewCallbackService.REJECTED);
        CommonEntity stored = db.findById(item.getId(), type(sourceType));
        assertNull(stored.getPreviewMessageId());
    }

    @Test
    void transportFailureLeavesBindingAndAckFailureIsSafe() throws Exception {
        CommonEntity item = item(true);
        doThrow(new IllegalStateException("send failure")).when(telegram).forwardPreview(anyLong(), anyString(), anyString());
        doThrow(new IllegalStateException("ack failure")).when(telegram).acknowledge(anyString(), anyString());
        assertDoesNotThrow(() -> callbacks.handle(request(true, item.getId(), ReviewCallbackService.Action.CONFIRM, 42, "preview-photo")));
        assertEquals(333, db.<Source>findById(item.getId(), Source.class).getPreviewMessageId());
        verify(telegram, never()).clearKeyboard(anyLong(), anyInt());
    }

    private CommonEntity item(boolean sourceType) throws Exception {
        CommonEntity item = sourceType ? new Source() : new Template(); item.setId(UUID.randomUUID());
        item.setStatus(Status.REVIEW); item.setWeight(17); item.setPreviewChatId(123L); item.setPreviewMessageId(333);
        Message original = new Message(); original.setMessageId(111); original.setDate(100);
        Chat chat = new Chat(); chat.setId(123L); original.setChat(chat);
        User user = new User(); user.setId(42L); original.setFrom(user); item.setMessage(original);
        db.insert(item); ImageTestFactory.image(Files.createDirectories(image(sourceType, item.getId()).getParent())
            .resolve(sourceType ? "source.png" : "template.png")); return item;
    }

    private ReviewCallbackService.Request request(boolean source, UUID id, ReviewCallbackService.Action action, long actor, String photo) {
        return new ReviewCallbackService.Request(source ? ModerationService.ItemType.SOURCE : ModerationService.ItemType.TEMPLATE,
            action, id, actor, 123, 333, photo, "@fixture", "callback");
    }
    private Path image(boolean source, UUID id) { return root.resolve(source ? "sources" : "templates").resolve(id.toString())
        .resolve(source ? "source.png" : "template.png"); }
    private Class<? extends CommonEntity> type(boolean source) { return source ? Source.class : Template.class; }
}