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

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.git.command.GitCommandRunner.Result;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static java.util.Objects.requireNonNull;

@Service
public class PullRequestConflictService
{
    private final WorkspaceStore workspaces;
    private final WorkspaceRepositoryService repositories;
    private final GitHubPullRequestReadClient github;
    private final CredentialService credentials;
    private final GitCommandRunner git;

    public PullRequestConflictService(WorkspaceStore workspaces, WorkspaceRepositoryService repositories,
            GitHubPullRequestReadClient github, CredentialService credentials, GitCommandRunner git)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.github = requireNonNull(github, "github is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.git = requireNonNull(git, "git is null");
    }

    public PullRequestConflicts read(String repo, int number)
    {
        var pullRequest = parseRef(repo, number);
        RepoRef requested = RepoRef.of(pullRequest.owner(), pullRequest.repo());
        var workspace = workspaces.findAll().stream()
                .filter(value -> value.monitoredRepository().fullName().equalsIgnoreCase(requested.fullName())
                        || value.selectedRepository().fullName().equalsIgnoreCase(requested.fullName()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Watch repository to list conflicted files"));
        return WorkspaceOperationLocks.withLock(workspace.id(), () -> {
            var local = repositories.requireLocalRepository(workspace.id());
            RepoRef canonical = workspace.monitoredRepository().fullName().equalsIgnoreCase(requested.fullName())
                    ? workspace.monitoredRepository() : workspace.selectedRepository();
            GitHubPullRequestIdentity identity = github.fetchPullRequestIdentity(credentials.resolveGitHubToken(workspace.id()), parseRef(canonical.fullName(), number));
            if (identity.state() != GitHubPullRequestIdentity.State.OPEN) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This pull request is no longer open. Refresh the pull request.");
            }
            if (!identity.baseRepository().fullName().equalsIgnoreCase(requested.fullName()) || !sha(identity.baseSha()) || !sha(identity.headSha())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub returned an invalid pull-request comparison.");
            }
            Result shallow = git.run(local.repository(), "rev-parse", "--is-shallow-repository");
            if (!shallow.successful() || shallow.truncated() || !shallow.output().strip().equals("false")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Complete repository history is required to list conflicted files.");
            }
            String url = "https://github.com/" + canonical.fullName() + ".git";
            // Fetch exact remote commits without moving local branches, tracking refs, or FETCH_HEAD.
            Result fetched = git.run(local.repository(), Duration.ofMinutes(2), credentials.gitAuthenticationEnvironment(workspace.id(), ImmutableList.of(url)),
                    ImmutableList.of("fetch", "--no-tags", "--no-recurse-submodules", "--no-write-fetch-head", "--no-auto-maintenance", "--", url,
                            identity.baseSha(), identity.headSha()));
            if (!fetched.successful() || fetched.truncated()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not fetch the PR and target branch. Check repository access and retry.");
            }
            Result merged = git.runStdout(local.repository(), 1, "--no-replace-objects", "merge-tree", "--write-tree", "--name-only", "-z", "--no-messages",
                    identity.baseSha(), identity.headSha());
            return new PullRequestConflicts(identity.baseBranch(), conflictPaths(merged));
        });
    }

    static List<String> conflictPaths(Result result)
    {
        String[] fields = result.output().split("\u0000", -1);
        if (result.truncated() || (result.exitCode() != 0 && result.exitCode() != 1) || fields.length < 2 || !sha(fields[0])
                || !fields[fields.length - 1].isEmpty()
                || (result.exitCode() == 0 && fields.length != 2)
                || (result.exitCode() == 1 && fields.length < 3)
                || Arrays.stream(fields, 1, fields.length - 1).anyMatch(String::isEmpty)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not determine conflicted files. Check that Git supports merge-tree --write-tree and retry.");
        }
        return ImmutableList.copyOf(Arrays.asList(fields).subList(1, fields.length - 1));
    }

    private static boolean sha(String value)
    {
        return value.matches("[0-9a-f]{40}|[0-9a-f]{64}");
    }
}
