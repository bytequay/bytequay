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
package com.bytequay.app.developmentflow.baseline;

import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.ListPullRequestsQuery;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.local.GitRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/** Deterministic, manually driven fakes shared by lifecycle acceptance tests. */
final class DeterministicDevelopmentFlowFakes
{
    private DeterministicDevelopmentFlowFakes() {}

    /** Scripted operation completion with no worker or timer. */
    static final class AgentExecutor
    {
        private final Map<String, AgentResult> scripted = new LinkedHashMap<>();
        private final List<String> executions = new ArrayList<>();

        AgentExecutor script(String operationId, String subjectId, String output)
        {
            AgentResult previous = scripted.putIfAbsent(
                    operationId, new AgentResult(operationId, subjectId, output));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "agent operation already scripted: " + operationId);
            }
            return this;
        }

        AgentResult execute(String operationId)
        {
            AgentResult result = scripted.get(operationId);
            if (result == null) {
                throw new IllegalArgumentException(
                        "agent operation is not scripted: " + operationId);
            }
            executions.add(operationId);
            return result;
        }

        List<String> executions()
        {
            return List.copyOf(executions);
        }
    }

    record AgentResult(String operationId, String subjectId, String output) {}

    /** Repeated indexes intentionally model duplicate and out-of-order delivery. */
    static <T> void deliver(
            List<T> results, Consumer<T> receiver, int... deliveryOrder)
    {
        for (int index : deliveryOrder) {
            receiver.accept(results.get(index));
        }
    }

    /**
     * Real {@link GitRunner} boundary with a scripted local head. An ambiguous
     * push records the remote head before throwing, matching a lost response.
     */
    static final class GitAdapter
            extends GitRunner
    {
        private final Map<Path, String> localHeads = new LinkedHashMap<>();
        private final Map<Path, String> remoteHeads = new LinkedHashMap<>();
        private final Set<Path> ambiguousPushes = new LinkedHashSet<>();
        private int pushCalls;

        GitAdapter head(Path worktree, String sha)
        {
            localHeads.put(key(worktree), sha);
            return this;
        }

        GitAdapter ambiguousNextPush(Path worktree)
        {
            ambiguousPushes.add(key(worktree));
            return this;
        }

        int pushCalls()
        {
            return pushCalls;
        }

        @Override
        public String headSha(Path workingDir)
                throws IOException
        {
            String sha = localHeads.get(key(workingDir));
            if (sha == null) {
                throw new IOException("no scripted local head for " + workingDir);
            }
            return sha;
        }

        @Override
        public String statusPorcelainZ(Path workingDir)
        {
            return "";
        }

        @Override
        public void push(Path workingDir)
                throws IOException
        {
            Path key = key(workingDir);
            String sha = headSha(workingDir);
            pushCalls++;
            remoteHeads.put(key, sha);
            if (ambiguousPushes.remove(key)) {
                throw new AmbiguousGitSuccessException(
                        "push reached the remote but its response was lost");
            }
        }

        @Override
        public Optional<String> remoteHeadSha(
                Path workingDir, String remote, String branch)
        {
            return Optional.ofNullable(remoteHeads.get(key(workingDir)));
        }

        private static Path key(Path path)
        {
            return path.toAbsolutePath().normalize();
        }
    }

    static final class AmbiguousGitSuccessException
            extends IOException
    {
        AmbiguousGitSuccessException(String message)
        {
            super(message);
        }
    }

    /**
     * Real GitHub repository boundary. An ambiguous create persists the PR
     * before throwing so the caller can recover by listing the exact head.
     */
    static final class GitHubAdapter
            implements PullRequestRepository
    {
        private static final Instant CREATED_AT =
                Instant.parse("2026-07-28T09:00:00Z");

        private final Map<PullRequestKey, PullRequest> pullRequests =
                new LinkedHashMap<>();
        private boolean ambiguousNextCreate;
        private int createCalls;
        private int nextNumber = 100;

        GitHubAdapter ambiguousNextCreate()
        {
            ambiguousNextCreate = true;
            return this;
        }

        int createCalls()
        {
            return createCalls;
        }

        @Override
        public PullRequest createPullRequest(
                String pat, RepoRef repo, CreatePullRequestCommand command)
        {
            createCalls++;
            PullRequestKey key = new PullRequestKey(
                    repo.fullName(), command.head(), command.base());
            PullRequest result = pullRequests.computeIfAbsent(
                    key, ignored -> createdPullRequest(repo, command, nextNumber++));
            if (ambiguousNextCreate) {
                ambiguousNextCreate = false;
                throw new AmbiguousGitHubSuccessException(
                        "pull request was created but its response was lost");
            }
            return result;
        }

        @Override
        public List<PullRequest> listPullRequests(
                String pat, RepoRef repo, ListPullRequestsQuery query)
        {
            return pullRequests.entrySet().stream()
                    .filter(entry -> entry.getKey().repo().equals(repo.fullName()))
                    .filter(entry -> query.head().isEmpty()
                            || query.head().orElseThrow().equals(entry.getKey().head()))
                    .filter(entry -> query.base().isEmpty()
                            || query.base().orElseThrow().equals(entry.getKey().base()))
                    .map(Map.Entry::getValue)
                    .filter(pr -> "all".equals(query.state())
                            || pr.state().equals(query.state()))
                    .toList();
        }

        @Override
        public PullRequest getPullRequest(String pat, PullRequestRef ref)
        {
            return pullRequests.values().stream()
                    .filter(pr -> pr.repo().equals(ref.repoFullName()))
                    .filter(pr -> pr.number() == ref.number())
                    .findFirst()
                    .orElseThrow();
        }

        private static PullRequest createdPullRequest(
                RepoRef repo, CreatePullRequestCommand command, int number)
        {
            String head = command.head();
            int separator = head.indexOf(':');
            String author = separator > 0 ? head.substring(0, separator) : repo.owner();
            String headRef = separator > 0 ? head.substring(separator + 1) : head;
            return new PullRequest(
                    number,
                    repo.fullName(),
                    number,
                    command.title(),
                    author,
                    "https://github.test/" + repo.fullName() + "/pull/" + number,
                    CREATED_AT,
                    CREATED_AT,
                    PullRequest.Origin.AUTHORED,
                    List.of(),
                    Map.of(),
                    command.draft().orElse(false),
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    0,
                    0,
                    0,
                    null,
                    "open",
                    null,
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    null,
                    null,
                    headRef);
        }

        private record PullRequestKey(String repo, String head, String base) {}
    }

    static final class AmbiguousGitHubSuccessException
            extends RuntimeException
    {
        AmbiguousGitHubSuccessException(String message)
        {
            super(message);
        }
    }
}
