/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.review;

import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.app.review.LocalRunService.OutputChunk;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalLong;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A live run's output in arrival order: the first 64 KiB and the last 1 MiB of characters, addressed by offsets in the
 * full stream so a client polling while the middle is dropped learns how much it missed.
 */
final class RunOutput
        implements ProcessOutputSink
{
    static final int HEAD_CHARACTERS = 64 * 1024;
    static final int TAIL_CHARACTERS = 1024 * 1024;

    private final StringBuilder head = new StringBuilder();
    // Holds at least the last TAIL_CHARACTERS; trimmed only past twice that, so appends stay amortized constant.
    private final StringBuilder tail = new StringBuilder();
    private final Map<ProcessStream, Utf8Stream> decoders = new EnumMap<>(ProcessStream.class);
    private long total;
    private boolean stderrTruncated;
    private boolean complete;

    @Override
    public synchronized void onOutput(ProcessStream stream, ByteBuffer bytes)
    {
        append(decoders.computeIfAbsent(stream, ignored -> new Utf8Stream()).decode(bytes, false));
    }

    @Override
    public synchronized void onTruncated(ProcessStream stream)
    {
        stderrTruncated |= stream == ProcessStream.STDERR;
    }

    synchronized void append(String text)
    {
        int toHead = Math.min(text.length(), HEAD_CHARACTERS - head.length());
        head.append(text, 0, toHead);
        tail.append(text, toHead, text.length());
        if (tail.length() > 2 * TAIL_CHARACTERS) {
            tail.delete(0, tail.length() - TAIL_CHARACTERS);
        }
        total += text.length();
    }

    /** Ends decoding and returns what the run record keeps: the head, a labelled gap, and the last 1 MiB. */
    synchronized Stored finish()
    {
        decoders.values().forEach(decoder -> append(decoder.decode(ByteBuffer.allocate(0), true)));
        decoders.clear();
        tail.delete(0, Math.max(0, tail.length() - TAIL_CHARACTERS));
        tail.trimToSize();
        String kept = tail.toString();
        long omitted = total - head.length() - kept.length();
        String output = omitted > 0 ? head + "\n… " + omitted + " chars of output omitted …\n" + kept : head + kept;
        // Stderr beyond its capture limit was never seen, so how much is missing is unknown.
        return new Stored(output, omitted > 0 || stderrTruncated, stderrTruncated ? OptionalLong.empty() : OptionalLong.of(omitted));
    }

    /** Marks the run's record saved, after which a reader at the end is complete. */
    synchronized void markComplete()
    {
        complete = true;
    }

    synchronized OutputChunk read(long after)
    {
        checkArgument(after >= 0, "after must not be negative");
        long tailStart = total - tail.length();
        if (after < head.length()) {
            String text = head.substring((int) after);
            // Without a dropped middle the tail continues the head directly.
            return tailStart == head.length()
                    ? new OutputChunk(text + tail, total, 0, complete)
                    : new OutputChunk(text, head.length(), 0, false);
        }
        long from = Math.min(Math.max(after, tailStart), total);
        return new OutputChunk(tail.substring((int) (from - tailStart)), total, Math.max(0, tailStart - after), complete);
    }

    record Stored(String output, boolean truncated, OptionalLong omittedCharacters) {}

    /** Decodes one stream's bytes, carrying a character split across chunks to the next one. */
    private static final class Utf8Stream
    {
        private final CharsetDecoder decoder = UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private ByteBuffer pending = ByteBuffer.allocate(0);

        String decode(ByteBuffer bytes, boolean end)
        {
            ByteBuffer input = ByteBuffer.allocate(pending.remaining() + bytes.remaining()).put(pending).put(bytes.duplicate()).flip();
            CharBuffer output = CharBuffer.allocate(input.remaining() + 1);
            decoder.decode(input, output, end);
            if (end) {
                decoder.flush(output);
            }
            pending = input.slice();
            return output.flip().toString();
        }
    }
}
