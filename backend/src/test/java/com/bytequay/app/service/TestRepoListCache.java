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
package com.bytequay.app.service;

import com.bytequay.app.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TestRepoListCache
{
    private static final RepoRef REPO = RepoRef.of("trinodb", "trino");

    private final RepoListCache cache = new RepoListCache();

    @Test
    void testGetPullsCachesWithinTtl()
    {
        AtomicInteger calls = new AtomicInteger();
        cache.getPulls(REPO, () -> {
            calls.incrementAndGet();
            return ImmutableList.of();
        });
        cache.getPulls(REPO, () -> {
            calls.incrementAndGet();
            return ImmutableList.of();
        });

        // Second call hits the cache; loader runs only once.
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void testInvalidatePullsForcesReload()
    {
        AtomicInteger calls = new AtomicInteger();
        cache.getPulls(REPO, () -> {
            calls.incrementAndGet();
            return ImmutableList.of();
        });
        cache.invalidatePulls(REPO);
        cache.getPulls(REPO, () -> {
            calls.incrementAndGet();
            return ImmutableList.of();
        });

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void testInvalidatePullsLeavesOtherKindsUntouched()
    {
        AtomicInteger activityCalls = new AtomicInteger();
        // Seed the activity cache for the same repo, then invalidate pulls —
        // activity should remain hot.
        cache.getActivity(REPO, () -> {
            activityCalls.incrementAndGet();
            return ImmutableList.of();
        });
        cache.invalidatePulls(REPO);
        cache.getActivity(REPO, () -> {
            activityCalls.incrementAndGet();
            throw new IllegalStateException("activity cache was unexpectedly invalidated");
        });

        assertThat(activityCalls.get()).isEqualTo(1);
    }

    @Test
    void testCachesAreScopedPerRepo()
    {
        RepoRef otherRepo = RepoRef.of("trinodb", "trino-py");
        AtomicInteger calls = new AtomicInteger();
        cache.getIssues(REPO, "open", () -> {
            calls.incrementAndGet();
            return ImmutableList.of();
        });
        cache.getIssues(otherRepo, "open", () -> {
            calls.incrementAndGet();
            return ImmutableList.of();
        });

        // Each (kind, repo) pair has its own slot; both loaders fire.
        assertThat(calls.get()).isEqualTo(2);
    }
}
