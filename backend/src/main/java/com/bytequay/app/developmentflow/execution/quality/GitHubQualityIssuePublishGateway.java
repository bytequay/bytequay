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
package com.bytequay.app.developmentflow.execution.quality;

import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Gateway;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Operation;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoIssuePage;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** GitHub adapter with marker-based recovery for create-issue ambiguity. */
@Component
public final class GitHubQualityIssuePublishGateway
        implements Gateway
{
    private final PullRequestRepository github;
    private final PatResolver pats;

    public GitHubQualityIssuePublishGateway(
            PullRequestRepository github, PatResolver pats)
    {
        this.github = requireNonNull(github, "github is null");
        this.pats = requireNonNull(pats, "pats is null");
    }

    @Override
    public Optional<RepoIssue> findExisting(Operation operation)
    {
        RepoRef repo = repo(operation);
        String pat = pats.resolve(operation.repoOwner() + "/" + operation.repoName());
        List<RepoIssue> matches = new ArrayList<>();
        for (int pageNumber = 1; ; pageNumber++) {
            RepoIssuePage page = github.fetchRepoIssuePage(
                    pat, repo, pageNumber, 100);
            page.issues().stream()
                    .filter(issue -> hasMarker(
                            pat, repo, issue.number(), operation.marker()))
                    .forEach(matches::add);
            if (!page.hasMore()) {
                break;
            }
        }
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Multiple GitHub issues carry quality operation "
                            + operation.operationId());
        }
        return matches.stream().findFirst();
    }

    @Override
    public RepoIssue create(Operation operation)
    {
        String pat = pats.resolve(operation.repoOwner() + "/" + operation.repoName());
        return github.createIssue(
                pat, repo(operation), operation.title(), operation.body());
    }

    private boolean hasMarker(
            String pat, RepoRef repo, int issueNumber, String marker)
    {
        IssueDetail detail = github.fetchIssueDetail(pat, repo, issueNumber);
        return detail.body() != null && detail.body().contains(marker);
    }

    private static RepoRef repo(Operation operation)
    {
        return new RepoRef(operation.repoOwner(), operation.repoName());
    }
}
