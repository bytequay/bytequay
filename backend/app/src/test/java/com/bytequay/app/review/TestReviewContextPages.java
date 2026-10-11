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

import com.bytequay.agent.spi.ReviewTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class TestReviewContextPages
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void pagesCompleteCapturedTextWithoutSplittingUnicodeAndDistinguishesGuidance()
            throws Exception
    {
        String text = "a".repeat(11_999) + "\uD83D\uDE00" + "b".repeat(12_000) + "end";
        ReviewTool tool = ReviewContextPages.tool(ImmutableMap.of("pr", text, "assignment", "Read frozen source"), () -> true);
        StringBuilder restored = new StringBuilder();
        int page = 1;
        while (true) {
            JsonNode result = mapper.readTree(tool.call().apply("{\"kind\":\"pr\",\"page\":" + page + "}"));
            String chunk = result.path("text").asText();
            assertThat(Character.isLowSurrogate(chunk.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
            assertThat(chunk).hasSizeLessThanOrEqualTo(12_001);
            assertThat(result.path("referenceOnly").asBoolean()).isTrue();
            assertThat(result.path("totalCharacters").asInt()).isEqualTo(text.length());
            restored.append(chunk);
            if (result.path("nextPage").isNull()) {
                break;
            }
            page = result.path("nextPage").asInt();
        }
        assertThat(restored.toString()).isEqualTo(text);
        assertThat(mapper.readTree(tool.call().apply("{\"kind\":\"assignment\"}")).path("referenceOnly").asBoolean()).isFalse();
        assertThat(tool.childCallable()).isTrue();
    }

    @Test
    void rejectsOtherContextInvalidPagesAndReadsAfterRevocation()
            throws Exception
    {
        AtomicBoolean active = new AtomicBoolean(true);
        ReviewTool tool = ReviewContextPages.tool(ImmutableMap.of("scope", "head"), active::get);
        for (String args : ImmutableList.of("{}", "null", "[]", "{", "{\"kind\":\"findings\"}", "{\"kind\":\"scope\",\"taskId\":\"other\"}",
                "{\"kind\":\"scope\",\"page\":0}", "{\"kind\":\"scope\",\"page\":2}", "{\"kind\":\"scope\",\"page\":1.5}",
                "{\"kind\":\"scope\",\"page\":2147483648}")) {
            assertThat(mapper.readTree(tool.call().apply(args)).has("error")).as(args).isTrue();
        }
        active.set(false);
        assertThat(mapper.readTree(tool.call().apply("{\"kind\":\"scope\"}")).path("error").asText()).contains("stopped or been replaced");
        assertThat(ReviewContextPages.page("rules", "", 1).path("nextPage").isNull()).isTrue();
    }
}
