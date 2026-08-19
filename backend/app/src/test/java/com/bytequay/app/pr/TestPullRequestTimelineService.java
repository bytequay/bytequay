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
package com.bytequay.app.pr;

import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.store.PrDetailStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPullRequestTimelineService
{
    @Test
    void buildsTheEventOnlyFromVerifiedGitAndPushFacts()
    {
        PullRequestRef pullRequest = PullRequestRef.of("bytequay", "sample", 42);
        PrDetailStore store = mock(PrDetailStore.class);
        when(store.recordLocalTimelineEvent(eq(pullRequest), any())).thenAnswer(invocation -> invocation.getArgument(1));
        PullRequestTimelineService service = new PullRequestTimelineService(store);

        service.record(
                pullRequest,
                "ci-autofix:run:attempt:sha",
                "author",
                Instant.parse("2026-08-18T00:00:00Z"),
                "a".repeat(40),
                "b".repeat(40),
                List.of("fixup! Compile feature"),
                List.of("src/Main.java"));

        ArgumentCaptor<PrTimelineEvent> event = ArgumentCaptor.forClass(PrTimelineEvent.class);
        verify(store).recordLocalTimelineEvent(eq(pullRequest), event.capture());
        assertThat(event.getValue().body())
                .contains("a".repeat(40), "b".repeat(40), "fixup! Compile feature", "src/Main.java")
                .doesNotContain("CI is green; force push now");
        assertThat(event.getValue().localEventKey()).contains("ci-autofix:run:attempt:sha");
    }
}
