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
package com.bytequay.core.review;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgentReview
{
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void retainsReplayIdentityAndIndependentHeadsWhenClosing()
    {
        AgentReview.Replay replay = new AgentReview.Replay("replay", 42, true, NOW.minusSeconds(60));
        AgentReview initial = new AgentReview("review", "workspace", "owner/repo", OptionalInt.empty(), OptionalLong.empty(), "task", Optional.empty(),
                AgentReview.State.OPEN, false, Optional.empty(), Optional.of("old-head"), Optional.of("old-worktree"), 2, NOW, NOW, Optional.empty(), Optional.of(replay));
        AgentReview reviewed = initial.withObservedHead("new-head", NOW.plusSeconds(1))
                .withWorktreeHead("aligned-head", NOW.plusSeconds(2))
                .withLatestCompletedRun("run", NOW.plusSeconds(3))
                .withFollowUpPaused(true, NOW.plusSeconds(4));
        Instant closedAt = NOW.plusSeconds(5);
        AgentReview closed = reviewed.closed(closedAt).withVersion(3);

        assertThat(closed).isEqualTo(new AgentReview("review", "workspace", "owner/repo", OptionalInt.empty(), OptionalLong.empty(), "task", Optional.empty(),
                AgentReview.State.CLOSED, true, Optional.of("run"), Optional.of("new-head"), Optional.of("aligned-head"), 3, NOW, closedAt, Optional.of(closedAt),
                Optional.of(replay)));
        assertThat(initial.observedHeadSha()).contains("old-head");
        assertThat(reviewed.withFollowUpPaused(false, closedAt).followUpPaused()).isFalse();
        assertThatThrownBy(() -> reviewed.withPullRequest(42, OptionalLong.of(7), closedAt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reviewed.withVersion(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retainsLocalSourceWhenLinkingAPullRequest()
    {
        AgentReview local = new AgentReview(
                "review",
                "workspace",
                "owner/repo",
                OptionalInt.empty(),
                OptionalLong.empty(),
                "task",
                Optional.of("source"),
                AgentReview.State.OPEN,
                false,
                Optional.of("run"),
                Optional.of("head"),
                Optional.of("aligned-head"),
                4,
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty());
        AgentReview linked = local.withPullRequest(42, OptionalLong.of(7), NOW.plusSeconds(1));

        assertThat(linked).usingRecursiveComparison().ignoringFields("number", "pullRequestId", "updatedAt").isEqualTo(local);
        assertThat(linked.number()).hasValue(42);
        assertThat(linked.pullRequestId()).hasValue(7);
        assertThat(linked.updatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThatThrownBy(() -> linked.withPullRequest(43, OptionalLong.of(8), NOW.plusSeconds(2))).isInstanceOf(IllegalArgumentException.class);
    }
}
