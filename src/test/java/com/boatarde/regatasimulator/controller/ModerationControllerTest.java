package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.flows.WorkflowAction;
import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.boatarde.regatasimulator.flows.WorkflowManager;
import com.boatarde.regatasimulator.flows.simulator.SendTemplateRejectedMessageStep;
import com.boatarde.regatasimulator.models.ReviewSourceBody;
import com.boatarde.regatasimulator.models.ReviewTemplateBody;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.service.RouterService;
import com.boatarde.regatasimulator.service.SourceImporterService;
import com.boatarde.regatasimulator.service.SourceService;
import com.boatarde.regatasimulator.service.TemplateService;
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
import org.telegram.telegrambots.meta.api.objects.Update;

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

/** Phase 0: direct controller characterization, not HTTP security/desired Phase 1 behavior. */
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
    private RouterService router;

    private SourceController sources;
    private TemplateController templates;

    @BeforeEach
    void setUp() {
        sources = new SourceController(sourceService, importer, bot, router);
        templates = new TemplateController(templateService, bot, router);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sourceStatusIsCommittedBeforeNotification(boolean approved) {
        Source source = source(origin());
        AtomicReference<Status> persisted = trackSourceCommit(source, approved);
        WorkflowAction action = sourceAction(approved);
        doAnswer(invocation -> {
            assertEquals(decision(approved), persisted.get());
            assertNotificationUpdate(invocation.getArgument(0), source.getMessage(), approved);
            return null;
        }).when(router).startFlow(any(Update.class), eq(bot), eq(action));

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
        WorkflowAction action = templateAction(approved);
        doAnswer(invocation -> {
            assertEquals(decision(approved), persisted.get());
            assertNotificationUpdate(invocation.getArgument(0), template.getMessage(), approved);
            return null;
        }).when(router).startFlow(any(Update.class), eq(bot), eq(action));

        assertEquals(204, templates.reviewTemplate(templateReview(template.getId(), approved)).getStatusCode().value());

        verifyTemplateOrder(template, approved, router);
        assertEquals(decision(approved), persisted.get());
        verifyNoInteractions(bot);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sourceNotificationFailureCurrentlyLeavesCommittedStatusPhase1RecoveryGap(boolean approved) {
        Source source = source(origin());
        AtomicReference<Status> persisted = trackSourceCommit(source, approved);
        IllegalStateException failure = new IllegalStateException("notification failed");
        doThrow(failure).when(router).startFlow(any(Update.class), eq(bot), eq(sourceAction(approved)));

        ReviewSourceBody review = sourceReview(source.getId(), approved);
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> sources.reviewSource(review)));

        assertEquals(decision(approved), persisted.get());
        assertEquals(decision(approved), source.getStatus());
        verifySourceOrder(source, approved, router);
        verifyNoMoreInteractions(sourceService, router);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void templateNotificationFailureCurrentlyLeavesCommittedStatusPhase1RecoveryGap(boolean approved) {
        Template template = template(origin());
        AtomicReference<Status> persisted = trackTemplateCommit(template, approved);
        IllegalStateException failure = new IllegalStateException("notification failed");
        doThrow(failure).when(router).startFlow(any(Update.class), eq(bot), eq(templateAction(approved)));

        ReviewTemplateBody review = templateReview(template.getId(), approved);
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> templates.reviewTemplate(review)));

        assertEquals(decision(approved), persisted.get());
        assertEquals(decision(approved), template.getStatus());
        verifyTemplateOrder(template, approved, router);
        verifyNoMoreInteractions(templateService, router);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void alreadyApprovedSourceCurrentlyRejectsEitherDecisionWithRuntimeException(boolean approved) {
        Source source = source(origin());
        source.setStatus(Status.APPROVED);
        when(sourceService.getSource(source.getId())).thenReturn(Optional.of(source));

        ReviewSourceBody review = sourceReview(source.getId(), approved);
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> sources.reviewSource(review));

        assertEquals(RuntimeException.class, failure.getClass());
        assertEquals("Source already approved: " + source.getId(), failure.getMessage());
        InOrder order = inOrder(sourceService);
        order.verify(sourceService).getSource(source.getId());
        verifyNoMoreInteractions(sourceService);
        verifyNoInteractions(router, bot);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void alreadyApprovedTemplateCurrentlyRejectsEitherDecisionWithRuntimeException(boolean approved) {
        Template template = template(origin());
        template.setStatus(Status.APPROVED);
        when(templateService.getTemplate(template.getId())).thenReturn(Optional.of(template));

        ReviewTemplateBody review = templateReview(template.getId(), approved);
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> templates.reviewTemplate(review));

        assertEquals(RuntimeException.class, failure.getClass());
        assertEquals("Template already approved: " + template.getId(), failure.getMessage());
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
    void nullOriginTemplateRejectionCurrentlyThrowsInRealStepAfterCommitPhase1NullOriginBug() {
        Template template = template(null);
        AtomicReference<Status> persisted = trackTemplateCommit(template, false);
        // No mocked exception: the controller leaves channelPost null and the real step dereferences it.
        RouterService realRouter = spy(new RouterService(
            new WorkflowManager(List.of(new SendTemplateRejectedMessageStep())), List.of()));
        TemplateController controller = new TemplateController(templateService, bot, realRouter);

        ReviewTemplateBody review = templateReview(template.getId(), false);
        Throwable failure = assertThrows(ApplicationFailure.class,
            () -> controller.reviewTemplate(review)).getCause();
        assertTrue(failure instanceof NullPointerException);

        assertEquals(Status.REJECTED, persisted.get());
        assertEquals(Status.REJECTED, template.getStatus());
        assertTrue(Arrays.stream(failure.getStackTrace()).anyMatch(frame ->
            frame.getClassName().equals(SendTemplateRejectedMessageStep.class.getName())
                && frame.getMethodName().equals("run")));
        verifyTemplateOrder(template, false, realRouter);
        verifyNoInteractions(bot);
    }

    @Test
    void missingSourceCurrentlyThrowsRuntimeExceptionBeforeMutation() {
        UUID id = UUID.randomUUID();
        when(sourceService.getSource(id)).thenReturn(Optional.empty());

        ReviewSourceBody review = sourceReview(id, false);
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> sources.reviewSource(review));

        assertEquals(RuntimeException.class, failure.getClass());
        assertEquals("Source not found: " + id, failure.getMessage());
        inOrder(sourceService).verify(sourceService).getSource(id);
        verifyNoMoreInteractions(sourceService);
        verifyNoInteractions(router, bot);
    }

    @Test
    void missingTemplateCurrentlyThrowsRuntimeExceptionBeforeMutation() {
        UUID id = UUID.randomUUID();
        when(templateService.getTemplate(id)).thenReturn(Optional.empty());

        ReviewTemplateBody review = templateReview(id, false);
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> templates.reviewTemplate(review));

        assertEquals(RuntimeException.class, failure.getClass());
        assertEquals("Template not found: " + id, failure.getMessage());
        inOrder(templateService).verify(templateService).getTemplate(id);
        verifyNoMoreInteractions(templateService);
        verifyNoInteractions(router, bot);
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

    private void verifySourceOrder(Source source, boolean approved, RouterService targetRouter) {
        InOrder order = inOrder(sourceService, targetRouter);
        order.verify(sourceService).getSource(source.getId());
        if (approved) {
            order.verify(sourceService).approveSource(source);
        } else {
            order.verify(sourceService).rejectSource(source);
        }
        order.verify(targetRouter).startFlow(any(Update.class), eq(bot), eq(sourceAction(approved)));
    }

    private void verifyTemplateOrder(Template template, boolean approved, RouterService targetRouter) {
        InOrder order = inOrder(templateService, targetRouter);
        order.verify(templateService).getTemplate(template.getId());
        if (approved) {
            order.verify(templateService).approveTemplate(template);
        } else {
            order.verify(templateService).rejectTemplate(template);
        }
        order.verify(targetRouter).startFlow(any(Update.class), eq(bot), eq(templateAction(approved)));
    }

    private void assertNotificationUpdate(Update update, Message message, boolean approved) {
        assertSame(message, update.getMessage());
        if (!approved) {
            assertEquals("test rejection reason", update.getChannelPost().getText());
        }
    }

    private Status decision(boolean approved) {
        return approved ? Status.APPROVED : Status.REJECTED;
    }

    private WorkflowAction sourceAction(boolean approved) {
        return approved ? WorkflowAction.SEND_SOURCE_APPROVED_MESSAGE_STEP
            : WorkflowAction.SEND_SOURCE_REJECTED_MESSAGE;
    }

    private WorkflowAction templateAction(boolean approved) {
        return approved ? WorkflowAction.SEND_TEMPLATE_APPROVED_MESSAGE_STEP
            : WorkflowAction.SEND_TEMPLATE_REJECTED_MESSAGE;
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