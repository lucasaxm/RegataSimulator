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
        String filePath = getTelegramFilePath(fileId);
        if (filePath == null) {
            throw new IOException("Failed to retrieve file path from Telegram for file_id: " + fileId);
        }

        Path sourceFile = destinationDir.resolve("source.jpg");
        downloadFile(filePath, sourceFile);
        return sourceFile;
    }

    private String getTelegramFilePath(String fileId) throws IOException {
        String url = "https://api.telegram.org/bot" + biluTagsBotToken + "/getFile?file_id=" + fileId;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");

        if (conn.getResponseCode() == 200) {
            try (InputStream is = conn.getInputStream()) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode json = mapper.readTree(is);
                boolean ok = json.path("ok").asBoolean(false);
                if (!ok) {
                    return null;
                }
                JsonNode result = json.get("result");
                return result != null && result.has("file_path") ? result.get("file_path").asText() : null;
            }
        }
        return null;
    }

    private void downloadFile(String filePath, Path destination) throws IOException {
        String fileUrl = "https://api.telegram.org/file/bot" + biluTagsBotToken + "/" + filePath;
        HttpURLConnection conn = (HttpURLConnection) new URL(fileUrl).openConnection();
        conn.setRequestMethod("GET");

        if (conn.getResponseCode() == 200) {
            try (InputStream is = conn.getInputStream()) {
                Files.copy(is, destination);
            }
        } else {
            throw new IOException("Failed to download file from Telegram");
        }
    }
}