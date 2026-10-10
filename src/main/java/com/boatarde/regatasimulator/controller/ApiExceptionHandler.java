package com.boatarde.regatasimulator.controller;

import com.boatarde.regatasimulator.flows.ApplicationFailure;
import com.opencsv.exceptions.CsvException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.io.IOException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApplicationFailure.class)
    public ResponseEntity<Object> applicationFailure(ApplicationFailure failure) {
        HttpStatus status = switch (failure.getKind()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case EXECUTION -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return problem(status, new HttpHeaders());
    }

    @ExceptionHandler({IOException.class, CsvException.class})
    public ResponseEntity<Object> invalidImport(Exception ignored) {
        return problem(HttpStatus.BAD_REQUEST, new HttpHeaders());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpectedFailure(Exception ignored) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, new HttpHeaders());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        return problem(status, headers);
    }

    private ResponseEntity<Object> problem(HttpStatusCode status, HttpHeaders originalHeaders) {
        String detail = switch (status.value()) {
            case 400 -> "Requisição inválida.";
            case 404 -> "Item não encontrado.";
            case 409 -> "O item não está mais em revisão.";
            case 503 -> "Operação indisponível no momento.";
            default -> status.is5xxServerError() ? "Não foi possível concluir a operação." : "Operação não permitida.";
        };
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(originalHeaders);
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(ProblemDetail.forStatusAndDetail(status, detail), headers, status);
    }
}