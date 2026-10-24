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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.agent.runtime.AgentProperties;
import com.bytequay.app.git.ReviewExperiments;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.review.ReviewCodeService.ChangeScope;
import com.bytequay.app.review.ReviewCodeService.Changes;
import com.bytequay.app.review.ReviewCodeService.CodeFile;
import com.bytequay.app.review.ReviewCodeService.CodeFileEntry;
import com.bytequay.app.review.ReviewCodeService.CodeFiles;
import com.bytequay.app.review.ReviewCodeService.CodeSource;
import com.bytequay.app.review.ReviewCodeService.LineChange;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.ClaudeAccount;
import com.bytequay.app.service.settings.ClaudeAccounts;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.bytequay.app.git.GitTestSupport.configureUser;
import static com.bytequay.app.git.GitTestSupport.git;
import static com.bytequay.app.git.GitTestSupport.write;
import static com.bytequay.app.review.ReviewTestData.pullRequestReview;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.CONCURRENT)
class TestReviewCodeService
{
    private static final String WORKSPACE_ID = "review-code";
    private static final String REVIEW_ID = "review-1";
    private static final String ORIGIN = "https://github.com/owner/repo.git";
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repo");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final String TEST_PATH = "src/test/java/TestCode.java";
    private static final String MAIN_PATH = "src/main/java/Code.java";
    private static final String TEST_PATCH = "@@ -1 +1 @@\n-old test\n+test source";
    private static final String ADDED_PATH = "src/test/java/TestAdded.java";
    private static final String OUTLINE_PATH = "src/test/java/demo/TestOutline.java";
    // The new side: lines 3-7 hold kept() with one changed line; lines 8-12 are added and hold added().
    private static final String OUTLINE_PATCH = """
            @@ -1,8 +1,13 @@
             class TestOutline
             {
                 @Test
                 void kept()
                 {
            -        old();
            +        assertThat(1).isOne();
                 }
            +
            +    @Test
            +    void added()
            +    {
            +    }
             }""";

    @TempDir
    private Path directory;

    private final AgentReviewStore store = mock(AgentReviewStore.class);
    private final AgentReviewService reviews = mock(AgentReviewService.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final PullRequestTaskStore assignments = mock(PullRequestTaskStore.class);
    private final PullRequestStore pullRequestRows = mock(PullRequestStore.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private AgentProperties agentProperties;
    private final ClaudeAccounts claudeAccounts = mock(ClaudeAccounts.class);
    private Path repository;
    private Path checkout;
    private TaskWorktree worktree;
    private ManagedWorktreeService worktrees;
    private ReviewCodeService service;
    private String head;

    @BeforeEach
    void setUp()
            throws IOException
    {
        agentProperties = new AgentProperties(directory.resolve("runtime"), Duration.ofHours(1), "test-session", "codex", "0.159.3",
                directory.resolve("codex-home"), "claude", directory.resolve("claude-home"));
        WorkspacePaths paths = new WorkspacePaths(directory.resolve("managed").toString());
        repository = paths.repository(WORKSPACE_ID);
        Files.createDirectories(repository);
        Files.writeString(paths.workspace(WORKSPACE_ID).resolve(".bytequay-workspace"), WORKSPACE_ID + "\n1\n");
        git(repository, "init", "--initial-branch=main");
        configureUser(repository, "ByteQuay Test", "test@example.invalid");
        git(repository, "config", "core.hooksPath", "/dev/null");
        git(repository, "remote", "add", "origin", ORIGIN);
        write(repository, MAIN_PATH, "production source\n");
        write(repository, TEST_PATH, "test source\n");
        write(repository, ADDED_PATH, "added test\n");
        write(repository, "README.md", "readme\n");
        write(repository, "Old.java", "renamed source\n");
        write(repository, "Gone.java", "deleted source\n");
        write(repository, ".gitignore", "build/\n");
        git(repository, "add", ".");
        git(repository, "commit", "-m", "Reviewed revision");
        head = git(repository, "rev-parse", "HEAD");

        Workspace workspace = new Workspace(WORKSPACE_ID, "Review code", 1, REPOSITORY, Optional.empty(), Optional.empty(),
                1, REPOSITORY, Workspace.Topology.DIRECT, "main", ORIGIN, Optional.empty(), Optional.empty(), repository.toString(),
                Optional.of(NOW), Optional.of(NOW), 0, NOW, NOW);
        WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
        when(workspaceStore.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        GitCommandRunner runner = new GitCommandRunner();
        worktrees = new ManagedWorktreeService(paths, workspaceStore, runner, mock(IdeRuntimeService.class));
        TaskId taskId = TaskId.random();
        worktree = worktrees.provision(new ProvisionWorktreeCommand(taskId, WORKSPACE_ID, RepositoryIdentity.valueOf(REPOSITORY.fullName()), head, "bytequay/task/" + taskId));
        checkout = worktree.canonicalWorktree();

        when(store.findReview(REVIEW_ID)).thenReturn(Optional.of(review(AgentReview.State.OPEN)));
        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run(head)));
        when(reviews.task(REVIEW_ID)).thenReturn(openTask());
        when(workspaces.requireReadyRepository(WORKSPACE_ID)).thenReturn(new WorkspaceRepositoryContext(
                WORKSPACE_ID, repository, 1, REPOSITORY, OptionalLong.empty(), REPOSITORY, "origin", "origin", "main", ImmutableList.of()));
        service = new ReviewCodeService(store, reviews, worktrees, workspaces, runner, new ReviewExperiments(runner, worktrees), tasks, turns,
                assignments, pullRequestRows, new WorktreeDiffReader(runner), pullRequests, agentProperties, claudeAccounts);
        when(reviews.mutateWorkingCopy(eq(REVIEW_ID), any())).thenAnswer(invocation -> {
            Function<AgentTask, ?> mutation = invocation.getArgument(1);
            return mutation.apply(reviews.task(REVIEW_ID));
        });

        when(tasks.get(taskId)).thenReturn(developmentTask());
        when(assignments.findByTaskId(taskId.toString())).thenReturn(Optional.of(new PullRequestTask(taskId.toString(), WORKSPACE_ID, REPOSITORY.fullName(), 7, "Imported PR")));
        when(pullRequests.getPullRequestDiffFiles(REPOSITORY.fullName(), 7, null, null)).thenReturn(ImmutableList.of(
                new DiffFile(MAIN_PATH, "modified", 1, 1, "@@ -1 +1 @@\n-old\n+production source"),
                new DiffFile(ADDED_PATH, "added", 1, 0, "@@ -0,0 +1 @@\n+added test")));
        when(pullRequests.findPullRequestDetail(REPOSITORY.fullName(), 7)).thenReturn(Optional.of(pullRequestDetail("main")));
        doAnswer(invocation -> {
            Consumer<AgentTask> action = invocation.getArgument(2);
            action.accept(tasks.get(taskId));
            return null;
        }).when(turns).synchronizeWorktree(eq(taskId), anyLong(), any());
    }

    @Test
    void historyReadsOldCommitsRenamesDeletionAndMergeParentsWithoutChangingGit()
    {
        String initial = head;
        CodeHistory.Comparison root = service.historyCommit(taskHost(), initial, Optional.empty());
        assertThat(root.parent()).isEmpty();
        assertThat(root.files()).extracting(CodeHistory.Change::status).containsOnly("A");
        assertThat(service.snapshot(taskHost(), initial, MAIN_PATH).content()).contains("production source\n");
        assertThat(service.snapshot(taskHost(), initial, MAIN_PATH).editable()).isFalse();

        git(checkout, "mv", "Old.java", "renamed 測試.java");
        git(checkout, "commit", "-m", "Rename source");
        String renamed = git(checkout, "rev-parse", "HEAD");
        CodeHistory.Change rename = service.historyCommit(service.reviewHost(REVIEW_ID), renamed, Optional.empty()).files().getFirst();
        assertThat(rename.path()).isEqualTo("renamed 測試.java");
        assertThat(rename.originalPath()).contains("Old.java");
        assertThat(service.history(taskHost(), Optional.empty(), Optional.of(rename.path()), 0).commits())
                .extracting(CodeHistory.Commit::sha).containsExactly(renamed, initial);
        git(checkout, "rm", "renamed 測試.java");
        git(checkout, "commit", "-m", "Delete source");
        String deleted = git(checkout, "rev-parse", "HEAD");
        assertThat(service.historyCommit(taskHost(), deleted, Optional.empty()).files().getFirst().status()).isEqualTo("D");
        assertThat(service.snapshot(taskHost(), renamed, rename.path()).content()).contains("renamed source\n");

        git(checkout, "checkout", "-b", "other-parent", initial);
        write(checkout, "branch.go", "package main\n");
        git(checkout, "add", "branch.go");
        git(checkout, "commit", "-m", "Other parent");
        String other = git(checkout, "rev-parse", "HEAD");
        git(checkout, "checkout", "--detach", deleted);
        git(checkout, "merge", "--no-ff", "other-parent", "-m", "Merge source");
        String merge = git(checkout, "rev-parse", "HEAD");
        write(checkout, MAIN_PATH, "Uncommitted local edits\n");
        String status = git(checkout, "status", "--porcelain=v1");
        CodeHistory.Comparison first = service.historyCommit(taskHost(), merge, Optional.empty());
        assertThat(first.commit().parents()).containsExactly(deleted, other);
        assertThat(first.files()).extracting(CodeHistory.Change::path).containsExactly("branch.go");
        CodeHistory.Comparison second = service.historyCommit(taskHost(), merge, Optional.of(other));
        assertThat(second.parent()).contains(other);
        assertThat(second.files()).extracting(CodeHistory.Change::path).containsExactly("Old.java");
        assertThat(service.history(service.reviewHost(REVIEW_ID), Optional.of(initial), Optional.empty(), 0).commits()).hasSize(1);
        assertThatThrownBy(() -> service.historyCommit(taskHost(), merge, Optional.of(initial))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.snapshot(taskHost(), initial, "../secret")).isInstanceOf(IllegalArgumentException.class);
        assertThat(git(checkout, "status", "--porcelain=v1")).isEqualTo(status);
        assertThat(git(checkout, "rev-parse", "HEAD")).isEqualTo(merge);
    }

    @Test
    void historyPagesStayBoundToTheSelectedRevision()
    {
        for (int index = 0; index < 101; index++) {
            git(checkout, "commit", "--allow-empty", "-m", "History " + index);
        }
        CodeHistory first = service.history(taskHost(), Optional.empty(), Optional.empty(), 0);
        assertThat(first.commits()).hasSize(100);
        assertThat(first.nextOffset()).hasValue(100);
        git(checkout, "commit", "--allow-empty", "-m", "New head after page one");
        CodeHistory second = service.history(taskHost(), Optional.of(first.revision()), Optional.empty(), first.nextOffset().orElseThrow());
        assertThat(second.commits()).hasSize(2);
        assertThat(second.commits().getLast().sha()).isEqualTo(head);
        assertThat(second.nextOffset()).isEmpty();
        assertThatThrownBy(() -> service.history(taskHost(), Optional.empty(), Optional.empty(), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blameTracksSavedEditsAndRetainsRepeatedCommitMetadataWithoutChangingGit()
    {
        write(checkout, MAIN_PATH, "one\ntwo\nthree\n");
        git(checkout, "add", MAIN_PATH);
        git(checkout, "commit", "-m", "Three lines");
        String committed = git(checkout, "rev-parse", "HEAD");
        write(checkout, MAIN_PATH, "one\nlocal edit\nthree\n");
        String before = git(checkout, "status", "--porcelain=v1");
        CodeBlame blame = service.blame(service.reviewHost(REVIEW_ID), MAIN_PATH);
        assertThat(blame.revision()).isEqualTo(committed);
        assertThat(blame.contentHash()).isEqualTo(service.file(service.reviewHost(REVIEW_ID), MAIN_PATH).contentHash().orElseThrow());
        assertThat(blame.spans()).extracting(CodeBlame.Span::line).containsExactly(1, 2, 3);
        assertThat(blame.spans()).extracting(span -> span.commit().matches("0+")).containsExactly(false, true, false);
        assertThat(blame.spans().getFirst().author()).isEqualTo("ByteQuay Test");
        assertThat(blame.spans().getLast().author()).isEqualTo("ByteQuay Test");
        assertThat(git(checkout, "status", "--porcelain=v1")).isEqualTo(before);
        assertThat(git(checkout, "rev-parse", "HEAD")).isEqualTo(committed);
    }

    @Test
    void blameFollowsRenamesAndWorksForJavaGoAndRust()
    {
        for (String path : ImmutableList.of(MAIN_PATH, "main.go", "src/main.rs")) {
            write(checkout, path, "source line\n");
        }
        git(checkout, "add", ".");
        git(checkout, "commit", "-m", "Language fixtures");
        String commit = git(checkout, "rev-parse", "HEAD");
        for (String path : ImmutableList.of(MAIN_PATH, "main.go", "src/main.rs")) {
            assertThat(service.blame(taskHost(), path).spans().getFirst().commit()).isEqualTo(commit);
        }
        git(checkout, "mv", "main.go", "renamed 測試.go");
        git(checkout, "commit", "-m", "Rename Go fixture");
        CodeBlame renamed = service.blame(taskHost(), "renamed 測試.go");
        assertThat(renamed.spans().getFirst().commit()).isEqualTo(commit);
        assertThat(renamed.spans().getFirst().originalPath()).isEqualTo("main.go");
    }

    @Test
    void blameDoesNotInventHistoryForUntrackedFilesOrFollowLinks()
            throws IOException
    {
        write(checkout, "new.go", "new source\n");
        assertThat(service.blame(taskHost(), "new.go").unavailableReason()).isPresent();
        Files.createSymbolicLink(checkout.resolve("linked.java"), checkout.resolve(MAIN_PATH));
        assertThat(service.blame(service.reviewHost(REVIEW_ID), "linked.java").unavailableReason()).contains("Blame is unavailable for symbolic links");
        assertThatThrownBy(() -> service.blame(taskHost(), "../outside")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void caughtUpCodeUsesCurrentFileMarksWhileReviewedChangesStayFrozen()
    {
        String listedRevision = service.files(service.reviewHost(REVIEW_ID)).revision();
        assertThat(service.file(service.reviewHost(REVIEW_ID), TEST_PATH, Optional.of(listedRevision)).content()).contains("test source\n");
        write(checkout, TEST_PATH, "caught up test\n");
        write(checkout, "src/test/java/TestLatest.java", "new remote test\n");
        git(checkout, "add", ".");
        git(checkout, "commit", "-m", "New remote head");
        String current = git(checkout, "rev-parse", "HEAD");

        assertThatThrownBy(() -> service.file(service.reviewHost(REVIEW_ID), TEST_PATH, Optional.of(listedRevision)))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("revision changed");
        assertThatThrownBy(() -> service.file(taskHost(), TEST_PATH, Optional.of(listedRevision)))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("revision changed");
        assertThat(service.file(service.reviewHost(REVIEW_ID), TEST_PATH, Optional.of(current)).content()).contains("caught up test\n");
        assertThat(service.file(taskHost(), TEST_PATH, Optional.of(current)).content()).contains("caught up test\n");

        CodeFiles files = service.files(service.reviewHost(REVIEW_ID));
        assertThat(files.revision()).isEqualTo(current);
        assertThat(files.files()).filteredOn(CodeFileEntry::changedInPr).extracting(CodeFileEntry::path)
                .containsExactly(TEST_PATH, "src/test/java/TestLatest.java");
        assertThat(files.files()).filteredOn(CodeFileEntry::addedInPr).extracting(CodeFileEntry::path)
                .containsExactly("src/test/java/TestLatest.java");
        assertThat(service.file(service.reviewHost(REVIEW_ID), TEST_PATH).prPatch()).get().asString().contains("-test source", "+caught up test").doesNotContain("-old test");
        assertThat(service.file(service.reviewHost(REVIEW_ID), "src/test/java/TestLatest.java").prPatch()).get().asString().contains("+new remote test");

        Changes reviewed = service.changes(service.reviewHost(REVIEW_ID), ChangeScope.COMMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(reviewed.headSha()).isEqualTo(head);
        assertThat(reviewed.files()).isEmpty();
        assertThat(store.listRuns(REVIEW_ID).getFirst().headSha()).isEqualTo(head);

        AgentTask completed = openTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of()), NOW, NOW.plusSeconds(60));
        when(reviews.task(REVIEW_ID)).thenReturn(completed.withWorktreeRemovedAt(NOW.plusSeconds(60)));
        assertThat(service.files(service.reviewHost(REVIEW_ID)).revision()).isEqualTo(head);
        assertThat(service.file(service.reviewHost(REVIEW_ID), TEST_PATH).content()).contains("test source\n");
        assertThat(service.file(service.reviewHost(REVIEW_ID), TEST_PATH).prPatch()).contains(TEST_PATCH);
        assertThat(service.file(service.reviewHost(REVIEW_ID), TEST_PATH, Optional.of(head)).prPatch()).contains(TEST_PATCH);
        assertThatThrownBy(() -> service.file(service.reviewHost(REVIEW_ID), TEST_PATH, Optional.of(current)))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("revision changed");
    }

    @Test
    void forcePushedCodeUsesCurrentMergeBaseWithoutRewritingTheReviewedComparison()
    {
        write(repository, "Upstream.java", "old upstream\n");
        git(repository, "add", ".");
        git(repository, "commit", "-m", "Previous upstream");
        String oldBase = git(repository, "rev-parse", "HEAD");
        git(checkout, "merge", "--ff-only", oldBase);
        write(checkout, TEST_PATH, "previous reviewed test\n");
        git(checkout, "commit", "-am", "Previous review");
        String reviewedHead = git(checkout, "rev-parse", "HEAD");
        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run(reviewedHead, oldBase)));

        git(repository, "reset", "--hard", head);
        write(repository, "Upstream.java", "rewritten upstream\n");
        git(repository, "add", ".");
        git(repository, "commit", "-m", "Rewritten upstream");
        String newBase = git(repository, "rev-parse", "HEAD");
        git(repository, "update-ref", "refs/remotes/origin/main", newBase);
        git(checkout, "reset", "--hard", newBase);
        write(checkout, TEST_PATH, "rebased remote test\n");
        git(checkout, "commit", "-am", "Rebased remote head");
        String current = git(checkout, "rev-parse", "HEAD");

        assertThat(service.files(service.reviewHost(REVIEW_ID)).files()).filteredOn(CodeFileEntry::changedInPr).extracting(CodeFileEntry::path).containsExactly(TEST_PATH);
        assertThat(service.file(service.reviewHost(REVIEW_ID), "Upstream.java").prPatch()).isEmpty();
        Changes live = service.changes(service.reviewHost(REVIEW_ID), ChangeScope.ALL, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(live.baseSha()).isEqualTo(newBase);
        assertThat(live.headSha()).isEqualTo(current);
        assertThat(live.files()).extracting(DiffFile::filename).containsExactly(TEST_PATH);
        assertThat(service.changes(service.reviewHost(REVIEW_ID), ChangeScope.COMMIT, Optional.of(current), Optional.empty(), Optional.empty()).baseSha()).isEqualTo(newBase);
        assertThat(service.changes(service.reviewHost(REVIEW_ID), ChangeScope.UNCOMMITTED, Optional.empty(), Optional.empty(), Optional.empty()).files()).isEmpty();

        Changes reviewed = service.changes(service.reviewHost(REVIEW_ID), ChangeScope.COMMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(reviewed.headSha()).isEqualTo(reviewedHead);
        assertThat(reviewed.baseSha()).isEqualTo(oldBase);
        assertThat(reviewed.files()).extracting(DiffFile::filename).containsExactly(TEST_PATH);
        assertThat(service.changes(
                service.reviewHost(REVIEW_ID),
                ChangeScope.COMMITTED,
                Optional.empty(),
                Optional.empty(),
                Optional.of(reviewedHead)).baseSha()).isEqualTo(oldBase);
    }

    @Test
    void refusesCodeReadsThatRaceAHeadMove()
    {
        GitCommandRunner runner = new GitCommandRunner()
        {
            @Override
            public Result runStdout(Path directory, String... arguments)
            {
                Result result = super.runStdout(directory, arguments);
                if (Arrays.asList(arguments).contains("ls-files") || Arrays.asList(arguments).contains("diff")) {
                    write(checkout, TEST_PATH, "new remote source after " + arguments[0] + "\n");
                    git(checkout, "commit", "-am", "Remote catch-up during read");
                }
                return result;
            }
        };
        ReviewCodeService racing = new ReviewCodeService(store, reviews, worktrees, workspaces, runner,
                new ReviewExperiments(
                        runner,
                        worktrees), tasks, turns, assignments, pullRequestRows, new WorktreeDiffReader(runner), pullRequests, agentProperties, claudeAccounts);

        assertThatThrownBy(() -> racing.files(racing.reviewHost(REVIEW_ID))).isInstanceOf(ReviewConflictException.class).hasMessageContaining("changed while it was being read");
        assertThatThrownBy(() -> racing.file(
                racing.reviewHost(REVIEW_ID),
                TEST_PATH)).isInstanceOf(ReviewConflictException.class).hasMessageContaining("changed while it was being read");
        verify(turns, never()).synchronizeWorktree(any(), anyLong(), any());
    }

    @Test
    void workingCopyMarksPullRequestAndLocalChangesAndSkipsDeletedFiles()
            throws IOException
    {
        write(checkout, MAIN_PATH, "experiment\n");
        write(checkout, "src/test/java/TestNew.java", "local test\n");
        write(checkout, "Staged.java", "staged\n");
        git(checkout, "add", "Staged.java");
        git(checkout, "mv", "Old.java", "Renamed.java");
        git(checkout, "rm", "--cached", "--quiet", "README.md");
        Files.delete(checkout.resolve("Gone.java"));
        write(checkout, "build/output", "ignored\n");

        CodeFiles files = service.files(service.reviewHost(REVIEW_ID));

        assertThat(files.source()).isEqualTo(CodeSource.WORKING_COPY);
        assertThat(files.revision()).isEqualTo(head);
        assertThat(files.sourceFingerprint()).contains(service.workingCopySource(checkout).fingerprint());
        assertThat(service.workingCopySource(checkout).localPaths())
                .containsExactly("Gone.java", "README.md", "Renamed.java", "Staged.java", MAIN_PATH, "src/test/java/TestNew.java");
        assertThat(files.truncated()).isFalse();
        assertThat(files.files()).containsExactly(
                new CodeFileEntry(".gitignore", false, false, false, false),
                new CodeFileEntry("README.md", false, false, true, true),
                new CodeFileEntry("Renamed.java", false, false, true, true),
                new CodeFileEntry("Staged.java", false, false, true, true),
                new CodeFileEntry(MAIN_PATH, true, false, false, true),
                new CodeFileEntry(ADDED_PATH, true, true, false, false),
                new CodeFileEntry(TEST_PATH, true, false, false, false),
                new CodeFileEntry("src/test/java/TestNew.java", false, false, true, true));
    }

    @Test
    void retainsUncommittedFileSnapshotsAfterTheDeveloperEditsAgainAndReviewEnds()
    {
        write(checkout, "local.txt", "reviewed local edit\n");
        WorktreeDiffReader reader = new WorktreeDiffReader(new GitCommandRunner());
        String tree = reader.snapshotTree(checkout);
        ReviewRun reviewed = run(head, head, head, reader.diff(checkout, head, Optional.of(tree)).files(), Optional.of(tree));
        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(reviewed));
        write(checkout, "local.txt", "later developer edit\n");
        assertThat(service.snapshot(service.reviewHost(REVIEW_ID), tree, "local.txt").content()).contains("reviewed local edit\n");
        assertThat(service.changes(service.reviewHost(REVIEW_ID), ChangeScope.COMMITTED, Optional.empty(), Optional.empty(), Optional.empty()).files())
                .extracting(DiffFile::filename).containsExactly("local.txt");
        AgentTask completed = openTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of()), NOW, NOW.plusSeconds(60));
        when(reviews.task(REVIEW_ID)).thenReturn(completed.withWorktreeRemovedAt(NOW));
        assertThat(service.files(service.reviewHost(REVIEW_ID)).revision()).isEqualTo(tree);
        assertThat(service.file(service.reviewHost(REVIEW_ID), "local.txt").content()).contains("reviewed local edit\n");
        assertThat(service.file(service.reviewHost(REVIEW_ID), "local.txt").editable()).isFalse();
    }

    @Test
    void readsTheFrozenHeadOnceTheWorkingCopyIsGone()
    {
        write(checkout, TEST_PATH, "unsaved experiment\n");
        AgentTask completed = openTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of()), NOW, NOW.plusSeconds(60));
        when(reviews.task(REVIEW_ID)).thenReturn(completed.withWorktreeRemovedAt(NOW));

        CodeFiles files = service.files(service.reviewHost(REVIEW_ID));
        assertThat(files.source()).isEqualTo(CodeSource.REVISION);
        assertThat(files.revision()).isEqualTo(head);
        assertThat(files.files()).extracting(CodeFileEntry::path).containsExactly(".gitignore", "Gone.java", "Old.java", "README.md", MAIN_PATH, ADDED_PATH, TEST_PATH);
        assertThat(files.files()).noneMatch(file -> file.uncommitted() || file.onlyInWorkingCopy());
        assertThat(files.files()).filteredOn(CodeFileEntry::addedInPr).extracting(CodeFileEntry::path).containsExactly(ADDED_PATH);

        CodeFile file = service.file(service.reviewHost(REVIEW_ID), TEST_PATH);
        assertThat(file.content()).contains("test source\n");
        assertThat(file.contentHash()).contains(Hashing.sha256().hashString("test source\n", UTF_8).toString());
        assertThat(file.editable()).isFalse();
        assertThat(file.readOnlyReason()).contains("Read-only: the working copy is gone");
        assertThat(file.prPatch()).contains(TEST_PATCH);
        assertThatThrownBy(() -> service.file(service.reviewHost(REVIEW_ID), "Missing.java")).isInstanceOf(ReviewNotFoundException.class);
        assertThatThrownBy(() -> service.file(service.reviewHost(REVIEW_ID), "src")).isInstanceOf(ReviewNotFoundException.class);

        write(checkout, TEST_PATH, "test source\n");
        when(reviews.task(REVIEW_ID)).thenReturn(openTask());
        worktrees.cleanup(worktree);
        assertThat(service.files(service.reviewHost(REVIEW_ID)).source()).isEqualTo(CodeSource.REVISION);
    }

    @Test
    void gitWarningsOnStandardErrorDoNotCorruptTheListing()
            throws IOException
    {
        Path locked = checkout.resolve("src/test/locked");
        write(checkout, "src/test/locked/Hidden.java", "unreadable\n");
        write(checkout, "src/test/java/TestVisible.java", "visible\n");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assertThat(service.files(service.reviewHost(REVIEW_ID)).files()).extracting(CodeFileEntry::path)
                    .containsExactly(".gitignore", "Gone.java", "Old.java", "README.md", MAIN_PATH, ADDED_PATH, TEST_PATH, "src/test/java/TestVisible.java");
        }
        finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void truncatedOutputDropsItsPartialEntry()
    {
        assertThat(ReviewCodeService.entries("a\0b\0", false)).containsExactly("a", "b");
        assertThat(ReviewCodeService.entries("a\0b\0par", true)).containsExactly("a", "b");
        assertThat(ReviewCodeService.entries("partial", true)).isEmpty();
        assertThat(ReviewCodeService.statusCodes(ReviewCodeService.entries(" M a\0R  new\0old\0?? u\0C  cop", true)))
                .isEqualTo(ImmutableListMultimap.of("a", " M", "new", "R ", "u", "??"));
        assertThat(ReviewCodeService.statusCodes(ReviewCodeService.entries("R  new\0ol", true))).isEqualTo(ImmutableListMultimap.of("new", "R "));
    }

    @Test
    void rejectsPathsOutsideTheRepository()
            throws IOException
    {
        for (String path : ImmutableList.of("", " ", "../secret.txt", "src/../README.md", "/etc/passwd", "a//b", "./README.md", "src\\main", "a\0b", ".git/config", "src/.GIT/x")) {
            assertThatThrownBy(() -> service.file(service.reviewHost(REVIEW_ID), path)).as(path).isInstanceOf(IllegalArgumentException.class);
        }
        Files.writeString(directory.resolve("secret.txt"), "outside\n");
        Files.createSymbolicLink(checkout.resolve("src/test/java/Escape.java"), directory.resolve("secret.txt"));
        assertThatThrownBy(() -> service.file(service.reviewHost(REVIEW_ID), "src/test/java/Escape.java")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.file(service.reviewHost(REVIEW_ID), "src/test/java/Missing.java")).isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void sourceAndTestsOfAnOpenReviewWithoutSymbolicLinksAreEditable()
            throws IOException
    {
        CodeFile test = service.file(service.reviewHost(REVIEW_ID), TEST_PATH);
        assertThat(test.editable()).isTrue();
        assertThat(test.readOnlyReason()).isEmpty();
        assertThat(test.content()).contains("test source\n");
        assertThat(test.contentHash()).contains(Hashing.sha256().hashString("test source\n", UTF_8).toString());
        assertThat(test.unavailableReason()).isEmpty();
        assertThat(test.prPatch()).contains(TEST_PATCH);

        CodeFile production = service.file(service.reviewHost(REVIEW_ID), MAIN_PATH);
        assertThat(production.editable()).isTrue();
        assertThat(production.readOnlyReason()).isEmpty();
        assertThat(production.prPatch()).isEmpty();

        Files.createSymbolicLink(checkout.resolve("src/test/linked"), checkout.resolve("src/main/java"));
        CodeFile linked = service.file(service.reviewHost(REVIEW_ID), "src/test/linked/Code.java");
        assertThat(linked.content()).contains("production source\n");
        assertThat(linked.readOnlyReason()).contains("Symbolic links are read-only here");
        Files.createSymbolicLink(checkout.resolve("src/test/java/Link.java"), checkout.resolve(TEST_PATH));
        assertThat(service.file(service.reviewHost(REVIEW_ID), "src/test/java/Link.java").readOnlyReason()).contains("Symbolic links are read-only here");

        when(store.findReview(REVIEW_ID)).thenReturn(Optional.of(review(AgentReview.State.CLOSED)));
        assertThat(service.file(service.reviewHost(REVIEW_ID), TEST_PATH).readOnlyReason()).contains("This review has ended — read-only");
        assertThat(service.file(service.reviewHost(REVIEW_ID), MAIN_PATH).readOnlyReason()).contains("This review has ended — read-only");
    }

    @Test
    void marksLinesChangedSinceTheMergeBase()
    {
        String path = "src/test/java/TestChanged.java";
        write(checkout, path, "a\nb\nc\nd\ne\nf\n");
        git(checkout, "add", path);
        git(checkout, "commit", "-m", "Base");
        String base = git(checkout, "rev-parse", "HEAD");
        write(checkout, path, "a\nB\nc\nnew one\nnew two\nd\nf\n");
        git(checkout, "commit", "-am", "Pull request");
        String pullRequestHead = git(checkout, "rev-parse", "HEAD");
        write(checkout, path, "a\nB\nc\nnew one\nnew two\nd\nf\nlocal\n");
        write(checkout, "src/test/java/TestUntracked.java", "untracked\n");
        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run(pullRequestHead, base)));

        assertThat(service.file(service.reviewHost(REVIEW_ID), path).lineChanges()).containsExactly(
                new LineChange(LineChange.Kind.MODIFIED, 2, 1),
                new LineChange(LineChange.Kind.ADDED, 4, 2),
                new LineChange(LineChange.Kind.DELETED, 6, 1),
                new LineChange(LineChange.Kind.ADDED, 8, 1));
        assertThat(service.file(service.reviewHost(REVIEW_ID), "src/test/java/TestUntracked.java").lineChanges()).isEmpty();

        AgentTask completed = openTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of()), NOW, NOW.plusSeconds(60));
        when(reviews.task(REVIEW_ID)).thenReturn(completed.withWorktreeRemovedAt(NOW));
        assertThat(service.file(service.reviewHost(REVIEW_ID), path).lineChanges()).containsExactly(
                new LineChange(LineChange.Kind.MODIFIED, 2, 1),
                new LineChange(LineChange.Kind.ADDED, 4, 2),
                new LineChange(LineChange.Kind.DELETED, 6, 1));

        // Git sorts blocks by path, so an unpaired rename source can follow the destination.
        String diff = """
                diff --git a/A B.java b/A B.java
                new file mode 100644
                --- /dev/null
                +++ b/A B.java\t
                @@ -1,2 +0,0 @@
                -x
                @@ -9 +7,2 @@ tail
                -old
                +new\r@@ -3,0 +0,0 @@
                +@@ -2 +2 @@
                diff --git a/Gone.java b/Gone.java
                deleted file mode 100644
                --- a/Gone.java
                +++ /dev/null
                @@ -1,3 +0,0 @@
                -gone
                diff --git "a/Q\\"uote\\tX.java" "b/Q\\"uote\\tX.java"
                --- "a/Q\\"uote\\tX.java"
                +++ "b/Q\\"uote\\tX.java"
                @@ -4 +4 @@
                -a
                +b
                """;
        assertThat(ReviewCodeService.lineChanges(diff, "A B.java")).containsExactly(new LineChange(LineChange.Kind.DELETED, 0, 2), new LineChange(LineChange.Kind.MODIFIED, 7, 2));
        assertThat(ReviewCodeService.lineChanges(diff, "Gone.java")).isEmpty();
        assertThat(ReviewCodeService.lineChanges(diff, "Q\"uote\tX.java")).containsExactly(new LineChange(LineChange.Kind.MODIFIED, 4, 1));
        assertThat(ReviewCodeService.unquote("\"b/\\303\\251t\\\\\\\"é\"")).isEqualTo("b/ét\\\"é");
    }

    @Test
    void marksOnlyTheEditedLinesOfARenamedFile()
    {
        write(checkout, "src/test/java/TestBefore.java", "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\n");
        git(checkout, "add", ".");
        git(checkout, "commit", "-m", "Base");
        String base = git(checkout, "rev-parse", "HEAD");
        git(checkout, "mv", "src/test/java/TestBefore.java", "src/test/java/TestAfter.java");
        write(checkout, "src/test/java/TestAfter.java", "one\ntwo\nTHREE\nfour\nfive\nsix\nseven\neight\n");
        git(checkout, "commit", "-am", "Move");
        String pullRequestHead = git(checkout, "rev-parse", "HEAD");
        // So the explicit -M, not the default, pairs the rename.
        git(checkout, "config", "diff.renames", "false");
        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run(pullRequestHead, base, ImmutableList.of(new DiffFile(
                "src/test/java/TestAfter.java",
                "renamed",
                1,
                1,
                null)))));

        assertThat(service.file(service.reviewHost(REVIEW_ID), "src/test/java/TestAfter.java").lineChanges()).containsExactly(new LineChange(LineChange.Kind.MODIFIED, 3, 1));

        AgentTask completed = openTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of()), NOW, NOW.plusSeconds(60));
        when(reviews.task(REVIEW_ID)).thenReturn(completed.withWorktreeRemovedAt(NOW));
        assertThat(service.file(service.reviewHost(REVIEW_ID), "src/test/java/TestAfter.java").lineChanges()).containsExactly(new LineChange(LineChange.Kind.MODIFIED, 3, 1));
    }

    @Test
    void readingChangesNeverRewritesTheIndex()
            throws IOException
    {
        Path index = Path.of(git(checkout, "rev-parse", "--path-format=absolute", "--git-path", "index"));
        FileTime written = Files.getLastModifiedTime(index);
        Files.setLastModifiedTime(checkout.resolve(TEST_PATH), FileTime.from(NOW.plusSeconds(3600 * 24 * 365 * 10)));
        git(checkout, "rm", "--cached", "--quiet", "README.md");
        FileTime unstaged = Files.getLastModifiedTime(index);

        assertThat(service.file(service.reviewHost(REVIEW_ID), TEST_PATH).lineChanges()).isEmpty();
        assertThat(service.file(service.reviewHost(REVIEW_ID), "README.md").lineChanges()).isEmpty();
        assertThat(Files.getLastModifiedTime(index)).isEqualTo(unstaged).isNotEqualTo(written);
    }

    @Test
    void withholdsBinaryAndOversizedContent()
            throws IOException
    {
        Files.write(checkout.resolve("src/test/image.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2});
        byte[] large = new byte[ReviewCodeService.MAXIMUM_FILE_BYTES + 1];
        Arrays.fill(large, (byte) 'a');
        Files.write(checkout.resolve("src/test/large.txt"), large);
        Files.write(checkout.resolve("src/test/limit.txt"), Arrays.copyOf(large, ReviewCodeService.MAXIMUM_FILE_BYTES));

        assertUnavailable(service.file(service.reviewHost(REVIEW_ID), "src/test/image.png"), "Binary file");
        assertUnavailable(service.file(service.reviewHost(REVIEW_ID), "src/test/large.txt"), "This file is larger than 2 MiB");
        assertThat(service.file(
                service.reviewHost(REVIEW_ID),
                "src/test/limit.txt").content()).hasValueSatisfying(content -> assertThat(content).hasSize(ReviewCodeService.MAXIMUM_FILE_BYTES));

        git(checkout, "add", "src/test");
        git(checkout, "commit", "-m", "Binary and large files");
        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run(head), run(git(checkout, "rev-parse", "HEAD"))));
        worktrees.cleanup(worktree);
        assertUnavailable(service.file(service.reviewHost(REVIEW_ID), "src/test/image.png"), "Binary file");
        assertUnavailable(service.file(service.reviewHost(REVIEW_ID), "src/test/large.txt"), "This file is larger than 2 MiB");
    }

    @Test
    void exploresAndEditsAPreparedWorktreeBeforeAnyReviewRun()
    {
        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of());
        git(repository, "update-ref", "refs/remotes/origin/main", head);
        assertThat(service.ideWorktree(service.reviewHost(REVIEW_ID))).isEqualTo(worktree);
        assertThat(service.terminal(service.reviewHost(REVIEW_ID)).workingDirectory()).isEqualTo(checkout);
        assertThat(service.files(service.reviewHost(REVIEW_ID)).revision()).isEqualTo(head);
        CodeFile loaded = service.file(service.reviewHost(REVIEW_ID), TEST_PATH);
        assertThat(loaded.content()).contains("test source\n");
        assertThat(loaded.editable()).isTrue();
        assertThat(save(REVIEW_ID, TEST_PATH, "exploration edit\n", loaded.contentHash().orElseThrow()).content()).contains("exploration edit\n");
        assertThat(service.changes(service.reviewHost(REVIEW_ID), ChangeScope.UNCOMMITTED, Optional.empty(), Optional.empty(), Optional.empty()).files()).isNotEmpty();
        assertThatThrownBy(() -> service.files(service.reviewHost("missing"))).isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void savesATestSourceOverTheLoadedVersion()
    {
        CodeFile loaded = service.file(service.reviewHost(REVIEW_ID), TEST_PATH);

        CodeFile saved = save(REVIEW_ID, TEST_PATH, "edited test\n", loaded.contentHash().orElseThrow());

        assertThat(checkout.resolve(TEST_PATH)).hasContent("edited test\n");
        assertThat(saved.content()).contains("edited test\n");
        assertThat(saved.contentHash()).contains(Hashing.sha256().hashString("edited test\n", UTF_8).toString());
        assertThat(saved.editable()).isTrue();
        assertThat(saved.prPatch()).contains(TEST_PATCH);
        assertThat(service.files(service.reviewHost(REVIEW_ID)).files()).contains(new CodeFileEntry(TEST_PATH, true, false, false, true));
    }

    @Test
    void savesReviewExperimentsInSourceAndProjectFiles()
    {
        for (String path : ImmutableList.of(MAIN_PATH, "src/lib.rs", "main.go", "Cargo.toml")) {
            write(checkout, path, "original\n");
            CodeFile loaded = service.file(service.reviewHost(REVIEW_ID), path);
            CodeFile saved = save(REVIEW_ID, path, "experiment\n", loaded.contentHash().orElseThrow());
            assertThat(saved.editable()).isTrue();
            assertThat(saved.content()).contains("experiment\n");
            assertThat(checkout.resolve(path)).hasContent("experiment\n");

            write(checkout, path, "agent edit\n");
            assertThatThrownBy(() -> save(REVIEW_ID, path, "stale edit\n", saved.contentHash().orElseThrow()))
                    .isInstanceOf(ReviewFileConflictException.class);
            assertThat(checkout.resolve(path)).hasContent("agent edit\n");
        }
    }

    @Test
    void savesEncodedIdeBytesAndRefusesStaleOrExistingFiles()
            throws IOException
    {
        byte[] original = {(byte) 0xff, (byte) 0xfe, 'a', 0};
        byte[] edited = {(byte) 0xff, (byte) 0xfe, 'b', 0};
        Files.write(checkout.resolve(MAIN_PATH), original);
        String hash = Hashing.sha256().hashBytes(original).toString();
        service.saveBytes(service.reviewHost(REVIEW_ID), MAIN_PATH, edited, hash);
        assertThat(Files.readAllBytes(checkout.resolve(MAIN_PATH))).isEqualTo(edited);
        assertThatThrownBy(() -> service.saveBytes(service.reviewHost(REVIEW_ID), MAIN_PATH, original, hash)).isInstanceOf(ReviewFileConflictException.class);
        assertThat(Files.readAllBytes(checkout.resolve(MAIN_PATH))).isEqualTo(edited);
        service.saveBytes(service.reviewHost(REVIEW_ID), "new/nested/main.go", "package main\n".getBytes(UTF_8), "-");
        assertThat(checkout.resolve("new/nested/main.go")).hasContent("package main\n");
        assertThatThrownBy(() -> service.saveBytes(service.reviewHost(REVIEW_ID), "new/nested/main.go", edited, "-")).isInstanceOf(ReviewConflictException.class);
        assertThat(checkout.resolve("new/nested/main.go")).hasContent("package main\n");
    }

    @Test
    void staleSaveReturnsTheVersionOnDiskWithoutWriting()
            throws IOException
    {
        String loaded = service.file(service.reviewHost(REVIEW_ID), TEST_PATH).contentHash().orElseThrow();
        write(checkout, TEST_PATH, "changed by the agent\n");

        assertThatThrownBy(() -> save(REVIEW_ID, TEST_PATH, "my draft\n", loaded))
                .isInstanceOf(ReviewFileConflictException.class);
        assertThat(checkout.resolve(TEST_PATH)).hasContent("changed by the agent\n");

        byte[] binary = {'P', 'K', 0, 1};
        Files.write(checkout.resolve(TEST_PATH), binary);
        assertThatThrownBy(() -> save(REVIEW_ID, TEST_PATH, "my draft\n", ""))
                .isInstanceOf(ReviewFileConflictException.class);
    }

    @Test
    void saveRefusesWhatTheEditorCannotEdit()
            throws IOException
    {
        String hash = Hashing.sha256().hashString("production source\n", UTF_8).toString();
        assertThatThrownBy(() -> save(REVIEW_ID, "src/test/.git/config", "edited\n", hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> save(REVIEW_ID, "src/test/../main/java/Code.java", "edited\n", hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> save(REVIEW_ID, TEST_PATH, "a".repeat(ReviewCodeService.MAXIMUM_FILE_BYTES + 1), hash))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The file is larger than 2 MiB");
        assertThatThrownBy(() -> save(REVIEW_ID, "src/test/java/Missing.java", "edited\n", hash)).isInstanceOf(ReviewNotFoundException.class);

        // A link in the file or any parent can redirect a write away from the named file.
        Files.createSymbolicLink(checkout.resolve("src/test/linked"), checkout.resolve("src/main/java"));
        assertThatThrownBy(() -> save(REVIEW_ID, "src/test/linked/Code.java", "edited\n", hash))
                .isInstanceOf(ReviewConflictException.class).hasMessage("Symbolic links are read-only here");
        Files.createSymbolicLink(checkout.resolve("src/test/java/Link.java"), checkout.resolve(MAIN_PATH));
        assertThatThrownBy(() -> save(REVIEW_ID, "src/test/java/Link.java", "edited\n", hash))
                .isInstanceOf(ReviewConflictException.class).hasMessage("Symbolic links are read-only here");
        assertThat(checkout.resolve(MAIN_PATH)).hasContent("production source\n");

        doThrow(new ReviewConflictException("A run is using this working copy")).when(reviews).mutateWorkingCopy(eq(REVIEW_ID), any());
        String loaded = Hashing.sha256().hashString("test source\n", UTF_8).toString();
        assertThatThrownBy(() -> save(REVIEW_ID, TEST_PATH, "edited\n", loaded)).isInstanceOf(ReviewConflictException.class).hasMessage("A run is using this working copy");
        assertThatThrownBy(() -> save(REVIEW_ID, MAIN_PATH, "edited\n", hash)).isInstanceOf(ReviewConflictException.class).hasMessage("A run is using this working copy");
        assertThat(checkout.resolve(TEST_PATH)).hasContent("test source\n");
        assertThat(checkout.resolve(MAIN_PATH)).hasContent("production source\n");
    }

    @Test
    void terminalStartsInTheWorkingCopyOfAnOpenReviewOnly()
    {
        assertThat(service.terminal(service.reviewHost(REVIEW_ID)).workingDirectory()).isEqualTo(checkout);

        AgentTask completed = openTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of()), NOW, NOW.plusSeconds(60));
        when(reviews.task(REVIEW_ID)).thenReturn(completed.withWorktreeRemovedAt(NOW.plusSeconds(60)));
        assertThatThrownBy(() -> service.terminal(service.reviewHost(REVIEW_ID))).isInstanceOf(ReviewConflictException.class).hasMessage("The working copy is gone");

        when(store.findReview(REVIEW_ID)).thenReturn(Optional.of(review(AgentReview.State.CLOSED)));
        assertThatThrownBy(() -> service.terminal(service.reviewHost(REVIEW_ID))).isInstanceOf(ReviewConflictException.class).hasMessage("The review has ended");
        assertThatThrownBy(() -> service.terminal(service.reviewHost("missing"))).isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void aDevelopmentTaskReadsAndEditsAnyFileOfItsWorkingCopyUnderItsClaim()
            throws IOException
    {
        write(checkout, MAIN_PATH, "committed production\n");
        git(checkout, "commit", "-am", "Develop locally");
        CodeFiles files = service.files(taskHost());
        assertThat(files.source()).isEqualTo(CodeSource.WORKING_COPY);
        assertThat(files.revision()).isEqualTo(git(checkout, "rev-parse", "HEAD"));
        assertThat(files.files()).contains(new CodeFileEntry(MAIN_PATH, true, false, false, false), new CodeFileEntry(ADDED_PATH, false, false, false, false),
                new CodeFileEntry(TEST_PATH, false, false, false, false));

        CodeFile production = service.file(taskHost(), MAIN_PATH);
        assertThat(production.editable()).isTrue();
        assertThat(production.prPatch()).hasValueSatisfying(patch -> assertThat(patch).contains("+committed production"));
        assertThat(service.file(taskHost(), "README.md").editable()).isTrue();

        CodeFile saved = save(taskHost(), MAIN_PATH, "edited production\n", production.contentHash().orElseThrow());
        assertThat(checkout.resolve(MAIN_PATH)).hasContent("edited production\n");
        assertThat(saved.content()).contains("edited production\n");
        assertThat(saved.editable()).isTrue();
        verify(turns).synchronizeWorktree(eq(worktree.taskId()), anyLong(), any());
        assertThatThrownBy(() -> save(taskHost(), MAIN_PATH, "stale\n", production.contentHash().orElseThrow()))
                .isInstanceOf(ReviewFileConflictException.class);

        // Git metadata, links anywhere in the chain and new files stay refused.
        String hash = Hashing.sha256().hashString("test source\n", UTF_8).toString();
        assertThatThrownBy(() -> save(taskHost(), ".git/config", "edited\n", hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> save(taskHost(), "src/main/java/New.java", "edited\n", hash)).isInstanceOf(ReviewNotFoundException.class);
        assertThat(checkout.resolve("src/main/java/New.java")).doesNotExist();
        Files.createSymbolicLink(checkout.resolve("src/main/linked"), checkout.resolve("src/test/java"));
        assertThat(service.file(taskHost(), "src/main/linked/TestCode.java").readOnlyReason()).contains("Symbolic links are read-only here");
        assertThatThrownBy(() -> save(taskHost(), "src/main/linked/TestCode.java", "edited\n", hash))
                .isInstanceOf(ReviewConflictException.class).hasMessage("Symbolic links are read-only here");
        assertThat(checkout.resolve(TEST_PATH)).hasContent("test source\n");
    }

    @Test
    void aDevelopmentTaskSaveRefusesWhileTheTaskIsBusy()
    {
        when(tasks.get(worktree.taskId())).thenReturn(developmentTask().claimWorktreeMutation(WorktreeMutationClaim.localRun("run-1", NOW), NOW));
        String hash = Hashing.sha256().hashString("production source\n", UTF_8).toString();

        assertThatThrownBy(() -> save(taskHost(), MAIN_PATH, "edited\n", hash)).isInstanceOf(ReviewConflictException.class).hasMessage("A run is using this working copy");

        when(tasks.get(worktree.taskId())).thenReturn(developmentTask().withDevelopmentContinuation(true, NOW));
        assertThatThrownBy(() -> save(taskHost(), MAIN_PATH, "edited\n", hash)).isInstanceOf(ReviewConflictException.class).hasMessage("Development automation is working");
        verify(turns, never()).synchronizeWorktree(any(), anyLong(), any());
        assertThat(checkout.resolve(MAIN_PATH)).hasContent("production source\n");
    }

    @Test
    void aDevelopmentTaskMarksLinesAgainstItsMergeBaseWithThePullRequestBase()
    {
        write(checkout, MAIN_PATH, "a\nb\nc\n");
        git(checkout, "commit", "-am", "Base");
        git(checkout, "update-ref", "refs/remotes/origin/main", git(checkout, "rev-parse", "HEAD"));
        write(checkout, MAIN_PATH, "a\nB\nc\n");
        git(checkout, "commit", "-am", "Task change");
        write(checkout, MAIN_PATH, "a\nB\nc\nlocal\n");

        assertThat(service.file(taskHost(), MAIN_PATH).lineChanges()).containsExactly(new LineChange(LineChange.Kind.MODIFIED, 2, 1), new LineChange(LineChange.Kind.ADDED, 4, 1));

        // Without a fetched PR base, the visible baseline remains the task's starting commit.
        when(pullRequests.findPullRequestDetail(REPOSITORY.fullName(), 7)).thenReturn(Optional.empty());
        assertThat(service.file(taskHost(), MAIN_PATH).lineChanges()).isNotEmpty();
        assertThat(service.files(taskHost()).files()).anyMatch(CodeFileEntry::changedInPr);
        assertThat(service.file(taskHost(), MAIN_PATH).prPatch()).isPresent();
    }

    @Test
    void terminalsUseTheOwningTasksClaudeProfileAndRejectAnUnavailableAccount()
    {
        assertThat(service.terminal(service.reviewHost(REVIEW_ID)).claudeConfigDirectory()).isEmpty();
        assertThat(service.terminal(taskHost()).claudeConfigDirectory()).isEmpty();
        Path claudeHome = directory.resolve("claude-home");
        ClaudeAccount account = new ClaudeAccount("11111111-1111-4111-8111-111111111111", "Work", Optional.of("work@example.com"), Optional.of(WORKSPACE_ID));
        when(claudeAccounts.find(account.laneId().value())).thenReturn(Optional.of(account));
        for (AgentLaneId lane : ImmutableList.of(AgentLaneId.CLAUDE_CLI, account.laneId())) {
            when(reviews.task(REVIEW_ID)).thenReturn(AgentTask.created(worktree.taskId(), false, Optional.empty(), "Review", TitleSource.USER, lane, worktree,
                    AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW));
            when(tasks.get(worktree.taskId())).thenReturn(AgentTask.created(worktree.taskId(), true, Optional.empty(), "Develop", TitleSource.USER, lane, worktree,
                    AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW));
            Path expected = lane.equals(AgentLaneId.CLAUDE_CLI) ? claudeHome : claudeHome.resolve("accounts").resolve(account.id());
            assertThat(service.terminal(service.reviewHost(REVIEW_ID)).claudeConfigDirectory()).contains(expected);
            assertThat(service.terminal(taskHost()).claudeConfigDirectory()).contains(expected);
        }
        doThrow(new AgentUnavailableException("Claude account unavailable in this repository"))
                .when(claudeAccounts).checkWorkspaceAccess(account.laneId().value(), Optional.of(WORKSPACE_ID));
        assertThatThrownBy(() -> service.terminal(service.reviewHost(REVIEW_ID))).isInstanceOf(AgentUnavailableException.class);
        assertThatThrownBy(() -> service.terminal(taskHost())).isInstanceOf(AgentUnavailableException.class);
    }

    @Test
    void aDevelopmentTaskIsReadOnlyOnceEndedAndRefusedWithoutItsWorkspaceOrWorktree()
    {
        assertThat(service.terminal(taskHost()).workingDirectory()).isEqualTo(checkout);

        AgentTask completed = developmentTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty(),
                ImmutableList.of()), NOW, NOW.plusSeconds(60));
        when(tasks.get(worktree.taskId())).thenReturn(completed);
        assertThat(service.file(taskHost(), MAIN_PATH).readOnlyReason()).contains("This task has ended — read-only");
        assertThatThrownBy(() -> service.terminal(taskHost())).isInstanceOf(ReviewConflictException.class).hasMessage("The task has ended");

        when(tasks.get(worktree.taskId())).thenReturn(completed.withWorktreeRemovedAt(NOW));
        assertThatThrownBy(() -> service.files(taskHost())).isInstanceOf(ReviewConflictException.class).hasMessage("The task's worktree was removed");
        assertThatThrownBy(() -> service.terminal(taskHost())).isInstanceOf(ReviewConflictException.class).hasMessage("The task's worktree was removed");

        when(tasks.get(worktree.taskId())).thenReturn(developmentTask());
        TaskCodeHost otherWorkspace = new TaskCodeHost("other", worktree.taskId().toString());
        assertThatThrownBy(() -> service.files(otherWorkspace)).isInstanceOf(ReviewNotFoundException.class).hasMessageContaining("does not belong to this workspace");
        assertThatThrownBy(() -> service.file(otherWorkspace, MAIN_PATH)).isInstanceOf(ReviewNotFoundException.class);
        assertThatThrownBy(() -> service.terminal(otherWorkspace)).isInstanceOf(ReviewNotFoundException.class);
        assertThatThrownBy(() -> save(otherWorkspace, MAIN_PATH, "edited\n", "hash")).isInstanceOf(ReviewNotFoundException.class);
        verify(turns, never()).synchronizeWorktree(any(), anyLong(), any());
    }

    @Test
    void workingDiffKeepsItsPathsAndPatchesTogetherWhenTheAgentChangesAnotherFile()
    {
        write(checkout, MAIN_PATH, "captured main change\n");
        GitCommandRunner racing = new GitCommandRunner()
        {
            @Override
            public Result runLocalBoundedStdout(Path workingDirectory, Duration timeout, int maximumOutputBytes, List<String> arguments)
            {
                Result result = super.runLocalBoundedStdout(workingDirectory, timeout, maximumOutputBytes, arguments);
                if (arguments.contains("--raw")) {
                    write(workingDirectory, MAIN_PATH, "production source\n");
                    write(workingDirectory, TEST_PATH, "later test change\n");
                }
                return result;
            }
        };
        WorktreeDiffReader.Diff diff = new WorktreeDiffReader(racing).diff(checkout, head, Optional.empty());
        assertThat(diff.files()).singleElement().satisfies(file -> {
            assertThat(file.filename()).isEqualTo(MAIN_PATH);
            assertThat(file.patch()).contains("+captured main change").doesNotContain("later test change");
        });
    }

    @Test
    void uncommittedReplacementHasOneCombinedDiffAndRetainsBothGitStatuses()
    {
        git(checkout, "rm", "--cached", "README.md");
        write(checkout, "README.md", "untracked replacement   \n");
        Changes changes = service.changes(taskHost(), ChangeScope.UNCOMMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(changes.files()).singleElement().satisfies(file -> {
            assertThat(file.filename()).isEqualTo("README.md");
            assertThat(file.status()).isEqualTo("modified");
            assertThat(file.patch()).contains("-readme\n+untracked replacement   \n");
        });
        assertThat(changes.staged()).containsExactly("README.md");
        assertThat(changes.unstaged()).isEmpty();
        assertThat(changes.untracked()).containsExactly("README.md");
        assertThat(git(checkout, "status", "--porcelain=v1")).contains("D  README.md", "?? README.md");
    }

    @Test
    void gitPatchesRetainTrailingSourceWhitespace()
    {
        write(checkout, MAIN_PATH, "changed with trailing spaces   \n");
        WorktreeDiffReader.Diff diff = new WorktreeDiffReader(new GitCommandRunner()).diff(checkout, head, Optional.empty());
        assertThat(diff.files()).singleElement().satisfies(file -> assertThat(file.patch()).endsWith("+changed with trailing spaces   \n"));
    }

    @Test
    void taskChangesIncludeLocalCommitsDirtyFilesAndStablePerCommitDiffs()
            throws IOException
    {
        when(assignments.findByTaskId(worktree.taskId().toString())).thenReturn(Optional.empty());
        String quoted = "space and \"quote\"\nfile.txt";
        write(checkout, MAIN_PATH, "first commit\n");
        write(checkout, quoted, "first file\n");
        git(checkout, "add", "--", MAIN_PATH, quoted);
        git(checkout, "commit", "-m", "First local commit");
        String first = git(checkout, "rev-parse", "HEAD");
        git(checkout, "mv", "Old.java", "Renamed.java");
        write(checkout, MAIN_PATH, "second commit\n");
        git(checkout, "commit", "-am", "Second local commit");
        String second = git(checkout, "rev-parse", "HEAD");
        write(checkout, MAIN_PATH, "staged edit\n");
        git(checkout, "add", MAIN_PATH);
        write(checkout, MAIN_PATH, "unstaged edit\n");
        Files.delete(checkout.resolve("Gone.java"));
        write(checkout, "local new.txt", "new local file\n");
        Files.write(checkout.resolve("binary.dat"), new byte[] {1, 0, 3});
        write(checkout, "build/ignored.txt", "ignored\n");
        git(checkout, "config", "diff.external", "false");
        git(checkout, "config", "color.ui", "always");
        // A concurrent provider owns index.lock; read-only inspection must still succeed.
        Path index = Path.of(git(checkout, "rev-parse", "--git-path", "index"));
        Files.writeString(index.resolveSibling("index.lock"), "held");

        Changes all = service.changes(taskHost(), ChangeScope.ALL, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(all.baseSha()).isEqualTo(head);
        assertThat(all.headSha()).isEqualTo(second);
        assertThat(all.commits()).extracting(commit -> commit.sha()).containsExactly(second, first);
        assertThat(all.files()).extracting(DiffFile::filename)
                .contains(MAIN_PATH, quoted, "Gone.java", "Renamed.java", "local new.txt", "binary.dat").doesNotContain("build/ignored.txt");
        assertThat(all.files()).filteredOn(file -> file.filename().equals(quoted)).singleElement()
                .satisfies(file -> assertThat(file.patch()).contains("+first file"));
        assertThat(all.staged()).containsExactly(MAIN_PATH);
        assertThat(all.unstaged()).containsExactly("Gone.java", MAIN_PATH);
        assertThat(all.untracked()).containsExactly("binary.dat", "local new.txt");
        assertThat(all.sourceFingerprint()).isPresent();
        assertThat(all.truncated()).isFalse();

        Changes dirty = service.changes(taskHost(), ChangeScope.UNCOMMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(dirty.files()).extracting(DiffFile::filename).containsExactlyInAnyOrder(MAIN_PATH, "Gone.java", "binary.dat", "local new.txt");
        assertThat(dirty.files()).filteredOn(file -> file.filename().equals(MAIN_PATH)).singleElement()
                .satisfies(file -> assertThat(file.patch()).contains("-second commit", "+unstaged edit"));
        Changes selected = service.changes(taskHost(), ChangeScope.COMMIT, Optional.of(first), Optional.empty(), Optional.empty());
        assertThat(selected.selectedCommit()).contains(first);
        assertThat(selected.files()).extracting(DiffFile::filename).containsExactlyInAnyOrder(MAIN_PATH, quoted);
        assertThat(selected.files()).filteredOn(file -> file.filename().equals(MAIN_PATH)).singleElement()
                .satisfies(file -> assertThat(file.patch()).contains("-production source", "+first commit").doesNotContain("unstaged edit"));
        assertThat(git(checkout, "rev-parse", "HEAD")).isEqualTo(second);
        assertThat(checkout.resolve(MAIN_PATH)).hasContent("unstaged edit\n");
        assertThatThrownBy(() -> service.changes(taskHost(), ChangeScope.COMMIT, Optional.of("--all"), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.changes(taskHost(), ChangeScope.ALL, Optional.empty(), Optional.of(second), Optional.of(first)))
                .isInstanceOf(IllegalArgumentException.class);

        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run(first, head)));
        Changes reviewed = service.changes(service.reviewHost(REVIEW_ID), ChangeScope.COMMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(reviewed.headSha()).isEqualTo(first);
        assertThat(reviewed.files()).extracting(DiffFile::filename).containsExactlyInAnyOrder(MAIN_PATH, quoted);
        assertThat(reviewed.sourceFingerprint()).isEmpty();
    }

    @Test
    void anEarlierReviewUsesItsRecordedMergeBaseAfterTheSourceHistoryChanges()
    {
        write(checkout, MAIN_PATH, "earlier reviewed change\n");
        git(checkout, "commit", "-am", "Earlier review");
        String earlierHead = git(checkout, "rev-parse", "HEAD");
        write(repository, "upstream.txt", "upstream change\n");
        git(repository, "add", "upstream.txt");
        git(repository, "commit", "-m", "Diverged base branch");
        String upstream = git(repository, "rev-parse", "HEAD");
        ReviewRun earlier = run(earlierHead, upstream, head, ImmutableList.of());
        git(checkout, "reset", "--hard", upstream);
        write(checkout, MAIN_PATH, "rewritten reviewed change\n");
        git(checkout, "commit", "-am", "Rewritten review");
        String currentHead = git(checkout, "rev-parse", "HEAD");
        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(earlier, run(currentHead, upstream)));

        Changes selected = service.changes(service.reviewHost(REVIEW_ID), ChangeScope.COMMITTED, Optional.empty(), Optional.empty(), Optional.of(earlierHead));
        assertThat(selected.baseSha()).isEqualTo(head);
        assertThat(selected.headSha()).isEqualTo(earlierHead);
        assertThat(selected.files()).extracting(DiffFile::filename).containsExactly(MAIN_PATH);
        assertThat(selected.files().getFirst().patch()).contains("+earlier reviewed change").doesNotContain("rewritten reviewed change");
        Changes current = service.changes(service.reviewHost(REVIEW_ID), ChangeScope.COMMITTED, Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(current.baseSha()).isEqualTo(upstream);
        assertThat(current.headSha()).isEqualTo(currentHead);
        assertThat(git(checkout, "rev-parse", "HEAD")).isEqualTo(currentHead);
        assertThatThrownBy(() -> service.changes(service.reviewHost(REVIEW_ID), ChangeScope.COMMITTED, Optional.empty(), Optional.empty(), Optional.of(upstream)))
                .isInstanceOf(ReviewNotFoundException.class).hasMessage("No review run exists for this revision");
    }

    @Test
    void inspectsMergedCommitsThatBranchedBeforeTheSelectedBase()
    {
        write(repository, "side.txt", "side branch change\n");
        git(repository, "add", "side.txt");
        git(repository, "commit", "-m", "Side branch commit");
        String side = git(repository, "rev-parse", "HEAD");
        write(checkout, MAIN_PATH, "task baseline\n");
        git(checkout, "commit", "-am", "Task baseline");
        String base = git(checkout, "rev-parse", "HEAD");
        git(checkout, "merge", "--no-ff", "-m", "Merge side branch", side);
        Changes selected = service.changes(taskHost(), ChangeScope.COMMIT, Optional.of(side), Optional.of(base), Optional.empty());
        assertThat(selected.commits()).extracting(commit -> commit.sha()).contains(side);
        assertThat(selected.files()).extracting(DiffFile::filename).containsExactly("side.txt");
    }

    @Test
    void workflowAndBusyTasksCanBrowseWithoutGrantingNewMutationRights()
    {
        AgentTask workflow = openTask().claimWorktreeMutation(WorktreeMutationClaim.localRun("run-1", NOW), NOW);
        when(tasks.get(worktree.taskId())).thenReturn(workflow);
        assertThat(service.files(taskHost()).files()).isNotEmpty();
        assertThat(service.file(taskHost(), MAIN_PATH).content()).contains("production source\n");
        assertThat(service.file(taskHost(), MAIN_PATH).editable()).isFalse();
        assertThatThrownBy(() -> save(taskHost(), MAIN_PATH, "change", "hash"))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("read-only");
        assertThatThrownBy(() -> service.terminal(taskHost())).isInstanceOf(ReviewConflictException.class).hasMessageContaining("read-only");
        verify(turns, never()).synchronizeWorktree(any(), anyLong(), any());
    }

    private TaskCodeHost taskHost()
    {
        return new TaskCodeHost(WORKSPACE_ID, worktree.taskId().toString());
    }

    private AgentTask developmentTask()
    {
        return AgentTask.created(worktree.taskId(), true, Optional.empty(), "Develop", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
    }

    private static PullRequestDetail pullRequestDetail(String baseRef)
    {
        return new PullRequestDetail(1, REPOSITORY.fullName(), 7, "Task", "https://github.com/owner/repo/pull/7", "open", false, true, "clean", "owner", null, NOW, NOW, null,
                baseRef, "bytequay/task", "head", "", ImmutableList.of(), ImmutableList.of(), null, ImmutableList.of(), 1, 1, 1, 0, PullRequestDetail.CiStatus.PASSING,
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true);
    }

    private static void assertUnavailable(CodeFile file, String reason)
    {
        assertThat(file.unavailableReason()).contains(reason);
        assertThat(file.content()).isEmpty();
        assertThat(file.contentHash()).isEmpty();
    }

    private AgentTask openTask()
    {
        AgentTaskDefaults defaults = AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO);
        return AgentTask.created(worktree.taskId(), false, Optional.empty(), "Review", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree, defaults, NOW);
    }

    private AgentReview review(AgentReview.State state)
    {
        AgentReview review = pullRequestReview(REVIEW_ID, WORKSPACE_ID, REPOSITORY.fullName(), 7, worktree.taskId().toString(), NOW);
        return state == AgentReview.State.CLOSED ? review.closed(NOW).withFollowUpPaused(false, NOW) : review;
    }

    private static ReviewRun run(String headSha)
    {
        return run(headSha, headSha);
    }

    private static ReviewRun run(String headSha, String mergeBaseSha)
    {
        return run(headSha, mergeBaseSha, ImmutableList.of(
                new DiffFile(TEST_PATH, "modified", 1, 1, TEST_PATCH),
                new DiffFile(MAIN_PATH, "modified", 1, 1, null),
                new DiffFile(ADDED_PATH, "added", 1, 0, "@@ -0,0 +1 @@\n+added test"),
                new DiffFile(OUTLINE_PATH, "modified", 6, 1, OUTLINE_PATCH)));
    }

    private static ReviewRun run(String headSha, String mergeBaseSha, List<DiffFile> diff)
    {
        return run(headSha, mergeBaseSha, mergeBaseSha, diff);
    }

    private static ReviewRun run(String headSha, String baseSha, String mergeBaseSha, List<DiffFile> diff)
    {
        return run(headSha, baseSha, mergeBaseSha, diff, Optional.empty());
    }

    private static ReviewRun run(String headSha, String baseSha, String mergeBaseSha, List<DiffFile> diff, Optional<String> tree)
    {
        return new ReviewRun("run-" + headSha, REVIEW_ID, 1, ReviewRun.Scope.FULL, "main", baseSha, mergeBaseSha, headSha, tree, Optional.empty(), ImmutableList.of(), diff,
                ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }

    private CodeFile save(String reviewId, String path, String content, String expectedHash)
    {
        service.saveBytes(service.reviewHost(reviewId), path, content.getBytes(UTF_8), expectedHash);
        return service.file(service.reviewHost(reviewId), path);
    }

    private CodeFile save(TaskCodeHost host, String path, String content, String expectedHash)
    {
        service.saveBytes(host, path, content.getBytes(UTF_8), expectedHash);
        return service.file(host, path);
    }
}
