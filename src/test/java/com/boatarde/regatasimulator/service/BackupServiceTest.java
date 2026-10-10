package com.boatarde.regatasimulator.service;

import com.boatarde.regatasimulator.bots.RegataSimulatorBot;
import com.boatarde.regatasimulator.util.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Phase 0: stub ZIP creation, real temporary archives, mocked Telegram delivery. */
@ExtendWith(MockitoExtension.class)
class BackupServiceTest {

    private static final long CHUNK_SIZE = 40L * 1024 * 1024;

    @TempDir
    private Path tempDir;
    @Mock
    private RegataSimulatorBot bot;

    private Path backupDirectory;
    private BackupService service;

    @BeforeEach
    void setUp() throws IOException {
        backupDirectory = Files.createDirectories(tempDir.resolve("input"));
        service = new BackupService(456L);
    }

    @Test
    void successfulMultiChunkDeliveryDeletesEachArchiveAfterSending() throws Exception {
        Path first = archive("first.zip");
        Path second = archive("second.zip");
        when(bot.execute(any(SendDocument.class))).thenAnswer(invocation -> {
            SendDocument document = invocation.getArgument(0);
            Path sentFile = document.getDocument().getNewMediaFile().toPath();
            assertTrue(Files.exists(sentFile), "Archive must still exist while being delivered");
            if (sentFile.equals(second)) {
                assertFalse(Files.exists(first), "Previous delivered chunk is already deleted");
            }
            return new Message();
        });

        try (MockedStatic<FileUtils> files = mockStatic(FileUtils.class)) {
            files.when(() -> FileUtils.zipInChunks(backupDirectory.toString(), CHUNK_SIZE))
                .thenReturn(List.of(first, second));

            service.zipToTelegram(bot, backupDirectory.toString(), "sources");

            files.verify(() -> FileUtils.zipInChunks(backupDirectory.toString(), CHUNK_SIZE));
        }

        ArgumentCaptor<SendDocument> documents = ArgumentCaptor.forClass(SendDocument.class);
        verify(bot, times(2)).execute(documents.capture());
        List<SendDocument> sent = documents.getAllValues();
        assertEquals(List.of("sources backup (1/2)", "sources backup (2/2)"),
            sent.stream().map(SendDocument::getCaption).toList());
        assertEquals(List.of(first, second), sent.stream()
            .map(document -> document.getDocument().getNewMediaFile().toPath()).toList());
        sent.forEach(document -> assertEquals("456", document.getChatId()));
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
        verifyNoMoreInteractions(bot);
    }

    @Test
    void successfulSingleChunkUsesSimpleCaptionAndDeletesArchive() throws Exception {
        Path zip = archive("single.zip");
        try (MockedStatic<FileUtils> files = mockStatic(FileUtils.class)) {
            files.when(() -> FileUtils.zipInChunks(backupDirectory.toString(), CHUNK_SIZE)).thenReturn(List.of(zip));

            service.zipToTelegram(bot, backupDirectory.toString(), "jsondb");

            files.verify(() -> FileUtils.zipInChunks(backupDirectory.toString(), CHUNK_SIZE));
        }

        ArgumentCaptor<SendDocument> document = ArgumentCaptor.forClass(SendDocument.class);
        verify(bot).execute(document.capture());
        assertEquals("jsondb backup", document.getValue().getCaption());
        assertEquals("456", document.getValue().getChatId());
        assertEquals(zip.toFile(), document.getValue().getDocument().getNewMediaFile());
        assertFalse(Files.exists(zip));
    }

    @Test
    void firstDeliveryFailureCurrentlyLeavesAllArchivesAndStopsPhase1CleanupGap() throws Exception {
        Path first = archive("first.zip");
        Path second = archive("second.zip");
        TelegramApiException failure = new TelegramApiException("controlled send failure");
        when(bot.execute(any(SendDocument.class))).thenThrow(failure);

        try (MockedStatic<FileUtils> files = mockStatic(FileUtils.class)) {
            files.when(() -> FileUtils.zipInChunks(backupDirectory.toString(), CHUNK_SIZE))
                .thenReturn(List.of(first, second));

            assertSame(failure, assertThrows(TelegramApiException.class,
                () -> service.zipToTelegram(bot, backupDirectory.toString(), "templates")));
        }

        verify(bot).execute(any(SendDocument.class));
        verifyNoMoreInteractions(bot);
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
    }

    @Test
    void laterDeliveryFailureCurrentlyDeletesOnlySuccessfulArchivesPhase1CleanupGap() throws Exception {
        Path first = archive("first.zip");
        Path second = archive("second.zip");
        Path third = archive("third.zip");
        TelegramApiException failure = new TelegramApiException("controlled second chunk failure");
        when(bot.execute(any(SendDocument.class))).thenReturn(new Message()).thenThrow(failure);

        try (MockedStatic<FileUtils> files = mockStatic(FileUtils.class)) {
            files.when(() -> FileUtils.zipInChunks(backupDirectory.toString(), CHUNK_SIZE))
                .thenReturn(List.of(first, second, third));

            assertSame(failure, assertThrows(TelegramApiException.class,
                () -> service.zipToTelegram(bot, backupDirectory.toString(), "sources")));
        }

        ArgumentCaptor<SendDocument> documents = ArgumentCaptor.forClass(SendDocument.class);
        verify(bot, times(2)).execute(documents.capture());
        assertEquals(List.of(first, second), documents.getAllValues().stream()
            .map(document -> document.getDocument().getNewMediaFile().toPath()).toList());
        verifyNoMoreInteractions(bot);
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
        assertFalse(Files.exists(third));
    }

    @Test
    void zipCreationFailurePropagatesWithoutSending() {
        IOException failure = new IOException("controlled archive failure");
        try (MockedStatic<FileUtils> files = mockStatic(FileUtils.class)) {
            files.when(() -> FileUtils.zipInChunks(backupDirectory.toString(), CHUNK_SIZE)).thenThrow(failure);

            assertSame(failure, assertThrows(IOException.class,
                () -> service.zipToTelegram(bot, backupDirectory.toString(), "jsondb")));
        }
        verifyNoInteractions(bot);
    }

    @Test
    void emptyArchiveListDoesNotSendDocuments() throws Exception {
        try (MockedStatic<FileUtils> files = mockStatic(FileUtils.class)) {
            files.when(() -> FileUtils.zipInChunks(backupDirectory.toString(), CHUNK_SIZE)).thenReturn(List.of());

            service.zipToTelegram(bot, backupDirectory.toString(), "jsondb");
        }
        verifyNoInteractions(bot);
    }

    private Path archive(String name) throws IOException {
        Path archive = tempDir.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("placeholder.txt"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }
        return archive;
    }
}
