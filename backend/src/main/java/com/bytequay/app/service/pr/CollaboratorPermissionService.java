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

import com.bytequay.app.domain.GithubReviewState;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.GitHubAccountRepository;
import com.bytequay.app.repository.sqlite.RepoWriteCollaboratorStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Resolves whether a reviewer has write permission on a repo — the test that
 * decides which approvals count toward a task's minimum-approvals gate (the
 * same distinction GitHub draws between its green and grey approval marks).
 * Verdicts are cached in the DB and only re-fetched from GitHub once past the
 * TTL, since a collaborator's permission rarely changes.
 */
@Service
public class CollaboratorPermissionService
{
    /** How long a cached write verdict is trusted before it's re-fetched. */
    private static final Duration TTL = Duration.ofDays(7);

    private final RepoWriteCollaboratorStore store;
    private final GitHubAccountRepository gitHub;

    public CollaboratorPermissionService(RepoWriteCollaboratorStore store, GitHubAccountRepository gitHub)
    {
        this.store = requireNonNull(store, "store is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
    }

    /** Whether {@code login} has write permission on the repo — cached, with a
     *  GitHub fetch (and upsert) on a cold or stale entry. */
    public boolean canWrite(String pat, RepoRef repo, String login)
    {
        String repoFullName = repo.owner() + "/" + repo.repo();
        Optional<Boolean> cached = store.find(repoFullName, login, Instant.now().minus(TTL));
        if (cached.isPresent()) {
            return cached.get();
        }
        boolean canWrite = gitHub.fetchCollaboratorCanWrite(pat, repo, login);
        store.save(repoFullName, login, canWrite, Instant.now());
        return canWrite;
    }

    /**
     * Count the distinct reviewers who approved AND have write permission — the
     * approvals GitHub renders with a green mark. A reviewer who approved more
     * than once counts once; permission is resolved through {@link #canWrite}
     * so repeat polls hit the cache.
     */
    public int countWriteApprovals(String pat, RepoRef repo, List<PrReviewState> reviews)
    {
        return (int) reviews.stream()
                .filter(review -> GithubReviewState.APPROVED.equals(review.state()))
                .map(PrReviewState::login)
                .distinct()
                .filter(login -> canWrite(pat, repo, login))
                .count();
    }
}
