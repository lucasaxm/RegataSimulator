package com.boatarde.regatasimulator.util;

import com.boatarde.regatasimulator.factory.ImageTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramFileDownloaderTest {
    @TempDir Path temporary;

    private TelegramFileDownloader downloader(HttpURLConnection metadata, HttpURLConnection image) {
        return new TelegramFileDownloader("synthetic-token") {
            @Override protected HttpURLConnection openConnection(URL url) {
                return url.getPath().contains("getFile") ? metadata : image;
            }
        };
    }

    private HttpURLConnection metadata() throws IOException {
        HttpURLConnection conn = mock(HttpURLConnection.class);
        when(conn.getResponseCode()).thenReturn(200);
        when(conn.getInputStream()).thenReturn(new ByteArrayInputStream(
            "{\"ok\":true,\"result\":{\"file_path\":\"photos/file.jpg\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return conn;
    }

    @Test
    void successfulTransferIsBoundedDisconnectedAndContainsActualImage() throws Exception {
        HttpURLConnection metadata = metadata();
        HttpURLConnection image = mock(HttpURLConnection.class);
        when(image.getResponseCode()).thenReturn(200);
        Path fixture = ImageTestFactory.image(temporary.resolve("fixture.jpg"));
        when(image.getInputStream()).thenReturn(new ByteArrayInputStream(Files.readAllBytes(fixture)));
        Path downloaded = downloader(metadata, image).downloadTelegramPhoto("file", temporary);
        assertThat(MediaValidation.image(downloaded)).isEqualTo(new MediaValidation.Dimensions(400, 300));
        for (HttpURLConnection conn : new HttpURLConnection[] {metadata, image}) {
            verify(conn).setConnectTimeout(10_000);
            verify(conn).setReadTimeout(30_000);
            verify(conn).setInstanceFollowRedirects(false);
            verify(conn).disconnect();
        }
    }

    @Test
    void unknownLengthOversizedCopyIsRemovedAndDisconnected() throws Exception {
        HttpURLConnection metadata = metadata();
        HttpURLConnection image = mock(HttpURLConnection.class);
        when(image.getResponseCode()).thenReturn(200);
        when(image.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[(int) MediaValidation.MAX_BYTES + 1]));
        assertThatThrownBy(() -> downloader(metadata, image).downloadTelegramPhoto("file", temporary))
            .isInstanceOf(IOException.class).hasMessageContaining("byte limit");
        assertThat(temporary.resolve("source.jpg")).doesNotExist();
        verify(image).disconnect();
    }

    @Test
    void readFailureDoesNotLeavePartialCopyOrOverwriteExistingFile() throws Exception {
        HttpURLConnection metadata = metadata();
        HttpURLConnection image = mock(HttpURLConnection.class);
        when(image.getResponseCode()).thenReturn(200);
        java.io.InputStream stream = mock(java.io.InputStream.class);
        when(stream.read(any(byte[].class))).thenThrow(new IOException("synthetic interrupted copy"));
        when(image.getInputStream()).thenReturn(stream);
        assertThatThrownBy(() -> downloader(metadata, image).downloadTelegramPhoto("file", temporary)).isInstanceOf(IOException.class);
        assertThat(temporary.resolve("source.jpg")).doesNotExist();
        verify(stream).close();
        verify(image).disconnect();
    }
}