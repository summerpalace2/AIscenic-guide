package com.ai.guide.domain.voice.model;

import java.util.Objects;

/** Transport-neutral result of a text-to-speech operation. */
public record VoiceSynthesisResult(Status status, byte[] audio, String message) {

    public enum Status {
        SUCCESS,
        BAD_REQUEST,
        PAYLOAD_TOO_LARGE,
        UPSTREAM_FAILURE,
        UNAVAILABLE
    }

    public VoiceSynthesisResult {
        Objects.requireNonNull(status, "status");
        audio = audio == null ? new byte[0] : audio.clone();
        message = message == null ? "" : message;
        if (status == Status.SUCCESS && audio.length == 0) {
            throw new IllegalArgumentException("Successful synthesis must contain audio");
        }
    }

    @Override
    public byte[] audio() {
        return audio.clone();
    }

    public static VoiceSynthesisResult success(byte[] audio) {
        return new VoiceSynthesisResult(Status.SUCCESS, audio, "");
    }

    public static VoiceSynthesisResult failure(Status status, String message) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("Failure status cannot be SUCCESS");
        }
        return new VoiceSynthesisResult(status, new byte[0], message);
    }

    public boolean successful() {
        return status == Status.SUCCESS;
    }
}
