/* Modified by Huaaudio: NeoMusicBot namespace migration (2026). */
/*
 * Copyright 2026 NeoMusicBot contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.huaaudio.neomusicbot.audio;

import io.github.huaaudio.neomusicbot.audio.media.SensitiveLogSanitizer;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** FIFO execution for one guild on a shared backing executor. */
public final class SerialExecutor implements Executor
{
    private static final Logger LOG = LoggerFactory.getLogger(SerialExecutor.class);

    private final Executor executor;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final ThreadLocal<Boolean> executing = ThreadLocal.withInitial(() -> false);

    public SerialExecutor(Executor executor)
    {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void execute(Runnable command)
    {
        Objects.requireNonNull(command, "command");
        synchronized(tasks)
        {
            tasks.add(command);
        }
        scheduleDrain();
    }

    public boolean isCurrentThread()
    {
        return executing.get();
    }

    private void scheduleDrain()
    {
        if(scheduled.compareAndSet(false, true))
            executor.execute(this::drain);
    }

    private void drain()
    {
        executing.set(true);
        try
        {
            while(true)
            {
                Runnable next;
                synchronized(tasks)
                {
                    next = tasks.poll();
                }
                if(next == null)
                    return;
                try
                {
                    next.run();
                }
                catch(RuntimeException ex)
                {
                    LOG.error("Guild playback task failed ({}): {}", ex.getClass().getSimpleName(),
                            SensitiveLogSanitizer.sanitize(ex.getMessage()));
                }
            }
        }
        finally
        {
            executing.remove();
            scheduled.set(false);
            synchronized(tasks)
            {
                if(!tasks.isEmpty())
                    scheduleDrain();
            }
        }
    }
}
