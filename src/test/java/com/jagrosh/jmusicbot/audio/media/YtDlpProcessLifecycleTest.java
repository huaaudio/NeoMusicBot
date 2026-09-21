/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package com.jagrosh.jmusicbot.audio.media;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class YtDlpProcessLifecycleTest
{
    @Test
    public void processBuilderPreservesArgumentsAndCommandContainsHardeningFlags() throws Exception
    {
        Path directory = Files.createTempDirectory("yt-dlp-argv");
        Path recorded = directory.resolve("argv.txt");
        Path shellMarker = directory.resolve("shell-was-used");
        String shellExpression = "$(touch " + shellMarker + ")";
        List<String> values = List.of("--exec", "line one\nline two", "quoted value", shellExpression);

        try (YtDlpMediaResolver resolver = resolver())
        {
            List<String> command = fakeCommand("record", recorded.toString());
            command.addAll(values);
            Object result = execute(resolver, command, Duration.ofSeconds(5));
            assertEquals(0, intResult(result, "exitCode"));

            List<String> encoded = Files.readAllLines(recorded, StandardCharsets.UTF_8);
            List<String> decoded = encoded.stream()
                    .map(value -> new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8))
                    .toList();
            assertEquals(values, decoded);
            assertFalse(Files.exists(shellMarker));

            Method build = YtDlpMediaResolver.class.getDeclaredMethod(
                    "buildCommand", MediaSource.class, String.class, boolean.class, int.class, boolean.class);
            build.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> built = (List<String>) build.invoke(resolver, MediaSource.BILIBILI,
                    "https://www.bilibili.com/video/BV1ab411c7de", false, 1, false);
            assertTrue(built.contains("--ignore-config"));
            assertTrue(built.contains("--no-update"));
            assertEquals("--", built.get(built.size() - 2));
            assertEquals("https://www.bilibili.com/video/BV1ab411c7de", built.get(built.size() - 1));
            assertFalse(built.stream().anyMatch(value ->
                    value.equalsIgnoreCase("sh") || value.equalsIgnoreCase("cmd.exe")));
        }
    }

    @Test
    public void timeoutKillsTheStartedProcess() throws Exception
    {
        Path pidFile = Files.createTempFile("yt-dlp-timeout", ".pid");
        Files.delete(pidFile);
        long pid = -1;
        try (YtDlpMediaResolver resolver = resolver())
        {
            YtDlpException failure = assertThrows(YtDlpException.class,
                    () -> execute(resolver, fakeCommand("sleep", pidFile.toString()),
                            Duration.ofMillis(350)));
            assertEquals(YtDlpException.Kind.TIMEOUT, failure.getKind());
            pid = awaitPid(pidFile);
            assertProcessStops(pid);
        }
        finally
        {
            forceStop(pid);
        }
    }

    @Test
    public void parentExitWithDescendantHoldingPipesStillKillsDescendant() throws Exception
    {
        Path childPidFile = Files.createTempFile("yt-dlp-child", ".pid");
        Files.delete(childPidFile);
        long childPid = -1;
        try (YtDlpMediaResolver resolver = resolver())
        {
            List<String> command = fakeCommand("spawn", childPidFile.toString(),
                    javaExecutable(), System.getProperty("java.class.path"));
            YtDlpException failure = assertThrows(YtDlpException.class,
                    () -> execute(resolver, command, Duration.ofSeconds(3)));
            assertEquals(YtDlpException.Kind.INVALID_OUTPUT, failure.getKind());
            childPid = awaitPid(childPidFile);
            assertProcessStops(childPid);
        }
        finally
        {
            forceStop(childPid);
        }
    }

    @Test
    public void closeCancelsActiveProcess() throws Exception
    {
        Path pidFile = Files.createTempFile("yt-dlp-close", ".pid");
        Files.delete(pidFile);
        YtDlpMediaResolver resolver = resolver();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        long pid = -1;
        try
        {
            Future<Throwable> execution = caller.submit(() ->
            {
                try
                {
                    execute(resolver, fakeCommand("sleep", pidFile.toString()), Duration.ofSeconds(30));
                    return null;
                }
                catch (Throwable failure)
                {
                    return failure;
                }
            });
            pid = awaitPid(pidFile);
            resolver.close();
            Throwable failure = execution.get(5, TimeUnit.SECONDS);
            assertNotNull(failure);
            assertTrue(failure instanceof YtDlpException);
            assertProcessStops(pid);
        }
        finally
        {
            resolver.close();
            caller.shutdownNow();
            forceStop(pid);
        }
    }

    @Test
    public void capsBothStreamsClassifiesExitAndRejectsMalformedJson() throws Exception
    {
        try (YtDlpMediaResolver resolver = resolver())
        {
            Object stdout = execute(resolver, fakeCommand("stdout-overflow"), Duration.ofSeconds(10));
            assertTrue(booleanResult(stdout, "stdoutOverflow"));

            Object stderr = execute(resolver, fakeCommand("stderr-overflow"), Duration.ofSeconds(10));
            assertTrue(booleanResult(stderr, "stderrOverflow"));

            Object nonzero = execute(resolver, fakeCommand("nonzero"), Duration.ofSeconds(5));
            assertEquals(7, intResult(nonzero, "exitCode"));
            YtDlpException classified = YtDlpMediaResolver.classifyFailure(
                    stringResult(nonzero, "stderr"));
            assertEquals(YtDlpException.Kind.AUTHENTICATION_REQUIRED, classified.getKind());

            Object malformed = execute(resolver, fakeCommand("malformed"), Duration.ofSeconds(5));
            YtDlpException invalid = assertThrows(YtDlpException.class,
                    () -> YtDlpMediaResolver.parseJsonDocument(stringResult(malformed, "stdout")));
            assertEquals(YtDlpException.Kind.INVALID_OUTPUT, invalid.getKind());
        }
    }

    private static YtDlpMediaResolver resolver()
    {
        return new YtDlpMediaResolver(new YtDlpConfiguration(
                "unused-test-executable", "deno", null, null, null, null,
                true, false, Duration.ofSeconds(30), Duration.ofSeconds(60)));
    }

    private static List<String> fakeCommand(String... arguments)
    {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(FakeProcessMain.class.getName());
        command.addAll(List.of(arguments));
        return command;
    }

    private static String javaExecutable()
    {
        String suffix = System.getProperty("os.name").toLowerCase().contains("win") ? ".exe" : "";
        return Path.of(System.getProperty("java.home"), "bin", "java" + suffix).toString();
    }

    private static Object execute(YtDlpMediaResolver resolver, List<String> command,
                                  Duration timeout) throws Exception
    {
        Method execute = YtDlpMediaResolver.class.getDeclaredMethod(
                "execute", List.class, MediaSource.class, Duration.class);
        execute.setAccessible(true);
        try
        {
            return execute.invoke(resolver, command, MediaSource.BILIBILI, timeout);
        }
        catch (InvocationTargetException ex)
        {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception)
                throw exception;
            if (cause instanceof Error error)
                throw error;
            throw ex;
        }
    }

    private static Object result(Object result, String accessor) throws Exception
    {
        Method method = result.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(result);
    }

    private static int intResult(Object result, String accessor) throws Exception
    {
        return (int) result(result, accessor);
    }

    private static boolean booleanResult(Object result, String accessor) throws Exception
    {
        return (boolean) result(result, accessor);
    }

    private static String stringResult(Object result, String accessor) throws Exception
    {
        return (String) result(result, accessor);
    }

    private static long awaitPid(Path file) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline)
        {
            if (Files.isRegularFile(file))
            {
                String value = Files.readString(file).trim();
                if (!value.isEmpty())
                    return Long.parseLong(value);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Fake process did not report its PID");
    }

    private static void assertProcessStops(long pid) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline)
        {
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                Thread.sleep(20);
            else
                return;
        }
        throw new AssertionError("Process " + pid + " is still alive");
    }

    private static void forceStop(long pid)
    {
        if (pid > 0)
            ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
    }

    public static final class FakeProcessMain
    {
        private FakeProcessMain()
        {
        }

        public static void main(String[] arguments) throws Exception
        {
            switch (arguments[0])
            {
                case "record" ->
                {
                    List<String> encoded = new ArrayList<>();
                    for (int index = 2; index < arguments.length; index++)
                        encoded.add(Base64.getEncoder().encodeToString(
                                arguments[index].getBytes(StandardCharsets.UTF_8)));
                    Files.write(Path.of(arguments[1]), encoded, StandardCharsets.UTF_8);
                    System.out.print("{}");
                }
                case "sleep" ->
                {
                    Files.writeString(Path.of(arguments[1]), Long.toString(ProcessHandle.current().pid()));
                    Thread.sleep(60_000);
                }
                case "spawn" ->
                {
                    new ProcessBuilder(arguments[2], "-cp", arguments[3],
                            PipeHoldingChild.class.getName(), arguments[1]).inheritIO().start();
                    Thread.sleep(400);
                }
                case "stdout-overflow" -> writeRepeated(System.out, 8 * 1024 * 1024 + 8192);
                case "stderr-overflow" -> writeRepeated(System.err, 256 * 1024 + 8192);
                case "malformed" -> System.out.print("{this-is-not-json");
                case "nonzero" ->
                {
                    System.err.print("ERROR: Private video. Login required");
                    System.exit(7);
                }
                default -> throw new IllegalArgumentException("Unknown fake process mode");
            }
        }

        private static void writeRepeated(java.io.OutputStream output, int size) throws Exception
        {
            byte[] block = new byte[8192];
            java.util.Arrays.fill(block, (byte) 'x');
            for (int remaining = size; remaining > 0; remaining -= block.length)
                output.write(block, 0, Math.min(block.length, remaining));
            output.flush();
        }
    }

    public static final class PipeHoldingChild
    {
        private PipeHoldingChild()
        {
        }

        public static void main(String[] arguments) throws Exception
        {
            Files.writeString(Path.of(arguments[0]), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(60_000);
        }
    }
}
