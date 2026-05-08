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

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.SuggestedReviewer;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitHubResponseCache
{
    private static final RepoRef REPO = RepoRef.of("trinodb", "trino");
    private static final PullRequestRef PR = PullRequestRef.of("trinodb", "trino", 123);

    private final GitHubResponseCache cache = new GitHubResponseCache();

    @Test
    void testViewerCanWriteCachesByPatFingerprint()
    {
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache.getViewerCanWrite("pat-a", REPO, () -> loads.incrementAndGet() == 1)).isTrue();
        assertThat(cache.getViewerCanWrite("pat-a", REPO, () -> false)).isTrue();
        assertThat(cache.getViewerCanWrite("pat-b", REPO, () -> {
            loads.incrementAndGet();
            return false;
        })).isFalse();

        assertThat(loads).hasValue(2);
    }

    @Test
    void testListValuesAreImmutable()
    {
        List<String> lines = cache.getFileBlobLines(
                "pat",
                REPO,
                "README.md",
                "abc123",
                () -> List.of("one", "two"));

        assertThatThrownBy(() -> lines.add("three"))
                .isInstanceOf(UnsupportedOperationException.class);

        List<DiffFile> diffFiles = cache.getCommitDiffFiles(
                "pat",
                PR,
                "abc123",
                () -> List.of(new DiffFile("README.md", "modified", 1, 0, "@@ patch")));

        assertThatThrownBy(() -> diffFiles.clear())
                .isInstanceOf(UnsupportedOperationException.class);

        List<SuggestedReviewer> reviewers = cache.getSuggestedReviewers(
                "pat",
                PR,
                () -> List.of(new SuggestedReviewer("alice", null, null, false, true)));

        assertThatThrownBy(() -> reviewers.remove(0))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testInvalidatePullRequestOnlyClearsPrScopedCaches()
    {
        AtomicInteger viewerLoads = new AtomicInteger();
        AtomicInteger blobLoads = new AtomicInteger();
        AtomicInteger diffLoads = new AtomicInteger();
        AtomicInteger reviewerLoads = new AtomicInteger();
        AtomicInteger otherDiffLoads = new AtomicInteger();
        AtomicInteger otherReviewerLoads = new AtomicInteger();
        PullRequestRef otherPr = PullRequestRef.of("trinodb", "trino", 124);

        cache.getViewerCanWrite("pat", REPO, () -> viewerLoads.incrementAndGet() == 1);
        cache.getFileBlobLines("pat", REPO, "README.md", "abc123", () -> ImmutableList.of("line " + blobLoads.incrementAndGet()));
        cache.getCommitDiffFiles("pat", PR, "abc123", () -> diff(diffLoads.incrementAndGet()));
        cache.getSuggestedReviewers("pat", PR, () -> reviewers(reviewerLoads.incrementAndGet()));
        cache.getCommitDiffFiles("pat", otherPr, "abc123", () -> diff(otherDiffLoads.incrementAndGet()));
        cache.getSuggestedReviewers("pat", otherPr, () -> reviewers(otherReviewerLoads.incrementAndGet()));

        cache.invalidatePullRequest(PR);

        cache.getViewerCanWrite("pat", REPO, () -> {
            viewerLoads.incrementAndGet();
            return false;
        });
        cache.getFileBlobLines("pat", REPO, "README.md", "abc123", () -> ImmutableList.of("line " + blobLoads.incrementAndGet()));
        cache.getCommitDiffFiles("pat", PR, "abc123", () -> diff(diffLoads.incrementAndGet()));
        cache.getSuggestedReviewers("pat", PR, () -> reviewers(reviewerLoads.incrementAndGet()));
        cache.getCommitDiffFiles("pat", otherPr, "abc123", () -> diff(otherDiffLoads.incrementAndGet()));
        cache.getSuggestedReviewers("pat", otherPr, () -> reviewers(otherReviewerLoads.incrementAndGet()));

        assertThat(viewerLoads).hasValue(1);
        assertThat(blobLoads).hasValue(1);
        assertThat(diffLoads).hasValue(2);
        assertThat(reviewerLoads).hasValue(2);
        assertThat(otherDiffLoads).hasValue(1);
        assertThat(otherReviewerLoads).hasValue(1);
    }

    @Test
    void testConcurrentMissesShareLoader()
            throws Exception
    {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() ->
                    cache.getViewerCanWrite("pat", REPO, () -> {
                        loads.incrementAndGet();
                        loaderStarted.countDown();
                        await(releaseLoader);
                        return true;
                    }), executor);

            assertThat(loaderStarted.await(5, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() ->
                    cache.getViewerCanWrite("pat", REPO, () -> {
                        loads.incrementAndGet();
                        return false;
                    }), executor);

            releaseLoader.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(loads).hasValue(1);
    }

    @Test
    void testLoaderRuntimeExceptionPropagatesAsOriginalType()
    {
        assertThatThrownBy(() -> cache.getViewerCanWrite("pat", REPO, () -> {
            throw new IllegalStateException("boom");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    private static ImmutableList<DiffFile> diff(int load)
    {
        return ImmutableList.of(new DiffFile("file" + load + ".java", "modified", 1, 0, "@@ patch"));
    }

    private static ImmutableList<SuggestedReviewer> reviewers(int load)
    {
        return ImmutableList.of(new SuggestedReviewer("reviewer" + load, null, null, false, false));
    }

    private static void await(CountDownLatch latch)
    {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
