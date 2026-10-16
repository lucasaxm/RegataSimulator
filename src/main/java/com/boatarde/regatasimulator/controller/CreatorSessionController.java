package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.application.creatorauth.CreatorAuthenticationFailure;
import com.boatarde.regatasimulator.application.creatorauth.CreatorJson;
import com.boatarde.regatasimulator.application.creatorauth.CreatorSessions;
import com.boatarde.regatasimulator.application.creatorauth.TelegramInitDataVerifier;
import com.boatarde.regatasimulator.configuration.CreatorHttpProblem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public final class CreatorSessionController {
    private final TelegramInitDataVerifier verifier;
    private final CreatorSessions sessions;
    private final ObjectMapper mapper;

    public CreatorSessionController(TelegramInitDataVerifier verifier, CreatorSessions sessions, ObjectMapper mapper) {
        this.verifier = verifier;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @PostMapping(value = "/api/creator/session", consumes = MediaType.APPLICATION_JSON_VALUE)
    public void create(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        byte[] body = request.getInputStream().readNBytes(32769);
        if (body.length > 32768) {
            CreatorHttpProblem.write(mapper, response, HttpStatus.CONTENT_TOO_LARGE);
            return;
        }
        JsonNode node;
        try {
            node = CreatorJson.read(body);
            if (node == null || !node.isObject() || node.size() != 1 || !node.has("initData") || !node.get("initData").isTextual()) {
                CreatorHttpProblem.write(mapper, response, HttpStatus.BAD_REQUEST);
                return;
            }
        } catch (IOException ignored) {
            CreatorHttpProblem.write(mapper, response, HttpStatus.BAD_REQUEST);
            return;
        }
        CreatorSessions.Issued issued;
        try {
            issued = sessions.issue(verifier.verify(node.get("initData").textValue()));
        } catch (CreatorAuthenticationFailure ignored) {
            CreatorHttpProblem.write(mapper, response, HttpStatus.UNAUTHORIZED);
            return;
        } catch (CreatorSessions.CapacityFailure ignored) {
            CreatorHttpProblem.write(mapper, response, HttpStatus.SERVICE_UNAVAILABLE);
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), issued);
    }
}
