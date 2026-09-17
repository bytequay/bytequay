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

import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.app.review.LocalRunService.OutputChunk;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.OptionalLong;

import static com.bytequay.app.review.RunOutput.HEAD_CHARACTERS;
import static com.bytequay.app.review.RunOutput.TAIL_CHARACTERS;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class TestRunOutput
{
    @Test
    void decodesEachStreamAcrossChunkBoundariesInArrivalOrder()
    {
        RunOutput output = new RunOutput();
        byte[] euro = "€".getBytes(UTF_8);
        output.onOutput(ProcessStream.STDOUT, ByteBuffer.wrap(new byte[] {'a', euro[0]}));
        output.onOutput(ProcessStream.STDERR, ByteBuffer.wrap("[err]".getBytes(UTF_8)));
        output.onOutput(ProcessStream.STDOUT, ByteBuffer.wrap(new byte[] {euro[1], euro[2], 'b'}));

        assertThat(output.read(0)).isEqualTo(new OutputChunk("a[err]€b", 8, 0, false));
        assertThat(output.read(6)).isEqualTo(new OutputChunk("€b", 8, 0, false));
        assertThat(output.read(100)).isEqualTo(new OutputChunk("", 8, 0, false));

        RunOutput.Stored stored = output.finish();
        assertThat(stored).isEqualTo(new RunOutput.Stored("a[err]€b", false, OptionalLong.of(0)));
        assertThat(output.read(8).complete()).isFalse();
        output.markComplete();
        assertThat(output.read(8)).isEqualTo(new OutputChunk("", 8, 0, true));
    }

    @Test
    void keepsTheHeadAndTailAndReportsTheDroppedMiddleAsSkipped()
    {
        RunOutput output = new RunOutput();
        String head = "h".repeat(HEAD_CHARACTERS);
        String middle = "m".repeat(3 * TAIL_CHARACTERS);
        String tail = "t".repeat(TAIL_CHARACTERS);
        output.append(head);
        output.append(middle);
        output.append(tail);
        long total = head.length() + middle.length() + tail.length();

        // A reader still in the head gets the rest of the head, then learns how much of the middle it missed.
        assertThat(output.read(10)).isEqualTo(new OutputChunk("h".repeat(HEAD_CHARACTERS - 10), HEAD_CHARACTERS, 0, false));
        OutputChunk afterGap = output.read(HEAD_CHARACTERS);
        assertThat(afterGap.nextOffset()).isEqualTo(total);
        assertThat(afterGap.skippedBytes() + afterGap.text().length()).isEqualTo(total - HEAD_CHARACTERS);
        assertThat(afterGap.text()).endsWith(tail);
        assertThat(output.read(total - 5)).isEqualTo(new OutputChunk("ttttt", total, 0, false));

        output.onTruncated(ProcessStream.STDOUT);
        RunOutput.Stored stored = output.finish();
        assertThat(stored.output()).isEqualTo(head + "\n… " + middle.length() + " chars of output omitted …\n" + tail);
        assertThat(stored.truncated()).isTrue();
        assertThat(stored.omittedCharacters()).hasValue(middle.length());
        // Offsets stay those of the full stream after the buffer is trimmed for keeping.
        output.markComplete();
        assertThat(output.read(HEAD_CHARACTERS)).isEqualTo(new OutputChunk(tail, total, middle.length(), true));
    }

    @Test
    void stderrBeyondItsCaptureLimitMakesTheOmittedAmountUnknown()
    {
        RunOutput output = new RunOutput();
        output.append("partial");
        output.onTruncated(ProcessStream.STDERR);

        assertThat(output.finish()).isEqualTo(new RunOutput.Stored("partial", true, OptionalLong.empty()));
    }
}
