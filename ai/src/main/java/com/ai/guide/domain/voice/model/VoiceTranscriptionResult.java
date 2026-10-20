package com.ai.guide.domain.voice.model;

import java.util.Objects;

/** Transport-neutral outcome of an audio transcription operation. */
public record VoiceTranscriptionResult(Status status, String transcript, String message) {

    public enum Status {
        SUCCESS,
        INVALID_REQUEST,
        PAYLOAD_TOO_LARGE,
        UNAUTHORIZED,
        FORBIDDEN,
        RATE_LIMITED,
        PROVIDER_FAILURE
    }

    public VoiceTranscriptionResult {
        Objects.requireNonNull(status, "status");
        transcript = transcript == null ? "" : transcript;
        message = message == null ? "" : message;
        if (status == Status.SUCCESS && transcript.isBlank()) {
            throw new IllegalArgumentException("Successful transcription must contain text");
        }
    }

    public static VoiceTranscriptionResult success(String message, String transcript) {
        return new VoiceTranscriptionResult(Status.SUCCESS, transcript, message);
    }

    public static VoiceTranscriptionResult failure(Status status, String message) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("Failure status cannot be SUCCESS");
        }
        return new VoiceTranscriptionResult(status, "", message);
    }

    public boolean successful() {
        return status == Status.SUCCESS;
    }
}
