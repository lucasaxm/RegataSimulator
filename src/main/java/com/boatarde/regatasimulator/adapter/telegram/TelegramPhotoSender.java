package com.boatarde.regatasimulator.adapter.telegram;

import org.apache.http.conn.ConnectTimeoutException;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import javax.net.ssl.SSLException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Retries only rejected/pre-connect photo sends, never rendering or uncertain delivery. */
final class TelegramPhotoSender {
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_WAIT_MILLIS = 10_000;
    private final Pause pause;

    @FunctionalInterface
    interface Send { Message execute(SendPhoto photo) throws TelegramApiException; }
    @FunctionalInterface
    interface Pause { void await(long millis) throws InterruptedException; }

    TelegramPhotoSender() { this(Thread::sleep); }
    TelegramPhotoSender(Pause pause) { this.pause = pause; }

    Message send(SendPhoto photo, Send transport) throws TelegramApiException {
        long waited = 0;
        for (int attempt = 1; ; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new TelegramApiException("Telegram photo delivery interrupted");
            }
            try {
                return transport.execute(photo);
            } catch (TelegramApiException failure) {
                List<Throwable> causes = causes(failure);
                if (causes.stream().anyMatch(cause -> cause instanceof InterruptedException || cause instanceof ClosedByInterruptException)) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
                long delay = retryDelay(failure, causes, attempt);
                if (attempt >= MAX_ATTEMPTS || Thread.currentThread().isInterrupted()
                    || delay <= 0 || delay > MAX_WAIT_MILLIS - waited) {
                    throw failure;
                }
                try {
                    pause.await(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new TelegramApiException("Telegram photo retry interrupted", interrupted);
                }
                waited += delay;
            }
        }
    }

    private long retryDelay(TelegramApiException failure, List<Throwable> causes, int attempt) {
        if (failure instanceof TelegramApiRequestException response) {
            if (!Integer.valueOf(429).equals(response.getErrorCode()) || response.getParameters() == null
                || response.getParameters().getRetryAfter() == null) return 0;
            return response.getParameters().getRetryAfter().longValue() * 1000;
        }
        if (causes.isEmpty() || causes.stream().anyMatch(this::ambiguousOrPermanent)) return 0;
        return causes.stream().anyMatch(this::connectionNotEstablished) ? attempt * 1000L : 0;
    }

    private boolean ambiguousOrPermanent(Throwable cause) {
        return cause instanceof TelegramApiRequestException || cause instanceof SSLException
            || cause instanceof InterruptedIOException && !(cause instanceof ConnectTimeoutException);
    }

    private boolean connectionNotEstablished(Throwable cause) {
        if (cause instanceof ConnectException || cause instanceof NoRouteToHostException
            || cause instanceof UnknownHostException || cause instanceof ConnectTimeoutException) return true;
        // A generic SocketException alone is ambiguous. Only a known TCP-connect frame
        // proves this socket failed before upload; message-string matching is not enough.
        if (cause.getClass() == SocketException.class) {
            for (StackTraceElement frame : cause.getStackTrace()) {
                if (frame.getClassName().equals("sun.nio.ch.Net")
                    && (frame.getMethodName().equals("connect") || frame.getMethodName().equals("connect0"))) return true;
            }
        }
        return false;
    }

    private List<Throwable> causes(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> result = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!seen.add(cause) || result.size() >= 16) return List.of();
            result.add(cause);
        }
        return result;
    }
}