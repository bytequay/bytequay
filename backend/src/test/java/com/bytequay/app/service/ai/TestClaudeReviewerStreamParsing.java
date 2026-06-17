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
 * Parsing the Anthropic streaming SSE frames is fragile — a wrong shape
 * silently drops the live token feed. These pin {@code extractTextDelta}:
 * a {@code text_delta} yields its text, everything else (other event /
 * delta types, empty text, malformed JSON) yields null.
 */
class TestClaudeReviewerStreamParsing
{
    private final ClaudeReviewer reviewer = new ClaudeReviewer(
            mock(RestClient.class), mock(CredentialService.class), mock(AppSettingsStore.class));

    @Test
    void extractsTheTextFromAContentBlockTextDelta()
    {
        String payload = "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}";
        assertThat(reviewer.extractTextDelta(payload)).isEqualTo("Hello");
    }

    @Test
    void returnsNullForNonTextEvents()
    {
        // A different top-level event.
        assertThat(reviewer.extractTextDelta(
                "{\"type\":\"message_start\",\"message\":{}}")).isNull();
        // A content_block_delta carrying a non-text delta (tool-input JSON).
        assertThat(reviewer.extractTextDelta(
                "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\"}}")).isNull();
    }

    @Test
    void returnsNullForAnEmptyTextDelta()
    {
        assertThat(reviewer.extractTextDelta(
                "{\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"text_delta\",\"text\":\"\"}}")).isNull();
    }

    @Test
    void returnsNullForMalformedJson()
    {
        assertThat(reviewer.extractTextDelta("not json at all")).isNull();
        assertThat(reviewer.extractTextDelta("")).isNull();
    }
}
