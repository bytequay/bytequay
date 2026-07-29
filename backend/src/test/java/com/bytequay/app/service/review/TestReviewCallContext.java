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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewCallContext
{
    private final ReviewCallContext calls = new ReviewCallContext();

    @Test
    void executesSynchronouslyInsideTheExactContext()
    {
        ReviewPass pass = pass();
        String result = calls.invoke(
                pass, ReviewCallContext.ProviderLane.API, "lead",
                () -> calls.invoke(
                        pass, ReviewCallContext.ProviderLane.CLI, "seat",
                        () -> {
                            calls.requireCurrent(
                                    pass, ReviewCallContext.ProviderLane.CLI, "seat");
                            return "done";
                        }));

        assertThat(result).isEqualTo("done");
        assertThatThrownBy(() -> calls.requireCurrent(
                pass, ReviewCallContext.ProviderLane.API, "lead"))
                .hasMessageContaining("no exact call context");
    }

    @Test
    void batchPreservesSubmissionOrder()
    {
        ReviewPass pass = pass();
        assertThat(calls.invokeAll(List.of(
                new ReviewCallContext.Work<>(pass,
                        ReviewCallContext.ProviderLane.API, "one", () -> 1),
                new ReviewCallContext.Work<>(pass,
                        ReviewCallContext.ProviderLane.CLI, "two", () -> 2))))
                .containsExactly(1, 2);
    }

    @Test
    void batchPreservesFailedSeatAbstentions()
    {
        ReviewPass pass = pass();
        List<Integer> results = calls.invokeAll(List.of(
                new ReviewCallContext.Work<>(pass,
                        ReviewCallContext.ProviderLane.API, "failed", () -> null),
                new ReviewCallContext.Work<>(pass,
                        ReviewCallContext.ProviderLane.CLI, "passed", () -> 2)));

        assertThat(results).containsExactly(null, 2);
        assertThatThrownBy(() -> results.add(3))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ReviewPass pass()
    {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return new ReviewPass(
                "pass-1", "thread-1", "owner/repo", 1, "head",
                ReviewPhase.KICKOFF, 0, 3, 100, 0, null, now, null);
    }
}
