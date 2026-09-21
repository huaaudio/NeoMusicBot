package io.github.huaaudio.neomusicbot.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class SerialExecutorTest
{
    @Test
    void aRejectedSubmissionDoesNotPoisonTheQueueOrRunLater()
    {
        AtomicInteger submitted = new AtomicInteger();
        SerialExecutor serial = new SerialExecutor(task -> {
            if(submitted.getAndIncrement() == 0) throw new RejectedExecutionException("busy");
            task.run();
        });
        List<Integer> ran = new ArrayList<>();
        org.junit.jupiter.api.Assertions.assertThrows(RejectedExecutionException.class, () -> serial.execute(() -> ran.add(1)));
        serial.execute(() -> ran.add(2));
        assertEquals(List.of(2), ran);
    }

    @Test
    void everySubmissionToStoppedWorkersFailsPromptly()
    {
        ExecutorService backing = Executors.newSingleThreadExecutor();
        backing.shutdown();
        SerialExecutor serial = new SerialExecutor(backing);
        for(int i = 0; i < 2; i++)
            org.junit.jupiter.api.Assertions.assertThrows(RejectedExecutionException.class, () -> serial.execute(() -> { }));
    }

    @Test
    void preservesOrderAndNeverRunsTwoGuildTasksAtOnce() throws Exception
    {
        ExecutorService backing = Executors.newFixedThreadPool(4);
        try
        {
            SerialExecutor serial = new SerialExecutor(backing);
            List<Integer> observed = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximumActive = new AtomicInteger();
            CountDownLatch finished = new CountDownLatch(100);

            for(int i = 0; i < 100; i++)
            {
                int value = i;
                serial.execute(() -> {
                    int running = active.incrementAndGet();
                    maximumActive.accumulateAndGet(running, Math::max);
                    observed.add(value);
                    active.decrementAndGet();
                    finished.countDown();
                });
            }

            assertTrue(finished.await(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(1, maximumActive.get());
            assertEquals(100, observed.size());
            for(int i = 0; i < observed.size(); i++)
                assertEquals(i, observed.get(i));
        }
        finally
        {
            backing.shutdownNow();
        }
    }
}
