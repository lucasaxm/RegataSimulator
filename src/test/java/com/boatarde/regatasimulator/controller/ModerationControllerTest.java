package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.models.ReviewSourceBody;
import com.boatarde.regatasimulator.models.ReviewTemplateBody;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.service.SourceImporterService;
import com.boatarde.regatasimulator.service.SourceService;
import com.boatarde.regatasimulator.service.TemplateService;
import com.boatarde.regatasimulator.service.ModerationService;
import com.boatarde.regatasimulator.application.TelegramGateway;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Direct moderation ordering/recovery regressions; full HTTP security is tested separately. */
@ExtendWith(MockitoExtension.class)
class ModerationControllerTest {

    @Mock
    private SourceService sourceService;
    @Mock
    private SourceImporterService importer;
    @Mock
    private TemplateService templateService;
    @Mock
    private RegataSimulatorBot bot;
    @Mock
    private TelegramGateway router;

    private SourceController sources;
    private TemplateController templates;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("test-admin", "unused", List.of()));
        ModerationService moderation = new ModerationService(sourceService, templateService, router);
        sources = new SourceController(sourceService, importer, moderation);
        templates = new TemplateController(templateService, moderation);
    }

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sourceStatusIsCommittedBeforeNotification(boolean approved) {
        Source source = source(origin());
        AtomicReference<Status> persisted = trackSourceCommit(source, approved);
        doAnswer(invocation -> {
            assertEquals(decision(approved), persisted.get());
            assertNotificationUpdate(invocation.getArgument(0), source.getMessage(), approved);
            return null;
        }).when(router).sendText(any(TelegramGateway.Text.class));

        assertEquals(204, sources.reviewSource(sourceReview(source.getId(), approved)).getStatusCode().value());

        verifySourceOrder(source, approved, router);
        assertEquals(decision(approved), persisted.get());
        verifyNoInteractions(bot, importer);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void templateStatusIsCommittedBeforeNotification(boolean approved) {
        Template template = template(origin());
        AtomicReference<Status> persisted = trackTemplateCommit(template, approved);
        doAnswer(invocation -> {
            assertEquals(decision(approved), persisted.get());
            assertNotificationUpdate(invocation.getArgument(0), template.getMessage(), approved);
            return null;
        }).when(router).sendText(any(TelegramGateway.Text.class));

        assertEquals(204, templates.reviewTemplate(templateReview(template.getId(), approved)).getStatusCode().value());

        verifyTemplateOrder(template, approved, router);
        assertEquals(decision(approved), persisted.get());
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sourceNotificationFailureRetainsCommittedStatusAndReportsFailure(boolean approved) {
        Source source = source(origin());
        AtomicReference<Status> persisted = trackSourceCommit(source, approved);
        IllegalStateException failure = new IllegalStateException("notification failed");
        doThrow(failure).when(router).sendText(any(TelegramGateway.Text.class));

        ReviewSourceBody review = sourceReview(source.getId(), approved);
        var response = sources.reviewSource(review);
        assertEquals(204, response.getStatusCode().value());
        assertEquals("failed", response.getHeaders().getFirst("X-Notification-Status"));

        assertEquals(decision(approved), persisted.get());
        assertEquals(decision(approved), source.getStatus());
        verifySourceOrder(source, approved, router);
        verifyNoMoreInteractions(sourceService, router);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void templateNotificationFailureRetainsCommittedStatusAndReportsFailure(boolean approved) {
        Template template = template(origin());
        AtomicReference<Status> persisted = trackTemplateCommit(template, approved);
        IllegalStateException failure = new IllegalStateException("notification failed");
        doThrow(failure).when(router).sendText(any(TelegramGateway.Text.class));

        ReviewTemplateBody review = templateReview(template.getId(), approved);
        var response = templates.reviewTemplate(review);
        assertEquals(204, response.getStatusCode().value());
        assertEquals("failed", response.getHeaders().getFirst("X-Notification-Status"));

        assertEquals(decision(approved), persisted.get());
        assertEquals(decision(approved), template.getStatus());
        verifyTemplateOrder(template, approved, router);
        verifyNoMoreInteractions(templateService, router);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void alreadyApprovedSourceRejectsEitherDecisionWithConflict(boolean approved) {
        Source source = source(origin());
        source.setStatus(Status.APPROVED);
        when(sourceService.getSource(source.getId())).thenReturn(Optional.of(source));

        ReviewSourceBody review = sourceReview(source.getId(), approved);
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> sources.reviewSource(review));

        assertEquals(ApplicationFailure.class, failure.getClass());
        assertEquals("Source no longer in review", failure.getMessage());
        InOrder order = inOrder(sourceService);
        order.verify(sourceService).getSource(source.getId());
        verifyNoMoreInteractions(sourceService);
        verifyNoInteractions(router, bot);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void alreadyApprovedTemplateRejectsEitherDecisionWithConflict(boolean approved) {
        Template template = template(origin());
        template.setStatus(Status.APPROVED);
        when(templateService.getTemplate(template.getId())).thenReturn(Optional.of(template));

        ReviewTemplateBody review = templateReview(template.getId(), approved);
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> templates.reviewTemplate(review));

        assertEquals(ApplicationFailure.class, failure.getClass());
        assertEquals("Template no longer in review", failure.getMessage());
        InOrder order = inOrder(templateService);
        order.verify(templateService).getTemplate(template.getId());
        verifyNoMoreInteractions(templateService);
        verifyNoInteractions(router, bot);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void nullOriginSourceCommitsEitherDecisionWithoutNotification(boolean approved) {
        Source source = source(null);
        AtomicReference<Status> persisted = trackSourceCommit(source, approved);

        assertEquals(204, sources.reviewSource(sourceReview(source.getId(), approved)).getStatusCode().value());

        assertEquals(decision(approved), persisted.get());
        verifyNoInteractions(router, bot);
    }

    @Test
    void nullOriginTemplateApprovalCommitsWithoutNotification() {
        Template template = template(null);
        AtomicReference<Status> persisted = trackTemplateCommit(template, true);

        assertEquals(204, templates.reviewTemplate(templateReview(template.getId(), true)).getStatusCode().value());

        assertEquals(Status.APPROVED, persisted.get());
        verifyNoInteractions(router, bot);
    }

    @Test
    void nullOriginTemplateRejectionCommitsWithoutStartingNotification() {
        Template template = template(null);
        AtomicReference<Status> persisted = trackTemplateCommit(template, false);
        TemplateController controller = new TemplateController(templateService,
            new ModerationService(sourceService, templateService, router));

        ReviewTemplateBody review = templateReview(template.getId(), false);
        assertEquals(204, controller.reviewTemplate(review).getStatusCode().value());

        assertEquals(Status.REJECTED, persisted.get());
        assertEquals(Status.REJECTED, template.getStatus());
        verifyNoInteractions(router);
        verifyNoInteractions(bot);
    }

    @Test
    void missingSourceFailsBeforeMutation() {
        UUID id = UUID.randomUUID();
        when(sourceService.getSource(id)).thenReturn(Optional.empty());

        ReviewSourceBody review = sourceReview(id, false);
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> sources.reviewSource(review));

        assertEquals(ApplicationFailure.class, failure.getClass());
        assertEquals("Source not found: " + id, failure.getMessage());
        inOrder(sourceService).verify(sourceService).getSource(id);
        verifyNoMoreInteractions(sourceService);
        verifyNoInteractions(router, bot);
    }

    @Test
    void missingTemplateFailsBeforeMutation() {
        UUID id = UUID.randomUUID();
        when(templateService.getTemplate(id)).thenReturn(Optional.empty());

        ReviewTemplateBody review = templateReview(id, false);
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> templates.reviewTemplate(review));

        assertEquals(ApplicationFailure.class, failure.getClass());
        assertEquals("Template not found: " + id, failure.getMessage());
        inOrder(templateService).verify(templateService).getTemplate(id);
        verifyNoMoreInteractions(templateService);
        verifyNoInteractions(router, bot);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectedItemsCannotBeReviewedAgain(boolean approved) {
        Source source = source(origin());
        source.setStatus(Status.REJECTED);
        Template template = template(origin());
        template.setStatus(Status.REJECTED);
        when(sourceService.getSource(source.getId())).thenReturn(Optional.of(source));
        when(templateService.getTemplate(template.getId())).thenReturn(Optional.of(template));
        assertThrows(ApplicationFailure.class, () -> sources.reviewSource(sourceReview(source.getId(), approved)));
        assertThrows(ApplicationFailure.class, () -> templates.reviewTemplate(templateReview(template.getId(), approved)));
        verifyNoInteractions(router, bot);
        verifyNoMoreInteractions(sourceService, templateService);
    }

    private AtomicReference<Status> trackSourceCommit(Source source, boolean approved) {
        when(sourceService.getSource(source.getId())).thenReturn(Optional.of(source));
        AtomicReference<Status> persisted = new AtomicReference<>(source.getStatus());
        if (approved) {
            doAnswer(invocation -> {
                source.setStatus(Status.APPROVED);
                persisted.set(Status.APPROVED);
                return null;
            }).when(sourceService).approveSource(source);
        } else {
            doAnswer(invocation -> {
                source.setStatus(Status.REJECTED);
                persisted.set(Status.REJECTED);
                return null;
            }).when(sourceService).rejectSource(source);
        }
        return persisted;
    }

    private AtomicReference<Status> trackTemplateCommit(Template template, boolean approved) {
        when(templateService.getTemplate(template.getId())).thenReturn(Optional.of(template));
        AtomicReference<Status> persisted = new AtomicReference<>(template.getStatus());
        if (approved) {
            doAnswer(invocation -> {
                template.setStatus(Status.APPROVED);
                persisted.set(Status.APPROVED);
                return null;
            }).when(templateService).approveTemplate(template);
        } else {
            doAnswer(invocation -> {
                template.setStatus(Status.REJECTED);
                persisted.set(Status.REJECTED);
                return null;
            }).when(templateService).rejectTemplate(template);
        }
        return persisted;
    }

    private void verifySourceOrder(Source source, boolean approved, TelegramGateway targetRouter) {
        InOrder order = inOrder(sourceService, targetRouter);
        order.verify(sourceService).getSource(source.getId());
        if (approved) {
            order.verify(sourceService).approveSource(source);
        } else {
            order.verify(sourceService).rejectSource(source);
        }
        order.verify(targetRouter).sendText(any(TelegramGateway.Text.class));
    }

    private void verifyTemplateOrder(Template template, boolean approved, TelegramGateway targetRouter) {
        InOrder order = inOrder(templateService, targetRouter);
        order.verify(templateService).getTemplate(template.getId());
        if (approved) {
            order.verify(templateService).approveTemplate(template);
        } else {
            order.verify(templateService).rejectTemplate(template);
        }
        order.verify(targetRouter).sendText(any(TelegramGateway.Text.class));
    }

    private void assertNotificationUpdate(TelegramGateway.Text update, Message message, boolean approved) {
        assertEquals(message.getChatId(), update.destination().chatId());
        assertEquals(message.getMessageId(), update.destination().replyToMessageId());
        if (!approved) {
            assertTrue(update.text().endsWith("Motivo: test rejection reason"));
        }
    }

    private Status decision(boolean approved) {
        return approved ? Status.APPROVED : Status.REJECTED;
    }

    private Source source(Message message) {
        Source source = new Source();
        source.setId(UUID.randomUUID());
        source.setMessage(message);
        source.setStatus(Status.REVIEW);
        return source;
    }

    private Template template(Message message) {
        Template template = new Template();
        template.setId(UUID.randomUUID());
        template.setMessage(message);
        template.setStatus(Status.REVIEW);
        return template;
    }

    private Message origin() {
        Chat chat = new Chat();
        chat.setId(123L);
        Message message = new Message();
        message.setChat(chat);
        message.setMessageId(42);
        return message;
    }

    private ReviewSourceBody sourceReview(UUID id, boolean approved) {
        ReviewSourceBody body = new ReviewSourceBody();
        body.setSourceId(id);
        body.setApproved(approved);
        body.setReason("test rejection reason");
        return body;
    }

    private ReviewTemplateBody templateReview(UUID id, boolean approved) {
        ReviewTemplateBody body = new ReviewTemplateBody();
        body.setTemplateId(id);
        body.setApproved(approved);
        body.setReason("test rejection reason");
        return body;
    }
}