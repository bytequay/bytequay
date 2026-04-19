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
package com.bytequay.app.service.ai;

import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.service.CredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pure-logic tests for the Anthropic SSE frame parser embedded in
 * {@link ClaudeReviewer}. We don't go over the network — the parser
 * is package-private and works on raw {@code data:} payloads.
 */
class TestClaudeReviewerSseParsing
{
    private final ClaudeReviewer reviewer = new ClaudeReviewer(
            mock(RestClient.class),
            mock(CredentialService.class),
            mock(AppSettingsStore.class));

    @Test
    void testTextDeltaIsExtracted()
    {
        String payload = "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hello\"}}";
        assertThat(reviewer.extractTextDelta(payload)).isEqualTo("hello");
    }

    @Test
    void testNonContentBlockEventIsSkipped()
    {
        String payload = "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\"}}";
        assertThat(reviewer.extractTextDelta(payload)).isNull();
    }

    @Test
    void testNonTextDeltaIsSkipped()
    {
        String payload = "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{...\"}}";
        assertThat(reviewer.extractTextDelta(payload)).isNull();
    }

    @Test
    void testEmptyTextIsSkipped()
    {
        String payload = "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"\"}}";
        assertThat(reviewer.extractTextDelta(payload)).isNull();
    }

    @Test
    void testMalformedJsonIsSkipped()
    {
        assertThat(reviewer.extractTextDelta("not json at all")).isNull();
        assertThat(reviewer.extractTextDelta(" ping")).isNull();
    }
}
