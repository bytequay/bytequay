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
package com.bytequay.app.cherrypick;

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.git.command.GitCommandRunner.Result;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient.MergedPullRequest;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService.WorkspaceRemote;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.cherrypick.MergedPullRequestRange;
import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/** Proves a PR's landed range in a configured source remote without changing a checkout or replaying merges. */
@Service
public class MergedPullRequestResolver
{
    private final WorkspaceRepositoryService workspaces;
    private final WorkspaceRemoteSourceService remotes;
    private final CredentialService credentials;
    private final GitHubPullRequestReadClient pullRequests;
    private final GitCommandRunner git;

    public MergedPullRequestResolver(
            WorkspaceRepositoryService workspaces,
            WorkspaceRemoteSourceService remotes,
            CredentialService credentials,
            GitHubPullRequestReadClient pullRequests,
            GitCommandRunner git)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.remotes = requireNonNull(remotes, "remotes is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.git = requireNonNull(git, "git is null");
    }

    public MergedPullRequestRange resolve(String workspaceId, String sourceRemote, PullRequestRef pullRequest)
    {
        requireNonNull(pullRequest, "pullRequest is null");
        WorkspaceRepositoryContext context = workspaces.requireReadyRepository(workspaceId);
        Path repository = context.repository();
        WorkspaceRemote remote = remotes.list(workspaceId).stream().filter(value -> value.name().equals(sourceRemote)).findFirst()
                .orElseThrow(() -> hold("The source remote is not configured"));
        String expectedUrl = "https://github.com/" + pullRequest.repoFullName();
        requireEvidence(remote.url().equalsIgnoreCase(expectedUrl) || remote.url().equalsIgnoreCase(expectedUrl + ".git"),
                "The source remote does not name the pull request's GitHub repository");
        requireEvidence(read(repository, "config", "--get", "remote." + sourceRemote + ".url").strip().equals(remote.url()),
                "The source remote changed from saved workspace routing");
        requireEvidence(read(repository, "rev-parse", "--is-shallow-repository").strip().equals("false"), "The repository is shallow; fetch complete history first");

        MergedPullRequest merged;
        try {
            merged = pullRequests.fetchMergedPullRequest(credentials.resolveGitHubToken(workspaceId), pullRequest);
        }
        catch (IllegalArgumentException | IllegalStateException invalid) {
            throw hold(invalid.getMessage());
        }
        catch (ResponseStatusException unavailable) {
            throw hold(Optional.ofNullable(unavailable.getReason()).orElse("GitHub merge evidence is unavailable"));
        }
        context.sourceRepositoryId(sourceRemote).ifPresent(id -> requireEvidence(id == merged.repositoryId(), "The source repository ID changed"));

        // Private refs keep simultaneous resolutions independent, without sharing FETCH_HEAD or moving workspace branches.
        String prefix = "refs/bytequay/merged-pr/" + UUID.randomUUID();
        try {
            String branch = merged.identity().baseBranch();
            requireEvidence(git.run(repository, "check-ref-format", "refs/heads/" + branch).successful(), "The pull-request base branch is invalid");
            Result fetch = git.run(repository, Duration.ofMinutes(10), credentials.gitAuthenticationEnvironment(workspaceId, ImmutableList.of(remote.url())),
                    ImmutableList.of("fetch", "--quiet", "--no-tags", "--no-write-fetch-head", sourceRemote,
                            "+refs/heads/" + branch + ":" + prefix + "/base",
                            "+refs/pull/" + pullRequest.number() + "/head:" + prefix + "/head"));
            requireEvidence(fetch.successful() && !fetch.truncated(), "Cannot fetch the pull request's base and original head; refresh the source repository");
            requireEvidence(read(repository, "rev-parse", prefix + "/head").strip().equals(merged.identity().headSha()), "The fetched pull-request head changed");
            requireEvidence(local(repository, "merge-base", "--is-ancestor", merged.mergeSha(), prefix + "/base").successful(),
                    "The reported landed commit is missing from the fetched base branch");
            return resolveHistory(repository, merged);
        }
        finally {
            git.run(repository, "update-ref", "-d", prefix + "/base");
            git.run(repository, "update-ref", "-d", prefix + "/head");
        }
    }

    private MergedPullRequestRange resolveHistory(Path repository, MergedPullRequest merged)
    {
        List<String> original = merged.commits();
        List<String> changes = new ArrayList<>();
        String originalBase = "";
        String previous = "";
        for (String sha : original) {
            String parent = singleParent(repository, sha).orElseThrow(() -> hold("The original pull request contains a merge or root commit"));
            if (changes.isEmpty()) {
                originalBase = parent;
            }
            else {
                requireEvidence(parent.equals(previous), "The original pull-request commits are not one complete linear range");
            }
            String change = changes(repository, parent, sha);
            requireEvidence(!change.isEmpty(), "The original pull request contains an empty commit");
            changes.add(change);
            previous = sha;
        }
        if (original.getLast().equals(merged.mergeSha())) {
            return range(merged, originalBase, original);
        }

        String mergeParent = singleParent(repository, merged.mergeSha())
                .orElseThrow(() -> hold("The landed result is a merge or root commit; merge-commit replay is unsupported"));
        String aggregate = changes(repository, originalBase, original.getLast());
        // Prove exact ranges first so an optional replay cannot block an already-verifiable rebase or squash.
        for (boolean replay : ImmutableList.of(false, true)) {
            List<Candidate> candidates = new ArrayList<>();
            if (!aggregate.isEmpty() && matchesChanges(repository, originalBase, original.getLast(), aggregate, mergeParent, merged.mergeSha(), replay)) {
                candidates.add(new Candidate(mergeParent, ImmutableList.of(merged.mergeSha())));
            }

            List<String> reversed = new ArrayList<>();
            String cursor = merged.mergeSha();
            for (int index = original.size() - 1; index >= 0; index--) {
                Optional<String> parent = singleParent(repository, cursor);
                String originalParent = index == 0 ? originalBase : original.get(index - 1);
                if (parent.isEmpty() || !matchesChanges(repository, originalParent, original.get(index), changes.get(index), parent.orElseThrow(), cursor, replay)) {
                    break;
                }
                reversed.add(cursor);
                cursor = parent.orElseThrow();
            }
            if (reversed.size() == original.size()) {
                Candidate candidate = new Candidate(cursor, ImmutableList.copyOf(reversed.reversed()));
                if (!candidates.contains(candidate)) {
                    candidates.add(candidate);
                }
            }
            requireEvidence(candidates.size() <= 1, "More than one landed range matches the pull request");
            if (!candidates.isEmpty()) {
                Candidate selected = candidates.getFirst();
                return range(merged, selected.base(), selected.commits());
            }
        }
        throw hold("The exact landed changes could not be proved in the source repository; its merged result differs from the original PR");
    }

    private static MergedPullRequestRange range(MergedPullRequest merged, String base, List<String> commits)
    {
        return new MergedPullRequestRange(merged.identity().pullRequest(), merged.repositoryId(), merged.identity().baseBranch(),
                merged.mergedAt(), merged.mergeSha(), merged.commits(), base, commits);
    }

    private Optional<String> singleParent(Path repository, String sha)
    {
        String parents = read(repository, "show", "--no-patch", "--format=%P", sha, "--").strip();
        return parents.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})") ? Optional.of(parents) : Optional.empty();
    }

    private String changes(Path repository, String base, String tip)
    {
        // Quoted paths preserve non-UTF-8 filename bytes through the command runner's text decoding.
        return read(repository, "-c", "core.quotePath=true", "diff-tree", "-r", "--raw", "--no-abbrev", "--no-renames", "--no-ext-diff", "--no-textconv",
                "--ignore-submodules=none", "--no-relative", base, tip, "--");
    }

    private boolean matchesChanges(Path repository, String originalBase, String originalTip, String originalChanges, String landedBase, String landedTip, boolean replay)
    {
        String landedChanges = changes(repository, landedBase, landedTip);
        if (originalChanges.equals(landedChanges)) {
            return true;
        }
        if (!replay || landedChanges.isEmpty()) {
            return false;
        }
        Result drivers = local(repository, "config", "--get-regexp", "^merge\\..*\\.driver$");
        requireEvidence(!drivers.truncated() && (drivers.exitCode() == 0 || drivers.exitCode() == 1), "Cannot inspect local Git merge drivers");
        requireEvidence(drivers.exitCode() == 1, "Source verification cannot run configured custom Git merge drivers");
        // Reverse replay excludes PR changes already in the candidate base, which would make a partial range look like a squash.
        return replaysTo(repository, originalBase, originalTip, landedBase, landedTip)
                && replaysTo(repository, originalTip, originalBase, landedTip, landedBase);
    }

    private boolean replaysTo(Path repository, String originalBase, String originalTip, String landedBase, String landedTip)
    {
        Result replay = local(repository, "-c", "merge.renormalize=false", "merge-tree", "--write-tree", "--no-messages",
                "--merge-base=" + originalBase, landedBase, originalTip);
        requireEvidence(!replay.truncated() && (replay.exitCode() == 0 || replay.exitCode() == 1),
                "Local Git could not verify the source merge; Git 2.40 or newer with merge-tree support is required");
        return replay.successful() && replay.output().strip().equals(read(repository, "rev-parse", landedTip + "^{tree}").strip());
    }

    private String read(Path repository, String... arguments)
    {
        Result result = local(repository, arguments);
        requireEvidence(result.successful() && !result.truncated(), "Local Git evidence is missing or exceeds the read limit (" + arguments[0] + ")");
        return result.output();
    }

    private Result local(Path repository, String... arguments)
    {
        return git.runLocalBoundedStdout(repository, Duration.ofSeconds(60), GitCommandRunner.MAXIMUM_OUTPUT_BYTES, ImmutableList.copyOf(arguments));
    }

    private static void requireEvidence(boolean valid, String reason)
    {
        if (!valid) {
            throw hold(reason);
        }
    }

    private static IllegalArgumentException hold(String reason)
    {
        return new IllegalArgumentException(reason + ". Automatic backport is paused; review the source in Commits and select an explicit range.");
    }

    private record Candidate(String base, List<String> commits) {}
}
