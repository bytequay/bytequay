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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.core.context.ContextHistoryRead;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextSourceRead;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewSummary;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class TestAgentReviewJson
{
    @Test
    void decodesRunsStoredWithLimitations()
    {
        ReviewSummary summary = AgentReviewJson.decode("""
                {"revision":1,"purpose":"Fix the parser reset","commits":[],"combinedResult":"Sound","evidence":null,
                 "criticalFindingIds":[],"limitations":["No local build"]}""", ReviewSummary.class);
        ReviewCoverage coverage = AgentReviewJson.decode("""
                {"complete":true,"omissions":[],"limitations":["No local build"],"recordedAt":"2026-09-14T00:00:00Z"}""", ReviewCoverage.class);

        assertThat(summary.purpose()).isEqualTo("Fix the parser reset");
        assertThat(coverage.complete()).isTrue();
    }

    @Test
    void retainsTypedHistorySourceReads()
    {
        String excerpt = "Observed closed issue; no resolution inferred";
        var read = new ContextHistoryRead("context-read:history", new ContextReference.Issue(1, RepoRef.of("example", "repo"), 2, Optional.empty()),
                ContextHistoryRead.State.CLOSED, Optional.of(Instant.EPOCH), Optional.empty(), Hashing.sha256().hashString(excerpt, UTF_8).toString(),
                Instant.EPOCH, excerpt.getBytes(UTF_8).length, true, true, ImmutableList.of("Activity refresh failed"), Optional.of("E1"));
        String encoded = AgentReviewJson.encode(read);
        assertThat(encoded).contains("\"kind\":\"HISTORY\"");
        assertThat(AgentReviewJson.decode(encoded, ContextSourceRead.class)).isEqualTo(read);
    }
}
