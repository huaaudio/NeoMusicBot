package com.jagrosh.jmusicbot.audio.media;

/** A deliberately terse exception which never contains a signed media URL. */
public class YtDlpException extends RuntimeException
{
    public enum Kind
    {
        NOT_FOUND,
        UNAVAILABLE,
        AUTHENTICATION_REQUIRED,
        REGION_BLOCKED,
        RETRYABLE,
        INTERNAL,
        INVALID_OUTPUT,
        CONFIGURATION,
        TIMEOUT,
        OUTPUT_LIMIT,
        INTERRUPTED
    }

    private final Kind kind;

    public YtDlpException(Kind kind, String message)
    {
        super(message);
        this.kind = kind;
    }

    public YtDlpException(Kind kind, String message, Throwable cause)
    {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind()
    {
        return kind;
    }

    public boolean canRetryWithPotProvider()
    {
        return kind == Kind.RETRYABLE;
    }
}
