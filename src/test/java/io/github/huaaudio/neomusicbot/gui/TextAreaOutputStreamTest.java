package io.github.huaaudio.neomusicbot.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextAreaOutputStreamTest
{
    @Test
    void utf8SurvivesSingleByteWritesAndIntermediateFlushes() throws Exception
    {
        Fixture fixture = fixture(10);
        String message = "中文日志 🎵 café\n";
        for(byte value : message.getBytes(StandardCharsets.UTF_8))
        {
            fixture.stream.write(value);
            fixture.stream.flush();
        }
        assertEquals(message, fixture.text());
    }

    @Test
    void utf8SurvivesEveryArrayBoundaryAndNonzeroOffset() throws Exception
    {
        String message = "音乐🎶结束";
        byte[] content = message.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[content.length + 4];
        System.arraycopy(content, 0, padded, 2, content.length);
        for(int boundary = 0; boundary <= content.length; boundary++)
        {
            Fixture fixture = fixture(10);
            fixture.stream.write(padded, 2, boundary);
            fixture.stream.flush();
            fixture.stream.write(padded, 2 + boundary, content.length - boundary);
            assertEquals(message, fixture.text(), "byte boundary " + boundary);
        }
    }

    @Test
    void largeUtf8WriteCrossesInternalBuffersWithoutChangingText() throws Exception
    {
        Fixture fixture = fixture(2);
        String message = "音乐🎶".repeat(3000) + "\nlast\n";
        fixture.stream.write(message.getBytes(StandardCharsets.UTF_8));
        assertEquals(message, fixture.text());
    }

    @Test
    void multilineWriteKeepsOnlyTheNewestLinesIncludingAnUnfinishedLine() throws Exception
    {
        Fixture fixture = fixture(2);
        fixture.stream.write("one\ntwo\nthree\nfour".getBytes(StandardCharsets.UTF_8));
        assertEquals("three\nfour", fixture.text());
    }

    @Test
    void lineLimitHandlesPartialLinesAndSplitWindowsNewlines() throws Exception
    {
        Fixture fixture = fixture(2);
        for(String chunk : new String[]{"one\ntw", "o\nthree\r", "\nfour\n"})
        {
            fixture.stream.write(chunk.getBytes(StandardCharsets.UTF_8));
            fixture.text(); // Exercise separate event-dispatch batches as well.
        }
        assertEquals("three\r\nfour\n", fixture.text());
    }

    @Test
    void clearDiscardsQueuedTextAndAnyUnfinishedUtf8Character() throws Exception
    {
        Fixture fixture = fixture(2);
        SwingUtilities.invokeAndWait(() -> {
            byte[] old = "old\n".getBytes(StandardCharsets.UTF_8);
            fixture.stream.write(old, 0, old.length);
            fixture.stream.write(0xe4);
            fixture.stream.clear();
            byte[] fresh = "fresh\n".getBytes(StandardCharsets.UTF_8);
            fixture.stream.write(fresh, 0, fresh.length);
        });
        assertEquals("fresh\n", fixture.text());
    }

    @Test
    void closeFinishesAnIncompleteCharacterOnce() throws Exception
    {
        Fixture fixture = fixture(2);
        fixture.stream.write(new byte[]{(byte)0xe4, (byte)0xb8});
        fixture.stream.close();
        fixture.stream.close();
        fixture.stream.write('x');
        assertEquals("\ufffd", fixture.text());
    }

    private static Fixture fixture(int limit) throws Exception
    {
        AtomicReference<Fixture> created = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            JTextArea area = new JTextArea();
            created.set(new Fixture(area, new TextAreaOutputStream(area, limit)));
        });
        return created.get();
    }

    private record Fixture(JTextArea area, TextAreaOutputStream stream)
    {
        String text() throws Exception
        {
            AtomicReference<String> value = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> value.set(area.getText()));
            return value.get();
        }
    }
}
