package com.boatarde.regatasimulator.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;

public final class CreatorHttpProblem {
    private CreatorHttpProblem() { }

    public static void write(ObjectMapper mapper, HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        if (status == HttpStatus.TOO_MANY_REQUESTS) response.setHeader("Retry-After", "60");
        String detail = switch (status.value()) {
            case 401 -> "Autenticação necessária.";
            case 403 -> "Acesso negado.";
            case 429 -> "Não foi possível autenticar agora.";
            case 503 -> "Operação indisponível no momento.";
            default -> "Requisição inválida.";
        };
        mapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}
