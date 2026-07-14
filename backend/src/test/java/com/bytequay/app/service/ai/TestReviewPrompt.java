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

import com.bytequay.app.domain.ReviewRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestReviewPrompt
{
    @Test
    void keepsTheStructuredReviewContractAfterCavemanInstructions()
    {
        String prompt = ReviewPrompt.systemPrompt(
                new ReviewRequest("acme/widget", 42, "Title", "", "sha", "diff"));

        assertThat(prompt).contains("Caveman is mandatory");
        assertThat(prompt).contains("Output format (REQUIRED): a single JSON object");
        assertThat(prompt).endsWith("Pick the most important ones.\n");
    }
}
