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
package com.bytequay.app.workspace;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Performs filesystem and Git operations inside a ByteQuay-owned workspace checkout. */
@Component
final class ManagedWorkspaceGit
        implements WorkspaceBranchPusher
{
    private static final Duration NETWORK_TIMEOUT = Duration.ofMinutes(10);
    private static final String OWNERSHIP_FILE = ".bytequay-workspace";

    private final WorkspacePaths paths;
    private final GitCommandRunner git;
    private final CredentialService credentials;

    ManagedWorkspaceGit(
            WorkspacePaths paths,
            GitCommandRunner git,
            CredentialService credentials)
    {
        this.paths = requireNonNull(paths, "paths is null");
        this.git = requireNonNull(git, "git is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
    }

    void provision(Workspace previous, Workspace current)
    {
        validateRouting(current);
        Path repository = prepareOwnedWorkspace(current);
        Map<String, String> environment = authenticationEnvironment(current);
        if (!Files.exists(repository, LinkOption.NOFOLLOW_LINKS)) {
            cloneRepository(current, repository, environment);
        }
        validateCheckout(current, repository);
        reconcileRemotes(previous, current, repository);
        fetchConfiguredRemotes(current, repository, environment);
    }

    void validate(Workspace previous, Workspace current)
    {
        validateRouting(current);
        Path repository = requireOwnedCheckout(current);
        reconcileRemotes(previous, current, repository);
    }

    void synchronize(Workspace previous, Workspace current)
    {
        validateRouting(current);
        Path repository = requireOwnedCheckout(current);
        Map<String, String> environment = authenticationEnvironment(current);
        reconcileRemotes(previous, current, repository);
        fetchConfiguredRemotes(current, repository, environment);
    }

    /** Pushes one ByteQuay-owned worktree branch to the persisted {@code origin} without force. */
    @Override
    public Push pushBranch(Workspace workspace, Path worktree, String branch)
    {
        requireNonNull(workspace, "workspace is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(branch, "branch is null");
        validateRouting(workspace);
        validateOwnedWorktree(workspace, worktree, branch);
        if (!remoteUrl(worktree, "origin").filter(workspace.originCloneUrl()::equals).isPresent()) {
            throw remoteMismatch("origin remote does not match persisted push routing");
        }
        Result clean = git.run(worktree, "status", "--porcelain");
        if (!clean.successful() || !clean.output().isBlank()) {
            throw new Failure(
                    "The generated branch has uncommitted changes. Finish or cancel the execution before pushing.",
                    "Generated worktree is not clean");
        }
        Result head = git.run(worktree, "rev-parse", "--verify", "HEAD^{commit}");
        requireGitSuccess(head, "inspect generated branch");
        String sha = head.output().strip();
        Result pushed = git.run(
                worktree,
                NETWORK_TIMEOUT,
                authenticationEnvironment(workspace),
                ImmutableList.of("push", "--porcelain", "origin", "HEAD:refs/heads/" + branch));
        requireGitSuccess(pushed, "push generated branch to origin");
        return new Push("origin", branch, sha);
    }

    private void validateOwnedWorktree(Workspace workspace, Path worktree, String branch)
    {
        try {
            Path expectedParent = paths.worktrees(workspace.id()).toRealPath();
            Path actual = worktree.toRealPath();
            if (Files.isSymbolicLink(worktree)
                    || !Files.isDirectory(worktree, LinkOption.NOFOLLOW_LINKS)
                    || !actual.getParent().equals(expectedParent)) {
                throw unknownDirectory();
            }
            Result topLevel = git.run(actual, "rev-parse", "--show-toplevel");
            requireGitSuccess(topLevel, "inspect generated worktree");
            if (!Path.of(topLevel.output().strip()).toRealPath().equals(actual)) {
                throw unknownDirectory();
            }
            Result currentBranch = git.run(actual, "symbolic-ref", "--quiet", "--short", "HEAD");
            if (!currentBranch.successful() || !currentBranch.output().strip().equals(branch)) {
                throw new Failure(
                        "The generated worktree is no longer on its recorded branch. Start another execution.",
                        "Generated worktree branch does not match persisted execution");
            }
        }
        catch (IOException failure) {
            throw new Failure(
                    "The generated worktree could not be verified. Start another execution.",
                    "Validating generated worktree failed: " + failure.getMessage(),
                    failure);
        }
    }

    private Path prepareOwnedWorkspace(Workspace workspace)
    {
        Path root = paths.root();
        Path directory = paths.workspace(workspace.id());
        Path marker = directory.resolve(OWNERSHIP_FILE);
        try {
            Files.createDirectories(root);
            Path realRoot = root.toRealPath();
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(directory);
                Files.writeString(
                        marker,
                        ownership(workspace),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
            }
            if (Files.isSymbolicLink(directory)
                    || !directory.toRealPath().getParent().equals(realRoot)
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || !Files.readString(marker, StandardCharsets.UTF_8).equals(ownership(workspace))) {
                throw unknownDirectory();
            }
            return paths.repository(workspace);
        }
        catch (IOException failure) {
            throw new Failure(
                    "Managed workspace directory could not be prepared. Check permissions and retry.",
                    "Preparing workspace directory failed: " + failure.getMessage(),
                    failure);
        }
    }

    private Path requireOwnedCheckout(Workspace workspace)
    {
        Path repository = prepareOwnedWorkspace(workspace);
        if (!Files.exists(repository, LinkOption.NOFOLLOW_LINKS)) {
            throw new Failure(
                    "Managed checkout is missing. Retry the workspace to restore it.",
                    "Managed checkout is missing");
        }
        validateCheckout(workspace, repository);
        return repository;
    }

    private void cloneRepository(
            Workspace workspace,
            Path repository,
            Map<String, String> environment)
    {
        Result result = git.run(
                repository.getParent(),
                NETWORK_TIMEOUT,
                environment,
                ImmutableList.of(
                        "clone",
                        "--origin",
                        "origin",
                        workspace.originCloneUrl(),
                        repository.getFileName().toString()));
        requireGitSuccess(result, "clone");
        requireGitSuccess(
                git.run(repository, "remote", "set-url", "origin", workspace.originCloneUrl()),
                "record origin routing");
    }

    private void validateCheckout(Workspace workspace, Path repository)
    {
        try {
            if (Files.isSymbolicLink(repository)
                    || !Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)
                    || !repository.toRealPath().getParent().equals(paths.workspace(workspace.id()).toRealPath())) {
                throw unknownDirectory();
            }
            Result topLevel = git.run(repository, "rev-parse", "--show-toplevel");
            requireGitSuccess(topLevel, "inspect checkout");
            if (!Path.of(topLevel.output().strip()).toRealPath().equals(repository.toRealPath())) {
                throw unknownDirectory();
            }
        }
        catch (IOException failure) {
            throw new Failure(
                    "Managed checkout identity could not be verified. Move the unexpected directory and retry.",
                    "Validating checkout path failed: " + failure.getMessage(),
                    failure);
        }
    }

    private void reconcileRemotes(Workspace previous, Workspace current, Path repository)
    {
        reconcileRemote(
                repository,
                "origin",
                previous.originCloneUrl(),
                current.originCloneUrl(),
                false);
        Optional<String> actualUpstream = remoteUrl(repository, "upstream");
        if (current.topology() == Topology.DIRECT) {
            if (actualUpstream.isPresent()) {
                throw remoteMismatch("Direct checkout unexpectedly has an upstream remote");
            }
            return;
        }
        reconcileRemote(
                repository,
                "upstream",
                previous.upstreamCloneUrl().orElseThrow(),
                current.upstreamCloneUrl().orElseThrow(),
                true);
    }

    private void reconcileRemote(
            Path repository,
            String name,
            String previousUrl,
            String currentUrl,
            boolean mayAdd)
    {
        Optional<String> actual = remoteUrl(repository, name);
        if (actual.isEmpty()) {
            if (!mayAdd) {
                throw remoteMismatch("Missing " + name + " remote");
            }
            requireGitSuccess(git.run(repository, "remote", "add", name, currentUrl), "add " + name);
            return;
        }
        if (actual.orElseThrow().equals(currentUrl)) {
            return;
        }
        if (!actual.orElseThrow().equals(previousUrl)) {
            throw remoteMismatch(name + " remote URL does not match persisted routing");
        }
        requireGitSuccess(git.run(repository, "remote", "set-url", name, currentUrl), "update " + name);
    }

    private Optional<String> remoteUrl(Path repository, String name)
    {
        Result result = git.run(repository, "config", "--get-all", "remote." + name + ".url");
        if (!result.successful()) {
            return Optional.empty();
        }
        List<String> urls = result.output().lines()
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(toImmutableList());
        Result pushUrls = git.run(repository, "config", "--get-all", "remote." + name + ".pushurl");
        if (urls.size() != 1 || pushUrls.successful()) {
            throw remoteMismatch(name + " remote has ambiguous fetch or push routing");
        }
        return Optional.of(urls.getFirst());
    }

    private void fetchConfiguredRemotes(
            Workspace workspace,
            Path repository,
            Map<String, String> environment)
    {
        fetch(repository, "origin", environment);
        if (workspace.topology() == Topology.FORK) {
            fetch(repository, "upstream", environment);
        }
    }

    private void fetch(Path repository, String remote, Map<String, String> environment)
    {
        Result result = git.run(
                repository,
                NETWORK_TIMEOUT,
                environment,
                ImmutableList.of("fetch", "--prune", remote));
        requireGitSuccess(result, "fetch " + remote);
    }

    private Map<String, String> authenticationEnvironment(Workspace workspace)
    {
        Set<String> bases = new LinkedHashSet<>();
        bases.add(authenticationBase(workspace.originCloneUrl()));
        workspace.upstreamCloneUrl()
                .map(ManagedWorkspaceGit::authenticationBase)
                .ifPresent(bases::add);
        String token = credentials.resolveGitHubToken();
        String header = "Authorization: Basic " + Base64.getEncoder().encodeToString(
                ("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
        ImmutableMap.Builder<String, String> environment = ImmutableMap.builder();
        environment.put("GIT_CONFIG_COUNT", Integer.toString(bases.size()));
        int index = 0;
        for (String base : bases) {
            environment.put("GIT_CONFIG_KEY_" + index, "http." + base + ".extraHeader");
            environment.put("GIT_CONFIG_VALUE_" + index, header);
            index++;
        }
        return environment.buildOrThrow();
    }

    private static void validateRouting(Workspace workspace)
    {
        cloneUri(workspace.originCloneUrl());
        workspace.upstreamCloneUrl().ifPresent(ManagedWorkspaceGit::cloneUri);
    }

    private static String ownership(Workspace workspace)
    {
        return workspace.id() + "\n" + workspace.selectedRepositoryId() + "\n";
    }

    private static String authenticationBase(String cloneUrl)
    {
        URI uri = cloneUri(cloneUrl);
        return uri.getScheme().toLowerCase() + "://" + uri.getAuthority().toLowerCase() + "/";
    }

    private static URI cloneUri(String cloneUrl)
    {
        try {
            URI uri = URI.create(requireNonNull(cloneUrl, "cloneUrl is null"));
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("unsafe clone URL");
            }
            return uri;
        }
        catch (IllegalArgumentException failure) {
            throw new Failure(
                    "Repository clone routing is unsafe. Remove and watch the repository again.",
                    "Unsafe clone URL",
                    failure);
        }
    }

    private static void requireGitSuccess(Result result, String action)
    {
        if (!result.successful()) {
            throw new Failure(
                    "Git failed while attempting to " + action + ". Check access and retry.",
                    "Git " + action + " failed: " + result.output().strip());
        }
    }

    private static Failure unknownDirectory()
    {
        return new Failure(
                "Managed checkout identity could not be verified. Move the unexpected directory and retry.",
                "Managed workspace directory is unknown or mismatched");
    }

    private static Failure remoteMismatch(String internalMessage)
    {
        return new Failure(
                "Managed checkout remotes do not match this workspace. Move the checkout and retry.",
                internalMessage);
    }

    static final class Failure
            extends RuntimeException
    {
        private final String safeMessage;

        Failure(String safeMessage, String internalMessage)
        {
            super(internalMessage);
            this.safeMessage = safeMessage;
        }

        Failure(String safeMessage, String internalMessage, Throwable cause)
        {
            super(internalMessage, cause);
            this.safeMessage = safeMessage;
        }

        String safeMessage()
        {
            return safeMessage;
        }
    }
}
