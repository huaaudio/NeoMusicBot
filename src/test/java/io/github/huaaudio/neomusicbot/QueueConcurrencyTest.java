/* Modified by Huaaudio: queue mutation concurrency regression (2026). */
package io.github.huaaudio.neomusicbot;

import io.github.huaaudio.neomusicbot.queue.FairQueue;
import io.github.huaaudio.neomusicbot.queue.Queueable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueueConcurrencyTest
{
    @Test
    void clearingDuringFairInsertionDoesNotCorruptOrAbortTheInsertion() throws Exception
    {
        FairQueue<Queueable> queue = new FairQueue<>(null);
        CountDownLatch readingExistingTrack = new CountDownLatch(1);
        CountDownLatch finishReading = new CountDownLatch(1);
        CountDownLatch clearAttempted = new CountDownLatch(1);
        CountDownLatch clearFinished = new CountDownLatch(1);
        queue.addAt(0, () -> 1L);
        queue.addAt(1, () -> {
            readingExistingTrack.countDown();
            try
            {
                if(!finishReading.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("Insertion was not released");
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return 2L;
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try
        {
            var insertion = workers.submit(() -> queue.add(() -> 3L));
            assertTrue(readingExistingTrack.await(5, TimeUnit.SECONDS));
            var clear = workers.submit(() -> {
                clearAttempted.countDown();
                queue.clear();
                clearFinished.countDown();
            });
            assertTrue(clearAttempted.await(5, TimeUnit.SECONDS));
            // Let a competing clear run while insertion is inspecting the queue.
            // A serialized clear waits; an unsynchronized insertion observes a
            // shrinking list and used to fail with IndexOutOfBoundsException.
            clearFinished.await(250, TimeUnit.MILLISECONDS);
            finishReading.countDown();
            assertDoesNotThrow(() -> insertion.get(5, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> clear.get(5, TimeUnit.SECONDS));
            assertTrue(queue.isEmpty());
        }
        finally
        {
            finishReading.countDown();
            workers.shutdownNow();
        }
    }
}
