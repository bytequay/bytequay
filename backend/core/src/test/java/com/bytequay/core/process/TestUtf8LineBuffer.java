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
package com.bytequay.core.process;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestUtf8LineBuffer
{
    @Test
    void decodesSplitUtf8AndFlushesTheLastLine()
    {
        List<String> received = new ArrayList<>();
        Utf8LineBuffer lines = new Utf8LineBuffer(32, true, received::add);
        byte[] bytes = "你好\r\nlast".getBytes(UTF_8);
        lines.accept(ByteBuffer.wrap(bytes, 0, 2));
        assertThat(received).isEmpty();
        lines.accept(ByteBuffer.wrap(bytes, 2, bytes.length - 2));
        assertThat(received).containsExactly("你好");
        lines.finish();
        assertThat(received).containsExactly("你好", "last");
    }

    @Test
    void rejectsOversizedProtocolLinesBeforeDelivery()
    {
        List<String> received = new ArrayList<>();
        Utf8LineBuffer lines = new Utf8LineBuffer(4, true, received::add);
        assertThatThrownBy(() -> lines.accept(ByteBuffer.wrap("12345\n".getBytes(UTF_8))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Process output line exceeds 4 bytes");
        assertThat(received).isEmpty();
    }

    @Test
    void truncatesDiagnosticsWithoutAffectingTheNextLine()
    {
        List<String> received = new ArrayList<>();
        Utf8LineBuffer lines = new Utf8LineBuffer(4, false, received::add);
        lines.accept(ByteBuffer.wrap("12345\nok\n".getBytes(UTF_8)));
        lines.finish();
        assertThat(received).containsExactly("1234 [truncated]", "ok");
    }
}
