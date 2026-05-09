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

import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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
        AtomicInteger metaCalls = new AtomicInteger();
        // Seed the meta cache for the same repo, then invalidate pulls — meta
        // should remain hot.
        cache.getMeta(REPO, () -> {
            metaCalls.incrementAndGet();
            return new RepoMeta(
                    "trinodb/trino",
                    "https://github.com/trinodb/trino",
                    "Distributed SQL query engine",
                    "main",
                    "Apache-2.0",
                    1,
                    2,
                    3,
                    4,
                    1024,
                    Instant.parse("2026-05-08T00:00:00Z"),
                    Instant.parse("2026-05-08T00:00:00Z"),
                    ImmutableList.of(),
                    ImmutableMap.of());
        });
        cache.invalidatePulls(REPO);
        cache.getMeta(REPO, () -> {
            metaCalls.incrementAndGet();
            throw new IllegalStateException("meta cache was unexpectedly invalidated");
        });

        assertThat(metaCalls.get()).isEqualTo(1);
    }

    @Test
    void testCachesAreScopedPerRepo()
    {
        RepoRef otherRepo = RepoRef.of("trinodb", "trino-py");
        AtomicInteger calls = new AtomicInteger();
        cache.getIssues(REPO, () -> {
            calls.incrementAndGet();
            return ImmutableList.of();
        });
        cache.getIssues(otherRepo, () -> {
            calls.incrementAndGet();
            return ImmutableList.of();
        });

        // Each (kind, repo) pair has its own slot; both loaders fire.
        assertThat(calls.get()).isEqualTo(2);
    }
}
