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

import com.bytequay.app.domain.ReviewOutput;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.service.CredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Parsing the model's free-form review output is fragile: it must find
 * the JSON object even when the model wraps it in prose or code fences,
 * and normalize odd severities. These pin {@code extractJsonObject} and
 * {@code parseReviewOutput}.
 */
class TestClaudeReviewerOutputParsing
{
    private final ClaudeReviewer reviewer = new ClaudeReviewer(
            mock(RestClient.class), mock(CredentialService.class), mock(AppSettingsStore.class));

    @Test
    void extractsTheJsonObjectFromSurroundingProseAndFences()
    {
        assertThat(ClaudeReviewer.extractJsonObject("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(ClaudeReviewer.extractJsonObject("Here you go: {\"a\":1} — done."))
                .isEqualTo("{\"a\":1}");
        assertThat(ClaudeReviewer.extractJsonObject("```json\n{\"a\":1}\n```"))
                .isEqualTo("{\"a\":1}");
    }

    @Test
    void throwsWhenThereIsNoJsonObject()
    {
        assertThatThrownBy(() -> ClaudeReviewer.extractJsonObject("no braces here"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parsesSummaryAndLineCommentsWithSeverityNormalized()
    {
        String text = "Sure! ```json\n"
                + "{\"summary\":\"Looks good overall\","
                + "\"comments\":["
                + "{\"file\":\"A.java\",\"line\":10,\"severity\":\"warning\",\"body\":\"nit\"},"
                + "{\"file\":\"B.java\",\"line\":3,\"severity\":\"CRITICAL\",\"body\":\"oops\"}"
                + "]}\n```";

        ReviewOutput out = reviewer.parseReviewOutput(text, "claude-test");

        assertThat(out.summary()).isEqualTo("Looks good overall");
        assertThat(out.modelName()).isEqualTo("claude-test");
        assertThat(out.comments()).hasSize(2);
        assertThat(out.comments().get(0).file()).isEqualTo("A.java");
        assertThat(out.comments().get(0).severity()).isEqualTo("warning");
        // Unknown severity falls back to the safe "suggestion".
        assertThat(out.comments().get(1).severity()).isEqualTo("suggestion");
    }

    @Test
    void defaultsAMissingSummaryAndCommentsToEmpty()
    {
        ReviewOutput out = reviewer.parseReviewOutput("{}", "claude-test");
        assertThat(out.summary()).isEmpty();
        assertThat(out.comments()).isEmpty();
    }

    @Test
    void throwsOnUnparseableJson()
    {
        assertThatThrownBy(() -> reviewer.parseReviewOutput("{not valid json}", "claude-test"))
                .isInstanceOf(IllegalStateException.class);
    }
}
