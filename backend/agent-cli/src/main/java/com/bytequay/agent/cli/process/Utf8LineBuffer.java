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
package com.bytequay.agent.cli.process;

import com.bytequay.agent.spi.AgentUnavailableException;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Converts bounded UTF-8 process chunks into complete lines. */
public final class Utf8LineBuffer
{
    private final int maximumBytes;
    private final boolean rejectOverflow;
    private final Consumer<String> lines;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean overflow;

    public Utf8LineBuffer(int maximumBytes, boolean rejectOverflow, Consumer<String> lines)
    {
        checkArgument(maximumBytes > 0, "maximumBytes is not positive");
        this.maximumBytes = maximumBytes;
        this.rejectOverflow = rejectOverflow;
        this.lines = requireNonNull(lines, "lines is null");
    }

    public synchronized void accept(ByteBuffer bytes)
    {
        requireNonNull(bytes, "bytes is null");
        while (bytes.hasRemaining()) {
            byte next = bytes.get();
            if (next == '\n') {
                emit();
            }
            else if (next != '\r') {
                if (buffer.size() < maximumBytes) {
                    buffer.write(next);
                }
                else {
                    overflow = true;
                }
            }
        }
    }

    public synchronized void finish()
    {
        if (buffer.size() > 0 || overflow) {
            emit();
        }
    }

    private void emit()
    {
        if (overflow && rejectOverflow) {
            throw new AgentUnavailableException("Process output line exceeds " + maximumBytes + " bytes");
        }
        String line = buffer.toString(StandardCharsets.UTF_8);
        buffer.reset();
        if (overflow) {
            line += " [truncated]";
            overflow = false;
        }
        if (!line.isEmpty()) {
            lines.accept(line);
        }
    }
}
