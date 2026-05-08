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
package com.bytequay.app.service.pr;

import com.bytequay.app.domain.PullRequest;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.bytequay.app.domain.PullRequest.Origin.AUTHORED;
import static com.bytequay.app.service.pr.PullRequestOrder.SMART;
import static com.bytequay.app.service.pr.PullRequestOrder.UPDATED_AT_DESC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPullRequestOrder
{
    // ── fromKey ────────────────────────────────────────────────────────────────

    @Test
    void testFromKeyNullReturnsSmart()
    {
        assertThat(PullRequestOrder.fromKey(null)).isEqualTo(SMART);
    }

    @Test
    void testFromKeyUnknownReturnsSmart()
    {
        assertThat(PullRequestOrder.fromKey("nonexistent")).isEqualTo(SMART);
    }

    @Test
    void testFromKeySmartKey()
    {
        assertThat(PullRequestOrder.fromKey("smart")).isEqualTo(SMART);
    }

    @Test
    void testFromKeyUpdatedDescKey()
    {
        assertThat(PullRequestOrder.fromKey("updated-desc")).isEqualTo(UPDATED_AT_DESC);
    }

    // ── UPDATED_AT_DESC ────────────────────────────────────────────────────────

    @Test
    void testUpdatedAtDescSortsNewestFirst()
    {
        var older = pr(1, "2024-01-01T00:00:00Z", null, null);
        var newer = pr(2, "2024-06-01T00:00:00Z", null, null);
        assertThat(UPDATED_AT_DESC.sort(ImmutableList.of(older, newer))).containsExactly(newer, older);
    }

    @Test
    void testUpdatedAtDescEmptyListReturnsEmpty()
    {
        assertThat(UPDATED_AT_DESC.sort(ImmutableList.of())).isEmpty();
    }

    @Test
    void testUpdatedAtDescNullListThrows()
    {
        assertThatThrownBy(() -> UPDATED_AT_DESC.sort(null)).isInstanceOf(NullPointerException.class);
    }

    // ── SMART ──────────────────────────────────────────────────────────────────

    @Test
    void testSmartPutsUnviewedFirst()
    {
        var viewed = pr(1, "2024-06-01T00:00:00Z", Instant.now(), null);
        var unviewed = pr(2, "2024-01-01T00:00:00Z", null, null);
        assertThat(SMART.sort(ImmutableList.of(viewed, unviewed))).containsExactly(unviewed, viewed);
    }

    @Test
    void testSmartPutsReviewedLast()
    {
        var reviewed = pr(1, "2024-06-01T00:00:00Z", Instant.now(), Instant.now());
        var unviewed = pr(2, "2024-01-01T00:00:00Z", null, null);
        var viewed = pr(3, "2024-03-01T00:00:00Z", Instant.now(), null);
        assertThat(SMART.sort(ImmutableList.of(reviewed, viewed, unviewed)))
                .containsExactly(unviewed, viewed, reviewed);
    }

    @Test
    void testSmartTiebreaksByUpdatedAtDesc()
    {
        var older = pr(1, "2024-01-01T00:00:00Z", null, null);
        var newer = pr(2, "2024-06-01T00:00:00Z", null, null);
        assertThat(SMART.sort(ImmutableList.of(older, newer))).containsExactly(newer, older);
    }

    private static PullRequest pr(long id, String updatedAt, Instant viewedAt, Instant reviewedAt)
    {
        return new PullRequest(id, "owner/repo", 1, "title", null, "url",
                null, Instant.parse(updatedAt), AUTHORED, ImmutableList.of(), null, false, viewedAt, reviewedAt, null, ImmutableList.of(),
                null, 0, 0, 0, null,
                "open", null, null, null, null, null, null,
                null, null, null);
    }
}
