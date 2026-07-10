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
package com.bytequay.app.service.threads;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestMessageAttachments
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void plainTextPassesThroughUnchanged()
    {
        String encoded = MessageAttachments.encode(mapper, "hello world", List.of());

        assertThat(encoded).isEqualTo("hello world");
        MessageAttachments.Decoded decoded = MessageAttachments.decode(mapper, encoded);
        assertThat(decoded.text()).isEqualTo("hello world");
        assertThat(decoded.images()).isEmpty();
    }

    @Test
    void nullImagesBehavesLikeNoImages()
    {
        String encoded = MessageAttachments.encode(mapper, "hi", null);

        assertThat(encoded).isEqualTo("hi");
    }

    @Test
    void encodeThenDecodeRoundTripsTextAndImages()
    {
        List<String> images = List.of("/tmp/attachments/t1/a.png", "/tmp/attachments/t1/b.jpg");

        String encoded = MessageAttachments.encode(mapper, "look at this", images);
        MessageAttachments.Decoded decoded = MessageAttachments.decode(mapper, encoded);

        assertThat(decoded.text()).isEqualTo("look at this");
        assertThat(decoded.images()).containsExactlyElementsOf(images);
    }

    @Test
    void anyPreExistingPlainTextTurnDecodesAsPlainTextWithNoImages()
    {
        // Unattended/automation turns (CI-fix prompts, completion summaries,
        // etc.) never wear the marker — decode must treat them as ordinary
        // text, even when they happen to look like JSON.
        String raw = "{\"text\": \"a user literally typed this json snippet\"}";

        MessageAttachments.Decoded decoded = MessageAttachments.decode(mapper, raw);

        assertThat(decoded.text()).isEqualTo(raw);
        assertThat(decoded.images()).isEmpty();
    }

    @Test
    void decodeNullIsEmptyText()
    {
        MessageAttachments.Decoded decoded = MessageAttachments.decode(mapper, null);

        assertThat(decoded.text()).isEmpty();
        assertThat(decoded.images()).isEmpty();
    }

    @Test
    void encodeMessageMatchesThePlainShapeWithNoImages()
    {
        String json = MessageAttachments.encodeMessage(mapper, "hi there", List.of());

        assertThat(json).isEqualTo("{\"text\":\"hi there\"}");
    }

    @Test
    void encodeMessageIncludesImagesWhenPresent()
    {
        String json = MessageAttachments.encodeMessage(mapper, "see attached", List.of("/tmp/a.png"));

        assertThat(json).contains("\"text\":\"see attached\"").contains("\"images\":[\"/tmp/a.png\"]");
    }
}
