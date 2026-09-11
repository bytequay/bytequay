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
package com.bytequay.app.repository.github;

import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static java.util.Objects.requireNonNull;

/** Fresh publication facts, including both repository identities for fork-safe matching. */
public record GitHubPullRequestIdentity(
        PullRequestRef pullRequest,
        String url,
        RepoRef headRepository,
        String headBranch,
        String headSha,
        RepoRef baseRepository,
        String baseBranch,
        String baseSha,
        State state,
        boolean draft,
        String author)
{
    public GitHubPullRequestIdentity
    {
        requireNonNull(pullRequest, "pullRequest is null");
        requireNonNull(headRepository, "headRepository is null");
        requireNonNull(baseRepository, "baseRepository is null");
        requireNonNull(state, "state is null");
        checkNotBlank(url, "url");
        checkNotBlank(headBranch, "headBranch");
        checkNotBlank(headSha, "headSha");
        checkNotBlank(baseBranch, "baseBranch");
        checkNotBlank(baseSha, "baseSha");
        checkNotBlank(author, "author");
    }

    public enum State
    {
        OPEN,
        CLOSED,
        MERGED
    }
}
