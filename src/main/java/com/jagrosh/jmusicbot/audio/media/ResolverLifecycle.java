package com.jagrosh.jmusicbot.audio.media;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

import static com.jagrosh.jmusicbot.audio.media.YtDlpException.Kind;

/** Serializes child-process publication with terminal resolver cleanup. */
final class ResolverLifecycle
{
    private boolean closed;

    synchronized boolean isClosed()
    {
        return closed;
    }

    synchronized <T> T start(CheckedSupplier<T> starter, Consumer<T> register) throws IOException
    {
        Objects.requireNonNull(starter, "starter");
        Objects.requireNonNull(register, "register");
        if (closed)
            throw new YtDlpException(Kind.CONFIGURATION, "The media resolver has been shut down");
        T resource = starter.get();
        register.accept(resource);
        return resource;
    }

    synchronized boolean close(Runnable terminateAndCleanup)
    {
        Objects.requireNonNull(terminateAndCleanup, "terminateAndCleanup");
        if (closed)
            return false;
        closed = true;
        terminateAndCleanup.run();
        return true;
    }

    @FunctionalInterface
    interface CheckedSupplier<T>
    {
        T get() throws IOException;
    }
}
