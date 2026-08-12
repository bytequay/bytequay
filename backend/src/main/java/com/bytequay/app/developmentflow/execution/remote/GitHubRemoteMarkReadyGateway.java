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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.GitHubPullRequestReadRepository;
import com.bytequay.app.repository.GitHubPullRequestWriteRepository;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/** Exact-head GitHub adapter for the user/policy-authorized mark-ready effect. */
@Component
public final class GitHubRemoteMarkReadyGateway
        implements RemoteMarkReadyOperationHandler.MarkReadyGateway
{
    private final GitHubPullRequestReadRepository pullRequests;
    private final GitHubPullRequestWriteRepository pullRequestWrites;
    private final PatResolver pats;

    @Autowired
    public GitHubRemoteMarkReadyGateway(
            GitHubPullRequestReadRepository pullRequests,
            GitHubPullRequestWriteRepository pullRequestWrites,
            PatResolver pats)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pullRequestWrites = requireNonNull(pullRequestWrites, "pullRequestWrites is null");
        this.pats = requireNonNull(pats, "pats is null");
    }

    GitHubRemoteMarkReadyGateway(PullRequestRepository gitHub, PatResolver pats)
    {
        this(gitHub, gitHub, pats);
    }

    @Override
    public void markReady(
            RemoteMarkReadyOperationHandler.Operation operation,
            ExecutionContext execution)
            throws Exception
    {
        Target target = target(operation);
        requireActive(execution);
        PrRawDetail before = fetchExact(operation, target);
        if (!before.draft()) {
            return;
        }
        pullRequestWrites.setPullRequestDraft(target.pat(), target.pullRequest(), false);
    }

    private PrRawDetail fetchExact(
            RemoteMarkReadyOperationHandler.Operation operation, Target target)
    {
        PrRawDetail detail = requireNonNull(
                pullRequests.fetchPrDetail(target.pat(), target.pullRequest()),
                "GitHub returned no pull request detail");
        if (!operation.headSha().equals(detail.headSha())
                || !operation.baseSha().equals(detail.baseSha())) {
            throw new IllegalStateException(
                    "mark-ready Remote subject moved outside its authorization");
        }
        return detail;
    }

    private Target target(RemoteMarkReadyOperationHandler.Operation operation)
    {
        requireNonNull(operation, "operation is null");
        RepoRef repository = RepoRef.parse(operation.repositoryId());
        return new Target(
                pats.resolve(repository.fullName()),
                PullRequestRef.of(
                        repository.owner(), repository.repo(),
                        operation.pullRequestNumber()));
    }

    private static void requireActive(ExecutionContext execution)
            throws ExecutionPorts.OperationCanceledException
    {
        requireNonNull(execution, "execution is null");
        if (execution.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Mark-ready was canceled");
        }
    }

    private record Target(String pat, PullRequestRef pullRequest) {}
}
