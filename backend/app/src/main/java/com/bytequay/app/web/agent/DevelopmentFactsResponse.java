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
package com.bytequay.app.web.agent;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.app.agent.DevelopmentBaseline;
import com.bytequay.app.agent.DevelopmentGitInspector;
import com.bytequay.app.agent.DevelopmentPublicationFacts;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;

/** Observed results only; reading them never starts or resumes delivery. */
public record DevelopmentFactsResponse(
        Instant observedAt,
        Optional<Local> local,
        Optional<String> localError,
        Optional<Remote> remote,
        Optional<String> remoteError)
{
    public static DevelopmentFactsResponse inspect(AgentTask task, DevelopmentGitInspector git)
    {
        DevelopmentBaseline baseline;
        try {
            baseline = git.capture(task);
        }
        catch (RuntimeException failure) {
            return new DevelopmentFactsResponse(Instant.now(), Optional.empty(), Optional.of(message(failure)), Optional.empty(), Optional.empty());
        }
        var facts = baseline.local();
        Local local = new Local(facts.branch(), facts.headSha(), facts.stagedFiles(), facts.unstagedFiles(),
                facts.untrackedFiles(), facts.uncommittedFileCount(), facts.commitCount());
        try {
            DevelopmentPublicationFacts publication = git.inspectPublication(baseline.worktree(), baseline.route());
            checkState(publication.matchingPullRequests().size() <= 1, "Several pull requests match this task branch; inspect the PR panel");
            Optional<GitHubPullRequestIdentity> matching = publication.matchingPullRequests().stream().findFirst();
            matching.ifPresent(pr -> {
                var route = baseline.route();
                checkState(pr.pullRequest().repoFullName().equals(route.baseRepository().fullName())
                                && pr.headRepository().equals(route.headRepository()) && pr.headBranch().equals(route.headBranch())
                                && pr.baseRepository().equals(route.baseRepository()) && pr.baseBranch().equals(route.baseBranch()),
                        "Pull request destination does not match this task");
                checkState(publication.remoteHeadSha().map(pr.headSha()::equals).orElse(pr.state() != GitHubPullRequestIdentity.State.OPEN),
                        "Pull request and remote branch revisions do not agree");
            });
            Optional<PullRequest> pr = matching.map(PullRequest::of);
            return new DevelopmentFactsResponse(Instant.now(), Optional.of(local), Optional.empty(), Optional.of(new Remote(publication.remoteHeadSha(), pr)), Optional.empty());
        }
        catch (RuntimeException failure) {
            return new DevelopmentFactsResponse(Instant.now(), Optional.of(local), Optional.empty(), Optional.empty(), Optional.of(message(failure)));
        }
    }

    private static String message(RuntimeException failure)
    {
        return Optional.ofNullable(failure.getMessage()).filter(value -> !value.isBlank()).orElse("Development facts could not be verified");
    }

    public record Local(String branch, String headSha, int stagedFiles, int unstagedFiles, int untrackedFiles, int uncommittedFileCount, long commitCount) {}

    public record Remote(Optional<String> headSha, Optional<PullRequest> pullRequest) {}

    public record PullRequest(int number, String url, String state, boolean draft, String headSha)
    {
        private static PullRequest of(GitHubPullRequestIdentity pr)
        {
            return new PullRequest(pr.pullRequest().number(), pr.url(), pr.state().name().toLowerCase(Locale.ROOT), pr.draft(), pr.headSha());
        }
    }
}
