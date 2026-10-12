package com.boatarde.regatasimulator.adapter.telegram;

import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.application.ImageRenderer;
import com.boatarde.regatasimulator.application.MediaStorage;
import com.boatarde.regatasimulator.application.TelegramGateway;
import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.factory.ImageTestFactory;
import com.boatarde.regatasimulator.models.*;
import com.boatarde.regatasimulator.repository.*;
import com.boatarde.regatasimulator.service.MemeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhotoDeliveryRetryIntegrationTest {
    @TempDir Path root;
    private final SourceRepository sources = mock(SourceRepository.class);
    private final TemplateRepository templates = mock(TemplateRepository.class);
    private final MemeHistoryRepository history = mock(MemeHistoryRepository.class);
    private final MetadataUnitOfWork metadata = mock(MetadataUnitOfWork.class);
    private final MediaStorage media = mock(MediaStorage.class);
    private final ImageRenderer renderer = mock(ImageRenderer.class);
    private final RegataSimulatorBot bot = mock(RegataSimulatorBot.class);
    private final List<Long> delays = new ArrayList<>();
    private Source source;
    private Template template;
    private Path job;
    private Path output;
    private MemeService service;
    private final TelegramGateway.Destination destination = new TelegramGateway.Destination(123, 7, 9);

    @BeforeEach void setUp() throws Exception {
        source = new Source(); source.setId(UUID.randomUUID()); source.setStatus(Status.APPROVED); source.setWeight(10);
        template = new Template(); template.setId(UUID.randomUUID()); template.setStatus(Status.APPROVED); template.setWeight(10);
        template.setAreas(List.of(new TemplateArea(1, 1, new AreaCorner(0, 0), new AreaCorner(20, 0),
            new AreaCorner(20, 20), new AreaCorner(0, 20), false)));
        when(sources.find(any())).thenReturn(List.of(source));
        when(templates.find(any())).thenReturn(List.of(template));
        when(history.newestFirst()).thenReturn(List.of());
        when(media.image(MediaStorage.Kind.SOURCE, source.getId())).thenReturn(ImageTestFactory.image(root.resolve("source.png")));
        when(media.image(MediaStorage.Kind.TEMPLATE, template.getId())).thenReturn(ImageTestFactory.image(root.resolve("template.png")));
        when(renderer.render(any())).thenAnswer(call -> {
            job = Files.createTempDirectory(root, "render-");
            output = ImageTestFactory.image(job.resolve("final_output.png"));
            return new ImageRenderer.RenderedImage(output, job);
        });
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(metadata).execute(any());
        var beans = new StaticListableBeanFactory();
        beans.addBean("bot", bot);
        var gateway = new BotTelegramGateway(beans.getBeanProvider(RegataSimulatorBot.class), new TelegramPhotoSender(millis -> {
            assertTrue(Files.exists(output), "Rendered artifact must survive retry delay");
            verifyNoInteractions(metadata);
            delays.add(millis);
        }));
        service = new MemeService(sources, templates, history, media, renderer, gateway,
            Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC), metadata);
    }

    @Test void rendersOnceReusesSamePhotoAndCommitsOnceAfterSuccessfulRetry() throws Exception {
        List<SendPhoto> attempts = new ArrayList<>();
        when(bot.execute(any(SendPhoto.class))).thenAnswer(call -> {
            SendPhoto photo = call.getArgument(0);
            attempts.add(photo);
            assertEquals(output.toFile(), photo.getPhoto().getNewMediaFile());
            assertTrue(Files.exists(output));
            assertEquals("123", photo.getChatId());
            assertEquals(7, photo.getReplyToMessageId());
            assertEquals(9, photo.getMessageThreadId());
            verifyNoInteractions(metadata);
            if (attempts.size() < 3) throw new TelegramApiException(new ConnectException());
            return delivered();
        });
        service.publish(new MemeService.Publish(MemeService.Origin.SCHEDULED, destination));
        assertEquals(3, attempts.size());
        assertSame(attempts.getFirst(), attempts.get(1)); assertSame(attempts.getFirst(), attempts.get(2));
        assertEquals(List.of(1000L, 2000L), delays);
        verify(renderer).render(any());
        verify(metadata).execute(any());
        verify(sources).decreaseWeight(source.getId());
        verify(templates).decreaseWeight(template.getId());
        verify(history).recordDelivered(argThat(meme -> meme.getTemplateId().equals(template.getId())
            && meme.getSourceIds().equals(List.of(source.getId()))));
        assertFalse(Files.exists(job));
    }

    @Test void exhaustedSendsCleanArtifactWithoutMetadataOrRegeneration() throws Exception {
        var failure = new TelegramApiException(new ConnectException());
        when(bot.execute(any(SendPhoto.class))).thenAnswer(call -> {
            assertTrue(Files.exists(output)); throw failure;
        });
        var result = assertThrows(ApplicationFailure.class,
            () -> service.publish(new MemeService.Publish(MemeService.Origin.ADMIN, destination)));
        assertSame(failure, result.getCause());
        verify(bot, times(3)).execute(any(SendPhoto.class));
        verify(renderer).render(any());
        verifyNoInteractions(metadata);
        verify(sources, never()).decreaseWeight(any());
        verify(templates, never()).decreaseWeight(any());
        verify(history, never()).recordDelivered(any());
        assertFalse(Files.exists(job));
    }

    @Test void metadataFailureAfterDeliveryDoesNotRetryPhotoOrRendering() throws Exception {
        when(bot.execute(any(SendPhoto.class))).thenReturn(delivered());
        var failure = new IllegalStateException("Metadata write failed");
        doThrow(failure).when(metadata).execute(any());
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> service.publish(new MemeService.Publish(MemeService.Origin.ADMIN, destination))));
        verify(bot).execute(any(SendPhoto.class));
        verify(renderer).render(any());
        assertTrue(delays.isEmpty()); assertFalse(Files.exists(job));
    }

    private Message delivered() {
        var message = new Message(); message.setMessageId(42);
        var chat = new Chat(); chat.setId(123L); message.setChat(chat);
        return message;
    }
}