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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.codegraph.CodeGraphFirstRuntime;
import com.bytequay.app.service.codegraph.CodeGraphUpdateCoordinator;
import com.bytequay.app.service.threads.WorktreeService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

/** ByteQuay-gated wrapper for CodeGraph's primary semantic search tool. */
@Component
public class CodeGraphToolHandlers
{
    private final CodeGraphUpdateCoordinator codeGraph;
    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final WorkspaceService workspaces;
    private final WatchedRepoStore watchedRepos;
    private final WorktreeService worktreeService;

    public CodeGraphToolHandlers(
            CodeGraphUpdateCoordinator codeGraph,
            TaskStore taskStore,
            ThreadStore threadStore,
            WorkspaceService workspaces,
            WatchedRepoStore watchedRepos,
            WorktreeService worktreeService)
    {
        this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
    }

    public record CodeGraphExploreArgs(
            @ToolParam(description = "Natural-language CodeGraph query. Ask about a symbol, "
                    + "file, flow, call path, or area of the current checkout.",
                    required = true) String query,
            @ToolParam(description = "Optional repository in owner/name form. Required when a "
                    + "trunk workspace has more than one managed local repo.") String repoFullName) {}

    @AgentTool(
            name = "codegraph_explore",
            description = "Use this before broad rg/grep/find/Glob discovery. CodeGraph's indexed "
                    + "semantic graph for the current checkout returns relevant source, call paths, "
                    + "tests, and impact context in one call. Afterward use native search for exact "
                    + "literal checks or completeness verification. "
                    + "ByteQuay first makes sure the checkout's CodeGraph index is fresh; "
                    + "if it cannot, the tool fails instead of returning stale context.",
            security = SecurityType.CODE_READ,
            gating = Gating.AUTO,
            roles = {AgentRole.TRUNK, AgentRole.TASK, AgentRole.REVIEWER})
    public ToolOutcome codegraphExplore(CodeGraphExploreArgs args, ToolCall call)
    {
        if (args.query() == null || args.query().isBlank()) {
            return ToolOutcome.Completed.error("query is required");
        }
        // An attempted graph call unlocks native search for this CLI turn,
        // including when checkout resolution or CodeGraph itself fails. The
        // policy is a preference and must retain a reliable fallback path.
        CodeGraphFirstRuntime.markAttempted(
                call.threadId(), PermissionResolver.agentKeyFor(call.taskId(), call.stageId()));
        CheckoutChoice checkout = checkoutFor(call, args.repoFullName());
        if (checkout.error() != null) {
            return ToolOutcome.Completed.error(checkout.error());
        }
        try {
            return ToolOutcome.Completed.ok(codeGraph.explore(checkout.path(), args.query()));
        }
        catch (RuntimeException e) {
            return ToolOutcome.Completed.error("CodeGraph unavailable: " + e.getMessage());
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            return ToolOutcome.Completed.error("CodeGraph interrupted");
        }
        catch (Exception e) {
            return ToolOutcome.Completed.error("CodeGraph failed: " + e.getMessage());
        }
    }

    private CheckoutChoice checkoutFor(ToolCall call, String repoFullName)
    {
        if (call.taskId() != null && !call.taskId().isBlank()) {
            return taskStore.findTaskById(call.taskId())
                    .flatMap(CodeGraphToolHandlers::taskCheckout)
                    .map(CheckoutChoice::found)
                    .orElseGet(() -> CheckoutChoice.error(
                            "no usable local checkout is bound to task " + call.taskId()));
        }
        Optional<Thread> thread = threadStore.findThreadById(call.threadId());
        if (thread.isEmpty()) {
            return CheckoutChoice.error("thread not found: " + call.threadId());
        }
        CheckoutChoice root = resolveTrunkCloneRoot(thread.get().workspaceId(), repoFullName);
        if (root.error() != null) {
            return root;
        }
        Path repoRoot = root.path().toAbsolutePath().normalize();
        Optional<ThreadStore.PlanningSnapshot> active =
                threadStore.findPlanningSnapshot(call.threadId())
                        .filter(snapshot -> repoRoot.toString().equals(snapshot.repoRoot()));
        Optional<WorktreeService.PlanningSync> ready = active.isPresent()
                ? worktreeService.ensurePlanningWorktree(
                        repoRoot, call.threadId(), active.get().baseSha())
                : worktreeService.refreshPlanningWorktree(repoRoot, call.threadId());
        ready.filter(ignored -> active.isEmpty())
                .ifPresent(sync -> threadStore.setPlanningSnapshot(
                        call.threadId(), new ThreadStore.PlanningSnapshot(
                                repoRoot.toString(), sync.baseSha())));
        return ready.map(sync -> CheckoutChoice.found(sync.worktree()))
                .orElseGet(() -> CheckoutChoice.error(
                        "planning snapshot unavailable; refusing to index the user's main checkout"));
    }

    private CheckoutChoice resolveTrunkCloneRoot(String workspaceId, String repoFullName)
    {
        if (repoFullName != null && !repoFullName.isBlank()) {
            return watchedRepos.findAll().stream()
                    .filter(wr -> wr.fullName().equalsIgnoreCase(repoFullName.trim()))
                    .flatMap(wr -> cloneCandidate(wr).stream())
                    .findFirst()
                    .map(candidate -> CheckoutChoice.found(candidate.path()))
                    .orElseGet(() -> CheckoutChoice.error(
                            "repoFullName is not a managed repo with a local clone: " + repoFullName.trim()));
        }
        List<String> pinned = workspaceId == null || workspaceId.isBlank()
                ? List.of()
                : workspaces.listRepos(workspaceId).stream()
                        .map(WorkspaceRepo::repoFullName)
                        .toList();
        List<CloneCandidate> candidates = cloneCandidates(pinned);
        if (candidates.isEmpty()) {
            return CheckoutChoice.error(
                    "no local checkout is bound to this turn; add or select a managed repo first");
        }
        if (candidates.size() > 1) {
            return CheckoutChoice.error("multiple local repos are available ("
                    + candidateNames(candidates) + "); pass repoFullName");
        }
        return CheckoutChoice.found(candidates.get(0).path());
    }

    private List<CloneCandidate> cloneCandidates(List<String> pinned)
    {
        List<WatchedRepo> watched = watchedRepos.findAll();
        List<CloneCandidate> out = new ArrayList<>();
        if (!pinned.isEmpty()) {
            for (String fullName : pinned) {
                watched.stream()
                        .filter(wr -> wr.fullName().equals(fullName))
                        .flatMap(wr -> cloneCandidate(wr).stream())
                        .findFirst()
                        .ifPresent(out::add);
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        return watched.stream()
                .flatMap(wr -> cloneCandidate(wr).stream())
                .toList();
    }

    private static Optional<Path> taskCheckout(Task task)
    {
        return gitCheckout(task.worktreePath())
                .or(() -> gitCheckout(task.workingDir()));
    }

    private static Optional<CloneCandidate> cloneCandidate(WatchedRepo watched)
    {
        return gitCheckout(watched.localClonePath())
                .map(path -> new CloneCandidate(watched.fullName(), path));
    }

    private static Optional<Path> gitCheckout(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isDirectory(path) || !Files.exists(path.resolve(".git"))) {
            return Optional.empty();
        }
        return Optional.of(path);
    }

    private static String candidateNames(List<CloneCandidate> candidates)
    {
        return candidates.stream()
                .map(CloneCandidate::fullName)
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private record CloneCandidate(String fullName, Path path) {}

    private record CheckoutChoice(Path path, String error)
    {
        private static CheckoutChoice found(Path path)
        {
            return new CheckoutChoice(path, null);
        }

        private static CheckoutChoice error(String message)
        {
            return new CheckoutChoice(null, message);
        }
    }
}
