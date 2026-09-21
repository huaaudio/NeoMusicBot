/* Modified by Huaaudio: NeoMusicBot namespace migration (2026). */
/*
 * Copyright 2017 John Grosh <john.a.grosh@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.huaaudio.neomusicbot.gui;

import java.awt.EventQueue;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.JTextArea;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;

/**
 * Streams UTF-8 to the Swing event thread while retaining the newest log lines.
 * @author Lawrence Dol
 */
public class TextAreaOutputStream extends OutputStream
{
    private final byte[] oneByte = new byte[1];
    private final ByteBuffer input = ByteBuffer.allocate(8192);
    private final CharBuffer output = CharBuffer.allocate(8192);
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private Appender appender;

    public TextAreaOutputStream(JTextArea textArea)
    {
        this(textArea, 1000);
    }

    public TextAreaOutputStream(JTextArea textArea, int maxLines)
    {
        if(maxLines < 1)
            throw new IllegalArgumentException("TextAreaOutputStream maximum lines must be positive (value=" + maxLines + ")");
        appender = new Appender(Objects.requireNonNull(textArea), maxLines);
    }

    /** Clear pending text and an unfinished character as one stream operation. */
    public synchronized void clear()
    {
        if(appender != null)
        {
            decoder.reset();
            input.clear();
            appender.clear();
        }
    }

    @Override
    public synchronized void close()
    {
        if(appender == null) return;
        decode(true);
        CoderResult result;
        do
        {
            output.clear();
            result = decoder.flush(output);
            appendDecoded();
        }
        while(result.isOverflow());
        appender = null;
    }

    @Override
    public synchronized void flush()
    {
        // Decoded text is queued immediately. A flush must not end a partial
        // UTF-8 character: PrintStream may flush between bytes of that character.
    }

    @Override
    public synchronized void write(int value)
    {
        oneByte[0] = (byte)value;
        write(oneByte, 0, 1);
    }

    @Override
    public synchronized void write(byte[] bytes)
    {
        write(bytes, 0, bytes.length);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length)
    {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if(appender == null) return;
        while(length > 0)
        {
            int count = Math.min(length, input.remaining());
            input.put(bytes, offset, count);
            offset += count;
            length -= count;
            decode(false);
        }
    }

    private void decode(boolean endOfInput)
    {
        input.flip();
        CoderResult result;
        do
        {
            output.clear();
            result = decoder.decode(input, output, endOfInput);
            appendDecoded();
        }
        while(result.isOverflow());
        input.compact(); // Keep at most one unfinished UTF-8 sequence.
    }

    private void appendDecoded()
    {
        output.flip();
        if(output.hasRemaining()) appender.append(output.toString());
    }

    private static final class Appender implements Runnable
    {
        private final JTextArea textArea;
        private final int maxLines;
        private final List<String> values = new ArrayList<>();
        private boolean clear;
        private boolean scheduled;

        Appender(JTextArea textArea, int maxLines)
        {
            this.textArea = textArea;
            this.maxLines = maxLines;
        }

        synchronized void append(String value)
        {
            values.add(value);
            schedule();
        }

        synchronized void clear()
        {
            clear = true;
            values.clear();
            schedule();
        }

        private void schedule()
        {
            if(!scheduled)
            {
                scheduled = true;
                EventQueue.invokeLater(this);
            }
        }

        @Override
        public void run()
        {
            List<String> batch;
            boolean clearText;
            synchronized(this)
            {
                batch = new ArrayList<>(values);
                values.clear();
                clearText = clear;
                clear = false;
                scheduled = false;
            }
            // Only the event thread touches the document. Do not hold the
            // producer's lock while Swing notifies document listeners.
            if(clearText) textArea.setText("");
            textArea.append(String.join("", batch));
            trimLines();
        }

        private void trimLines()
        {
            Document document = textArea.getDocument();
            int length = document.getLength();
            if(length == 0) return;
            try
            {
                int lines = textArea.getLineCount();
                // A final newline creates an empty Swing line; it is not a
                // further log entry. A nonempty unfinished line does count.
                if("\n".equals(document.getText(length - 1, 1))) lines--;
                int excess = lines - maxLines;
                if(excess > 0)
                    textArea.replaceRange("", 0, textArea.getLineStartOffset(excess));
            }
            catch(BadLocationException invalidDocument)
            {
                throw new IllegalStateException("Unable to trim the console document", invalidDocument);
            }
        }
    }
}
