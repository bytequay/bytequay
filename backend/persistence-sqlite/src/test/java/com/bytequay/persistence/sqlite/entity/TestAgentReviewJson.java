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

import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewSummary;
import com.bytequay.core.review.ReviewValidation;
import org.junit.jupiter.api.Test;

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
        assertThat(coverage.warnings()).isEmpty();
    }

    @Test
    void decodesValidationStoredWithoutWarnings()
    {
        ReviewValidation validation = AgentReviewJson.decode("""
                {"investigationStep":1,"candidates":[],"newCandidates":[],
                 "coverage":{"complete":true,"omissions":[],"recordedAt":"2026-09-14T00:00:00Z"}}""", ReviewValidation.class);

        assertThat(validation.coverage().complete()).isTrue();
        assertThat(validation.coverage().warnings()).isEmpty();
    }
}
