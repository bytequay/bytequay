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
package com.bytequay.app.service.local;

import com.bytequay.app.domain.PullRequestDraft;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.ai.LlmReviewer;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLocalRepoServiceDraftPullRequest
{
    @Test
    void testDraftPullRequestSucceedsForFeatureBranchAgainstDefault(@TempDir Path workingDir)
            throws Exception
    {
        Fixture f = new Fixture(workingDir);
        // Lazy-selected: HEAD on disk is master, but the user picked
        // feature/x via card click. Backend must use the supplied
        // head, not what currentBranch would return.
        when(f.gitRunner.refExists(workingDir, "master")).thenReturn(true);
        when(f.gitRunner.diff(workingDir, "master", "feature/x", LocalRepoServiceConstants.DIFF_MAX_BYTES))
                .thenReturn("diff --git a/x b/x\n+hello\n");
        when(f.reviewer.draftPullRequest(
                eq("feature/x"), eq("master"), any(String.class), any()))
                .thenReturn(new PullRequestDraft("Add hello", "## Summary\nadds a hello"));

        PullRequestDraft draft = f.service.draftPullRequestWithAi(
                "trinodb", "trino", "master", "feature/x");

        assertThat(draft.title()).isEqualTo("Add hello");
        assertThat(draft.description()).isEqualTo("## Summary\nadds a hello");
        // Backend trusted the supplied head — never had to ask the
        // working tree.
        verify(f.gitRunner, never()).currentBranch(any());
    }

    @Test
    void testDraftPullRequestRejectsHeadEqualsBase(@TempDir Path workingDir)
            throws Exception
    {
        Fixture f = new Fixture(workingDir);

        // User on master with master picked as base — there's nothing
        // to PR. Backend rejects up front so the model isn't called
        // with an empty diff.
        assertThatThrownBy(() -> f.service.draftPullRequestWithAi(
                "trinodb", "trino", "master", "master"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Head and base are both 'master'");

        // Crucial: never reached the LLM (and never even ran git diff).
        verify(f.reviewer, never()).draftPullRequest(any(), any(), any(), any());
        verify(f.gitRunner, never()).diff(any(), any(), any(), anyInt());
    }

    /** Wires up the mock graph each test needs. Kept as a small
     *  fixture so the two tests stay focused on what's different. */
    private static final class Fixture
    {
        final WatchedRepoStore watchedRepoStore = mock(WatchedRepoStore.class);
        final GitRunner gitRunner = mock(GitRunner.class);
        final PullRequestRepository gitHub = mock(PullRequestRepository.class);
        final PullRequestStore pullRequestStore = mock(PullRequestStore.class);
        final LlmReviewerRegistry registry = mock(LlmReviewerRegistry.class);
        final LlmReviewer reviewer = mock(LlmReviewer.class);
        final LocalRepoService service;

        Fixture(Path workingDir)
                throws IOException
        {
            when(watchedRepoStore.find("trinodb", "trino"))
                    .thenReturn(Optional.of(new WatchedRepo(
                            1L, "trinodb", "trino", 0, workingDir.toString(), null, null)));
            when(registry.active()).thenReturn(reviewer);
            this.service = new LocalRepoService(
                    watchedRepoStore, gitRunner, gitHub, pullRequestStore, registry);
        }
    }

    /** Pulled out so the test asserts on the same byte cap the service
     *  enforces — keeps the diff stub matching the real call. */
    private static final class LocalRepoServiceConstants
    {
        static final int DIFF_MAX_BYTES = 60_000;
    }
}
