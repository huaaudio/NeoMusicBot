/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package io.github.huaaudio.neomusicbot.audio.media;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResolverLifecycleTest
{
    @Test
    public void closeCannotPassAStartedButUnpublishedChild() throws Exception
    {
        ResolverLifecycle lifecycle = new ResolverLifecycle();
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        CountDownLatch closeAttempted = new CountDownLatch(1);
        AtomicBoolean registered = new AtomicBoolean();
        AtomicBoolean cleanupSawRegisteredChild = new AtomicBoolean();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try
        {
            Future<String> start = workers.submit(() -> lifecycle.start(() ->
            {
                starterEntered.countDown();
                try
                {
                    if (!releaseStarter.await(5, TimeUnit.SECONDS))
                        throw new IOException("test start gate timed out");
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException("test start gate interrupted", ex);
                }
                return "child";
            }, ignored -> registered.set(true)));

            assertTrue(starterEntered.await(5, TimeUnit.SECONDS));
            Future<Boolean> close = workers.submit(() ->
            {
                closeAttempted.countDown();
                return lifecycle.close(() ->
                        cleanupSawRegisteredChild.set(registered.get()));
            });
            assertTrue(closeAttempted.await(5, TimeUnit.SECONDS));
            assertFalse(close.isDone());

            releaseStarter.countDown();
            assertEquals("child", start.get(5, TimeUnit.SECONDS));
            assertTrue(close.get(5, TimeUnit.SECONDS));
            assertTrue(cleanupSawRegisteredChild.get());
            assertTrue(lifecycle.isClosed());
            assertThrows(YtDlpException.class,
                    () -> lifecycle.start(() -> "late-child", ignored -> {}));
        }
        finally
        {
            releaseStarter.countDown();
            workers.shutdownNow();
        }
    }
}
