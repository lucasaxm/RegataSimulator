package com.boatarde.regatasimulator.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@Slf4j
public class TelegramFileDownloader {

    private final String biluTagsBotToken;

    public TelegramFileDownloader(@Value("${telegram.bots.bilu-tags.token}") String biluTagsBotToken) {
        this.biluTagsBotToken = biluTagsBotToken;
    }

    public Path downloadTelegramPhoto(String fileId, Path destinationDir) throws IOException {
        if (fileId == null || fileId.isBlank() || fileId.length() > 1024) {
            throw new IOException("Invalid Telegram file identifier");
        }
        String filePath = getTelegramFilePath(fileId);
        if (filePath == null) {
            throw new IOException("Failed to retrieve Telegram file path");
        }
        if (!filePath.matches("[a-zA-Z0-9_./-]{1,1024}") || filePath.startsWith("/")
            || java.util.Arrays.stream(filePath.split("/",-1)).anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw new IOException("Invalid Telegram file path");
        }

        Path sourceFile = destinationDir.resolve("source.jpg");
        downloadFile(filePath, sourceFile);
        return sourceFile;
    }

    private String getTelegramFilePath(String fileId) throws IOException {
        String url = "https://api.telegram.org/bot" + biluTagsBotToken + "/getFile?file_id="
            + java.net.URLEncoder.encode(fileId, java.nio.charset.StandardCharsets.UTF_8);
        HttpURLConnection conn = connection(java.net.URI.create(url).toURL());
        try {
            if (conn.getResponseCode() == 200) {
                try (InputStream is = conn.getInputStream()) {
                    ObjectMapper mapper = new ObjectMapper();
                    byte[] metadata = is.readNBytes(64 * 1024 + 1);
                    if (metadata.length > 64 * 1024) {
                        throw new IOException("Telegram metadata exceeds limit");
                    }
                    JsonNode json = mapper.readTree(metadata);
                    if (json == null || !json.path("ok").asBoolean(false)) {
                        return null;
                    }
                    JsonNode result = json.get("result");
                    return result != null && result.has("file_path") ? result.get("file_path").asText() : null;
                }
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }

    private void downloadFile(String filePath, Path destination) throws IOException {
        String fileUrl = "https://api.telegram.org/file/bot" + biluTagsBotToken + "/" + filePath;
        HttpURLConnection conn = connection(java.net.URI.create(fileUrl).toURL());
        boolean created = false;
        try {
            if (conn.getResponseCode() != 200) {
                throw new IOException("Failed to download file from Telegram");
            }
            if (conn.getContentLengthLong() > MediaValidation.MAX_BYTES) {
                throw new IOException("Telegram image exceeds byte limit");
            }
            try (InputStream is = conn.getInputStream();
                 var output = Files.newOutputStream(destination, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                created = true;
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = is.read(buffer)) != -1) {
                    total += count;
                    if (total > MediaValidation.MAX_BYTES) {
                        throw new IOException("Telegram image exceeds byte limit");
                    }
                    output.write(buffer, 0, count);
                }
            }
        } catch (IOException e) {
            if (created) {
                try {
                    Files.deleteIfExists(destination);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    protected HttpURLConnection openConnection(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
    }

    private HttpURLConnection connection(URL url) throws IOException {
        HttpURLConnection conn = openConnection(url);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        return conn;
    }
}