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
package com.bytequay.core.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCommitEnrichment
{
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void rejectsPullRequestDetailsWithoutANumber()
    {
        assertThatThrownBy(() -> new CommitEnrichment(
                1,
                "abc",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("Title"),
                Optional.empty(),
                Optional.empty(),
                Optional.of(NOW),
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pullRequestTitle requires");
    }
}
