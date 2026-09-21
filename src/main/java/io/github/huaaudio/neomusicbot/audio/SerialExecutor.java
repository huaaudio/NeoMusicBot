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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** FIFO execution on a backing executor, including a direct executor. */
public final class SerialExecutor implements Executor
{
    private static final Logger LOG = LoggerFactory.getLogger(SerialExecutor.class);

    private final Executor executor;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private boolean scheduled;
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
            if(executor instanceof ExecutorService service && service.isShutdown())
                throw new RejectedExecutionException("Playback workers are stopped");
            tasks.add(command);
            if(!scheduled)
            {
                scheduled = true;
                try { executor.execute(this::drain); }
                catch(RuntimeException | Error rejection)
                {
                    // Enqueue and scheduling are atomic to other submitters:
                    // no caller can return successfully for an orphaned drain.
                    tasks.remove(command);
                    scheduled = false;
                    throw rejection;
                }
            }
        }
    }

    public boolean isCurrentThread()
    {
        return executing.get();
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
                    if(next == null)
                    {
                        // Publish idleness before a new submitter can enqueue.
                        scheduled = false;
                        return;
                    }
                }
                try
                {
                    next.run();
                }
                catch(RuntimeException | Error ex)
                {
                    LOG.error("Serialized task failed ({}): {}", ex.getClass().getSimpleName(),
                            SensitiveLogSanitizer.sanitize(ex.getMessage()));
                }
            }
        }
        finally
        {
            executing.remove();
        }
    }
}
