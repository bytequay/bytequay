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
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.CodeSymbol;
import com.bytequay.agent.core.CodeSymbolResult;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.app.agent.codegraph.FakeCodeGraphService;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.ReviewExperiments;
import com.bytequay.app.review.ReviewCodeService.CodeFile;
import com.bytequay.app.review.ReviewCodeService.CodeFileEntry;
import com.bytequay.app.review.ReviewCodeService.CodeFiles;
import com.bytequay.app.review.ReviewCodeService.CodeGraphState;
import com.bytequay.app.review.ReviewCodeService.CodeOutline;
import com.bytequay.app.review.ReviewCodeService.CodeSource;
import com.bytequay.app.review.ReviewCodeService.LineChange;
import com.bytequay.app.review.ReviewCodeService.OutlineSymbol;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.bytequay.app.git.GitTestSupport.git;
import static com.bytequay.app.git.GitTestSupport.write;
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
    private final AgentResourceService resources = mock(AgentResourceService.class);
    private final FakeCodeGraphService codeGraph = new FakeCodeGraphService();
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final TaskPullRequestOwnership pullRequestOwnership = mock(TaskPullRequestOwnership.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
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
        WorkspacePaths paths = new WorkspacePaths(directory.resolve("managed").toString());
        repository = paths.repository(WORKSPACE_ID);
        Files.createDirectories(repository);
        Files.writeString(paths.workspace(WORKSPACE_ID).resolve(".bytequay-workspace"), WORKSPACE_ID + "\n1\n");
        git(repository, "init", "--initial-branch=main");
        git(repository, "config", "user.name", "ByteQuay Test");
        git(repository, "config", "user.email", "test@example.invalid");
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
        worktrees = new ManagedWorktreeService(paths, workspaceStore, runner);
        TaskId taskId = TaskId.random();
        worktree = worktrees.provision(new ProvisionWorktreeCommand(taskId, WORKSPACE_ID, RepositoryIdentity.valueOf(REPOSITORY.fullName()), head, "bytequay/task/" + taskId));
        checkout = worktree.canonicalWorktree();

        when(store.findReview(REVIEW_ID)).thenReturn(Optional.of(review(AgentReview.State.OPEN)));
        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run(head)));
        when(reviews.task(REVIEW_ID)).thenReturn(openTask());
        when(workspaces.requireReadyRepository(WORKSPACE_ID)).thenReturn(new WorkspaceRepositoryContext(
                WORKSPACE_ID, repository, 1, REPOSITORY, OptionalLong.empty(), REPOSITORY, "origin", "origin", "main", ImmutableList.of()));
        Path runtime = directory.resolve("runtime").toAbsolutePath();
        when(resources.require(taskId)).thenReturn(new TaskResources(runtime, runtime.resolve("events.jsonl"), runtime.resolve("blobs"), runtime.resolve("provider")));
        service = new ReviewCodeService(store, reviews, worktrees, workspaces, runner, codeGraph, resources, new ReviewExperiments(runner, worktrees), tasks, turns,
                pullRequestOwnership, pullRequests);
        when(reviews.mutateWorkingCopy(eq(REVIEW_ID), any())).thenAnswer(invocation -> {
            Function<AgentTask, ?> mutation = invocation.getArgument(1);
            return mutation.apply(reviews.task(REVIEW_ID));
        });

        when(tasks.getInteractive(WORKSPACE_ID, taskId)).thenReturn(developmentTask());
        when(pullRequestOwnership.owns(any(), eq(REPOSITORY.fullName()), eq(7), eq(false))).thenReturn(true);
        when(pullRequests.getPullRequestDiffFiles(REPOSITORY.fullName(), 7, null, null)).thenReturn(ImmutableList.of(
                new DiffFile(MAIN_PATH, "modified", 1, 1, "@@ -1 +1 @@\n-old\n+production source"),
                new DiffFile(ADDED_PATH, "added", 1, 0, "@@ -0,0 +1 @@\n+added test")));
        when(pullRequests.findPullRequestDetail(REPOSITORY.fullName(), 7)).thenReturn(Optional.of(pullRequestDetail("main")));
        doAnswer(invocation -> {
            Consumer<AgentTask> action = invocation.getArgument(2);
            action.accept(tasks.getInteractive(WORKSPACE_ID, taskId));
            return null;
        }).when(turns).synchronizeWorktree(eq(taskId), anyLong(), any());
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

        CodeFiles files = service.files(REVIEW_ID);

        assertThat(files.source()).isEqualTo(CodeSource.WORKING_COPY);
        assertThat(files.revision()).isEqualTo(head);
        assertThat(files.sourceFingerprint()).contains(service.workingCopySource(checkout).fingerprint());
        assertThat(service.workingCopySource(checkout).localPaths())
                .containsExactly("Gone.java", "README.md", "Renamed.java", "Staged.java", MAIN_PATH, "src/test/java/TestNew.java");
        assertThat(files.codeGraph()).isEqualTo(CodeGraphState.READY);
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
    void readsTheFrozenHeadOnceTheWorkingCopyIsGone()
    {
        write(checkout, TEST_PATH, "unsaved experiment\n");
        AgentTask completed = openTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty()), NOW, NOW.plusSeconds(60));
        when(reviews.task(REVIEW_ID)).thenReturn(completed.withWorktreeRemovedAt(NOW));

        CodeFiles files = service.files(REVIEW_ID);
        assertThat(files.source()).isEqualTo(CodeSource.REVISION);
        assertThat(files.revision()).isEqualTo(head);
        assertThat(files.files()).extracting(CodeFileEntry::path).containsExactly(".gitignore", "Gone.java", "Old.java", "README.md", MAIN_PATH, ADDED_PATH, TEST_PATH);
        assertThat(files.files()).noneMatch(file -> file.uncommitted() || file.onlyInWorkingCopy());
        assertThat(files.files()).filteredOn(CodeFileEntry::addedInPr).extracting(CodeFileEntry::path).containsExactly(ADDED_PATH);

        CodeFile file = service.file(REVIEW_ID, TEST_PATH);
        assertThat(file.content()).contains("test source\n");
        assertThat(file.contentHash()).contains(Hashing.sha256().hashString("test source\n", UTF_8).toString());
        assertThat(file.editable()).isFalse();
        assertThat(file.readOnlyReason()).contains("Read-only: the working copy is gone");
        assertThat(file.prPatch()).contains(TEST_PATCH);
        assertThatThrownBy(() -> service.file(REVIEW_ID, "Missing.java")).isInstanceOf(ReviewNotFoundException.class);
        assertThatThrownBy(() -> service.file(REVIEW_ID, "src")).isInstanceOf(ReviewNotFoundException.class);

        write(checkout, TEST_PATH, "test source\n");
        when(reviews.task(REVIEW_ID)).thenReturn(openTask());
        worktrees.cleanup(worktree);
        assertThat(service.files(REVIEW_ID).source()).isEqualTo(CodeSource.REVISION);
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
            assertThat(service.files(REVIEW_ID).files()).extracting(CodeFileEntry::path)
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
            assertThatThrownBy(() -> service.file(REVIEW_ID, path)).as(path).isInstanceOf(IllegalArgumentException.class);
        }
        Files.writeString(directory.resolve("secret.txt"), "outside\n");
        Files.createSymbolicLink(checkout.resolve("src/test/java/Escape.java"), directory.resolve("secret.txt"));
        assertThatThrownBy(() -> service.file(REVIEW_ID, "src/test/java/Escape.java")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.file(REVIEW_ID, "src/test/java/Missing.java")).isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void onlyTestSourceOfAnOpenReviewWithoutSymbolicLinksIsEditable()
            throws IOException
    {
        CodeFile test = service.file(REVIEW_ID, TEST_PATH);
        assertThat(test.editable()).isTrue();
        assertThat(test.readOnlyReason()).isEmpty();
        assertThat(test.content()).contains("test source\n");
        assertThat(test.contentHash()).contains(Hashing.sha256().hashString("test source\n", UTF_8).toString());
        assertThat(test.unavailableReason()).isEmpty();
        assertThat(test.prPatch()).contains(TEST_PATCH);

        CodeFile production = service.file(REVIEW_ID, MAIN_PATH);
        assertThat(production.editable()).isFalse();
        assertThat(production.readOnlyReason()).contains("Production source — read-only here");
        assertThat(production.prPatch()).isEmpty();

        Files.createSymbolicLink(checkout.resolve("src/test/linked"), checkout.resolve("src/main/java"));
        CodeFile linked = service.file(REVIEW_ID, "src/test/linked/Code.java");
        assertThat(linked.content()).contains("production source\n");
        assertThat(linked.readOnlyReason()).contains("Symbolic links are read-only here");
        Files.createSymbolicLink(checkout.resolve("src/test/java/Link.java"), checkout.resolve(TEST_PATH));
        assertThat(service.file(REVIEW_ID, "src/test/java/Link.java").readOnlyReason()).contains("Symbolic links are read-only here");

        when(store.findReview(REVIEW_ID)).thenReturn(Optional.of(review(AgentReview.State.CLOSED)));
        assertThat(service.file(REVIEW_ID, TEST_PATH).readOnlyReason()).contains("This review has ended — read-only");
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

        assertThat(service.file(REVIEW_ID, path).lineChanges()).containsExactly(
                new LineChange(LineChange.Kind.MODIFIED, 2, 1),
                new LineChange(LineChange.Kind.ADDED, 4, 2),
                new LineChange(LineChange.Kind.DELETED, 6, 1),
                new LineChange(LineChange.Kind.ADDED, 8, 1));
        assertThat(service.file(REVIEW_ID, "src/test/java/TestUntracked.java").lineChanges()).isEmpty();

        AgentTask completed = openTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty()), NOW, NOW.plusSeconds(60));
        when(reviews.task(REVIEW_ID)).thenReturn(completed.withWorktreeRemovedAt(NOW));
        assertThat(service.file(REVIEW_ID, path).lineChanges()).containsExactly(
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
        assertThat(ReviewCodeService.lineChanges(diff, "A B.java")).containsExactly(
                new LineChange(LineChange.Kind.DELETED, 0, 2),
                new LineChange(LineChange.Kind.MODIFIED, 7, 2));
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
        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run(pullRequestHead, base, ImmutableList.of(new DiffFile("src/test/java/TestAfter.java", "renamed", 1, 1, null)))));

        assertThat(service.file(REVIEW_ID, "src/test/java/TestAfter.java").lineChanges()).containsExactly(new LineChange(LineChange.Kind.MODIFIED, 3, 1));

        AgentTask completed = openTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty()), NOW, NOW.plusSeconds(60));
        when(reviews.task(REVIEW_ID)).thenReturn(completed.withWorktreeRemovedAt(NOW));
        assertThat(service.file(REVIEW_ID, "src/test/java/TestAfter.java").lineChanges()).containsExactly(new LineChange(LineChange.Kind.MODIFIED, 3, 1));
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

        assertThat(service.file(REVIEW_ID, TEST_PATH).lineChanges()).isEmpty();
        assertThat(service.file(REVIEW_ID, "README.md").lineChanges()).isEmpty();
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

        assertUnavailable(service.file(REVIEW_ID, "src/test/image.png"), "Binary file");
        assertUnavailable(service.file(REVIEW_ID, "src/test/large.txt"), "This file is larger than 2 MiB");
        assertThat(service.file(REVIEW_ID, "src/test/limit.txt").content()).hasValueSatisfying(content -> assertThat(content).hasSize(ReviewCodeService.MAXIMUM_FILE_BYTES));

        git(checkout, "add", "src/test");
        git(checkout, "commit", "-m", "Binary and large files");
        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of(run(head), run(git(checkout, "rev-parse", "HEAD"))));
        worktrees.cleanup(worktree);
        assertUnavailable(service.file(REVIEW_ID, "src/test/image.png"), "Binary file");
        assertUnavailable(service.file(REVIEW_ID, "src/test/large.txt"), "This file is larger than 2 MiB");
    }

    @Test
    void requiresAReviewWithARun()
    {
        when(store.listRuns(REVIEW_ID)).thenReturn(ImmutableList.of());
        assertThatThrownBy(() -> service.files(REVIEW_ID)).isInstanceOf(ReviewConflictException.class).hasMessage("This review has no run yet");
        assertThatThrownBy(() -> service.file(REVIEW_ID, TEST_PATH)).isInstanceOf(ReviewConflictException.class);
        assertThatThrownBy(() -> service.files("missing")).isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void savesATestSourceOverTheLoadedVersion()
    {
        CodeFile loaded = service.file(REVIEW_ID, TEST_PATH);

        CodeFile saved = service.save(REVIEW_ID, TEST_PATH, "edited test\n", loaded.contentHash().orElseThrow());

        assertThat(checkout.resolve(TEST_PATH)).hasContent("edited test\n");
        assertThat(saved.content()).contains("edited test\n");
        assertThat(saved.contentHash()).contains(Hashing.sha256().hashString("edited test\n", UTF_8).toString());
        assertThat(saved.editable()).isTrue();
        assertThat(saved.prPatch()).contains(TEST_PATCH);
        assertThat(service.files(REVIEW_ID).files()).contains(new CodeFileEntry(TEST_PATH, true, false, false, true));
    }

    @Test
    void staleSaveReturnsTheVersionOnDiskWithoutWriting()
            throws IOException
    {
        String loaded = service.file(REVIEW_ID, TEST_PATH).contentHash().orElseThrow();
        write(checkout, TEST_PATH, "changed by the agent\n");

        assertThatThrownBy(() -> service.save(REVIEW_ID, TEST_PATH, "my draft\n", loaded))
                .isInstanceOfSatisfying(ReviewFileConflictException.class, conflict -> {
                    assertThat(conflict.currentHash()).isEqualTo(Hashing.sha256().hashString("changed by the agent\n", UTF_8).toString());
                    assertThat(conflict.currentContent()).contains("changed by the agent\n");
                });
        assertThat(checkout.resolve(TEST_PATH)).hasContent("changed by the agent\n");

        byte[] binary = {'P', 'K', 0, 1};
        Files.write(checkout.resolve(TEST_PATH), binary);
        assertThatThrownBy(() -> service.save(REVIEW_ID, TEST_PATH, "my draft\n", ""))
                .isInstanceOfSatisfying(ReviewFileConflictException.class, conflict -> {
                    assertThat(conflict.currentHash()).isEqualTo(Hashing.sha256().hashBytes(binary).toString());
                    assertThat(conflict.currentContent()).isEmpty();
                });
    }

    @Test
    void saveRefusesWhatTheEditorCannotEdit()
            throws IOException
    {
        String hash = Hashing.sha256().hashString("production source\n", UTF_8).toString();
        assertThatThrownBy(() -> service.save(REVIEW_ID, MAIN_PATH, "edited\n", hash)).isInstanceOf(IllegalArgumentException.class).hasMessage("Only test sources can be edited here");
        assertThatThrownBy(() -> service.save(REVIEW_ID, "src/test/.git/config", "edited\n", hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(REVIEW_ID, "src/test/../main/java/Code.java", "edited\n", hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(REVIEW_ID, TEST_PATH, "a".repeat(ReviewCodeService.MAXIMUM_FILE_BYTES + 1), hash))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The file is larger than 2 MiB");
        assertThatThrownBy(() -> service.save(REVIEW_ID, "src/test/java/Missing.java", "edited\n", hash)).isInstanceOf(ReviewNotFoundException.class);

        // A link in the file or any parent could aim a test path at production source.
        Files.createSymbolicLink(checkout.resolve("src/test/linked"), checkout.resolve("src/main/java"));
        assertThatThrownBy(() -> service.save(REVIEW_ID, "src/test/linked/Code.java", "edited\n", hash))
                .isInstanceOf(ReviewConflictException.class).hasMessage("Symbolic links are read-only here");
        Files.createSymbolicLink(checkout.resolve("src/test/java/Link.java"), checkout.resolve(MAIN_PATH));
        assertThatThrownBy(() -> service.save(REVIEW_ID, "src/test/java/Link.java", "edited\n", hash))
                .isInstanceOf(ReviewConflictException.class).hasMessage("Symbolic links are read-only here");
        assertThat(checkout.resolve(MAIN_PATH)).hasContent("production source\n");

        doThrow(new ReviewConflictException("A run is using this working copy")).when(reviews).mutateWorkingCopy(eq(REVIEW_ID), any());
        String loaded = Hashing.sha256().hashString("test source\n", UTF_8).toString();
        assertThatThrownBy(() -> service.save(REVIEW_ID, TEST_PATH, "edited\n", loaded)).isInstanceOf(ReviewConflictException.class).hasMessage("A run is using this working copy");
        assertThat(checkout.resolve(TEST_PATH)).hasContent("test source\n");
    }

    @Test
    void discardCleansTheWorkingCopyAndDropsTheSavedExperiment()
    {
        write(checkout, TEST_PATH, "saved experiment\n");
        git(checkout, "stash", "push", "--include-untracked", "--message", "bytequay-review-experiment:" + worktree.taskId() + ":sync:failed");
        write(checkout, "src/test/java/TestNew.java", "unrelated stash\n");
        git(checkout, "stash", "push", "--include-untracked", "--message", "User work");
        write(checkout, TEST_PATH, "current edit\n");
        write(checkout, "src/test/java/TestLocal.java", "local test\n");
        write(checkout, "build/output", "ignored\n");
        assertThat(service.files(REVIEW_ID).savedExperiment()).isTrue();

        assertThatThrownBy(() -> service.discard(REVIEW_ID, "a".repeat(40))).isInstanceOf(ReviewConflictException.class).hasMessageContaining("HEAD changed");
        assertThat(checkout.resolve(TEST_PATH)).hasContent("current edit\n");

        CodeFiles files = service.discard(REVIEW_ID, head);

        assertThat(files.savedExperiment()).isFalse();
        assertThat(files.revision()).isEqualTo(head);
        assertThat(files.files()).noneMatch(file -> file.uncommitted() || file.onlyInWorkingCopy());
        assertThat(checkout.resolve(TEST_PATH)).hasContent("test source\n");
        assertThat(checkout.resolve("src/test/java/TestLocal.java")).doesNotExist();
        assertThat(checkout.resolve("build/output")).hasContent("ignored\n");
        assertThat(git(checkout, "stash", "list", "--format=%gs")).endsWith("User work");
        assertThatThrownBy(() -> service.discard(REVIEW_ID, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void terminalStartsInTheWorkingCopyOfAnOpenReviewOnly()
    {
        assertThat(service.terminalDirectory(REVIEW_ID)).isEqualTo(checkout);

        AgentTask completed = openTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty()), NOW, NOW.plusSeconds(60));
        when(reviews.task(REVIEW_ID)).thenReturn(completed.withWorktreeRemovedAt(NOW.plusSeconds(60)));
        assertThatThrownBy(() -> service.terminalDirectory(REVIEW_ID)).isInstanceOf(ReviewConflictException.class).hasMessage("The working copy is gone");

        when(store.findReview(REVIEW_ID)).thenReturn(Optional.of(review(AgentReview.State.CLOSED)));
        assertThatThrownBy(() -> service.terminalDirectory(REVIEW_ID)).isInstanceOf(ReviewConflictException.class).hasMessage("The review has ended");
        assertThatThrownBy(() -> service.terminalDirectory("missing")).isInstanceOf(ReviewNotFoundException.class);
    }

    @Test
    void outlineMarksJUnitTestMethodsInLineOrder()
    {
        String path = "src/test/java/demo/TestTests.java";
        write(checkout, path, """
                package demo;

                class TestOutline
                {
                    @Test
                    void simple()
                    {
                    }

                    @org.junit.jupiter.api.Test
                    void qualified() {}

                    @ParameterizedTest
                    @ValueSource(ints = 1)
                    void parameterized(int value) {}

                    @Test
                    @Timeout(5)
                    void twoLinesBelow()
                    {
                    }

                    @TestInstance(Lifecycle.PER_CLASS)
                    public String helper()
                    {
                        return "@Test";
                    }
                }
                """);
        CodeSymbol type = symbol("TestOutline", "class", path, 3, 28);
        CodeSymbol simple = symbol("simple", "method", path, 5, 8);
        CodeSymbol qualified = symbol("qualified", "method", path, 10, 11);
        CodeSymbol parameterized = symbol("parameterized", "method", path, 13, 15);
        CodeSymbol twoLinesBelow = symbol("twoLinesBelow", "method", path, 17, 21);
        CodeSymbol helper = symbol("helper", "method", path, 23, 27);
        codeGraph.symbols = CodeSymbolResult.ready(ImmutableList.of(helper, twoLinesBelow, simple, type, parameterized, qualified));

        assertThat(service.outline(REVIEW_ID, path)).isEqualTo(new CodeOutline(CodeSymbolResult.Status.READY, ImmutableList.of(
                new OutlineSymbol(type, false, false),
                new OutlineSymbol(simple, true, false),
                new OutlineSymbol(qualified, true, false),
                new OutlineSymbol(parameterized, true, false),
                new OutlineSymbol(twoLinesBelow, true, false),
                new OutlineSymbol(helper, false, false))));
        assertThat(codeGraph.symbolReads).containsExactly("file:" + path);
        assertThatThrownBy(() -> service.outline(REVIEW_ID, "../secret.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.outline(REVIEW_ID, "src/test/java/Missing.java")).isInstanceOf(ReviewNotFoundException.class);

        codeGraph.symbols = CodeSymbolResult.BUSY;
        assertThat(service.outline(REVIEW_ID, path)).isEqualTo(new CodeOutline(CodeSymbolResult.Status.BUSY, ImmutableList.of()));
    }

    @Test
    void outlineMarksMethodsWhoseEveryLineThePullRequestAdded()
    {
        write(checkout, OUTLINE_PATH, """
                class TestOutline
                {
                    @Test
                    void kept()
                    {
                        assertThat(1).isOne();
                    }

                    @Test
                    void added()
                    {
                    }
                }
                """);
        CodeSymbol type = symbol("TestOutline", "class", OUTLINE_PATH, 1, 13);
        CodeSymbol kept = symbol("kept", "method", OUTLINE_PATH, 3, 7);
        CodeSymbol added = symbol("added", "method", OUTLINE_PATH, 9, 12);
        codeGraph.symbols = CodeSymbolResult.ready(ImmutableList.of(type, kept, added));

        assertThat(service.outline(REVIEW_ID, OUTLINE_PATH).symbols()).containsExactly(
                new OutlineSymbol(type, false, false),
                new OutlineSymbol(kept, true, false),
                new OutlineSymbol(added, true, true));
    }

    @Test
    void symbolReadsNeedAWorkingCopyOfAGraphTaskAndAnInstalledHelper()
    {
        CodeSymbolResult found = CodeSymbolResult.ready(ImmutableList.of(symbol("simple", "method", TEST_PATH, 1, 1)));
        codeGraph.symbols = found;
        assertThat(service.symbols(REVIEW_ID, "simple", 20)).isEqualTo(found);
        assertThat(codeGraph.symbolReads).containsExactly("search:simple:20");
        codeGraph.symbolReads.clear();

        when(reviews.task(REVIEW_ID)).thenReturn(task(false));
        assertThat(service.files(REVIEW_ID).codeGraph()).isEqualTo(CodeGraphState.OFF);
        assertThat(service.symbols(REVIEW_ID, "simple", 20)).isEqualTo(CodeSymbolResult.UNAVAILABLE);
        assertThat(service.outline(REVIEW_ID, TEST_PATH)).isEqualTo(new CodeOutline(CodeSymbolResult.Status.UNAVAILABLE, ImmutableList.of()));

        AgentTask completed = openTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty()), NOW, NOW.plusSeconds(60));
        when(reviews.task(REVIEW_ID)).thenReturn(completed.withWorktreeRemovedAt(NOW));
        assertThat(service.files(REVIEW_ID).codeGraph()).isEqualTo(CodeGraphState.UNAVAILABLE);
        assertThat(service.symbols(REVIEW_ID, "simple", 20)).isEqualTo(CodeSymbolResult.UNAVAILABLE);
        assertThat(service.outline(REVIEW_ID, TEST_PATH).status()).isEqualTo(CodeSymbolResult.Status.UNAVAILABLE);

        when(reviews.task(REVIEW_ID)).thenReturn(openTask());
        codeGraph.available = false;
        assertThat(service.files(REVIEW_ID).codeGraph()).isEqualTo(CodeGraphState.UNAVAILABLE);
        assertThat(service.symbols(REVIEW_ID, "simple", 20)).isEqualTo(CodeSymbolResult.UNAVAILABLE);
        assertThat(service.outline(REVIEW_ID, TEST_PATH).status()).isEqualTo(CodeSymbolResult.Status.UNAVAILABLE);
        assertThat(codeGraph.symbolReads).isEmpty();
    }

    @Test
    void aDevelopmentTaskReadsAndEditsAnyFileOfItsWorkingCopyUnderItsClaim()
            throws IOException
    {
        CodeFiles files = service.files(taskHost());
        assertThat(files.source()).isEqualTo(CodeSource.WORKING_COPY);
        assertThat(files.revision()).isEqualTo(head);
        assertThat(files.files()).contains(new CodeFileEntry(MAIN_PATH, true, false, false, false), new CodeFileEntry(ADDED_PATH, true, true, false, false),
                new CodeFileEntry(TEST_PATH, false, false, false, false));

        CodeFile production = service.file(taskHost(), MAIN_PATH);
        assertThat(production.editable()).isTrue();
        assertThat(production.prPatch()).contains("@@ -1 +1 @@\n-old\n+production source");
        assertThat(service.file(taskHost(), "README.md").editable()).isTrue();

        CodeFile saved = service.save(taskHost(), MAIN_PATH, "edited production\n", production.contentHash().orElseThrow());
        assertThat(checkout.resolve(MAIN_PATH)).hasContent("edited production\n");
        assertThat(saved.content()).contains("edited production\n");
        assertThat(saved.editable()).isTrue();
        verify(turns).synchronizeWorktree(eq(worktree.taskId()), anyLong(), any());
        assertThatThrownBy(() -> service.save(taskHost(), MAIN_PATH, "stale\n", production.contentHash().orElseThrow()))
                .isInstanceOfSatisfying(ReviewFileConflictException.class, conflict -> assertThat(conflict.currentContent()).contains("edited production\n"));

        // Git metadata, links anywhere in the chain and new files stay refused.
        String hash = Hashing.sha256().hashString("test source\n", UTF_8).toString();
        assertThatThrownBy(() -> service.save(taskHost(), ".git/config", "edited\n", hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save(taskHost(), "src/main/java/New.java", "edited\n", hash)).isInstanceOf(ReviewNotFoundException.class);
        assertThat(checkout.resolve("src/main/java/New.java")).doesNotExist();
        Files.createSymbolicLink(checkout.resolve("src/main/linked"), checkout.resolve("src/test/java"));
        assertThat(service.file(taskHost(), "src/main/linked/TestCode.java").readOnlyReason()).contains("Symbolic links are read-only here");
        assertThatThrownBy(() -> service.save(taskHost(), "src/main/linked/TestCode.java", "edited\n", hash))
                .isInstanceOf(ReviewConflictException.class).hasMessage("Symbolic links are read-only here");
        assertThat(checkout.resolve(TEST_PATH)).hasContent("test source\n");
    }

    @Test
    void aDevelopmentTaskSaveRefusesWhileTheTaskIsBusy()
    {
        when(tasks.getInteractive(WORKSPACE_ID, worktree.taskId())).thenReturn(developmentTask().claimWorktreeMutation(WorktreeMutationClaim.localRun("run-1", NOW), NOW));
        String hash = Hashing.sha256().hashString("production source\n", UTF_8).toString();

        assertThatThrownBy(() -> service.save(taskHost(), MAIN_PATH, "edited\n", hash)).isInstanceOf(ReviewConflictException.class).hasMessage("A run is using this working copy");

        when(tasks.getInteractive(WORKSPACE_ID, worktree.taskId())).thenReturn(developmentTask().withDevelopmentContinuation(true, NOW));
        assertThatThrownBy(() -> service.save(taskHost(), MAIN_PATH, "edited\n", hash)).isInstanceOf(ReviewConflictException.class).hasMessage("Development automation is working");
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

        assertThat(service.file(taskHost(), MAIN_PATH).lineChanges()).containsExactly(
                new LineChange(LineChange.Kind.MODIFIED, 2, 1),
                new LineChange(LineChange.Kind.ADDED, 4, 1));

        // Without a known or fetched base branch the marks are simply absent.
        when(pullRequests.findPullRequestDetail(REPOSITORY.fullName(), 7)).thenReturn(Optional.of(pullRequestDetail("release")));
        assertThat(service.file(taskHost(), MAIN_PATH).lineChanges()).isEmpty();
        when(pullRequests.findPullRequestDetail(REPOSITORY.fullName(), 7)).thenReturn(Optional.empty());
        assertThat(service.file(taskHost(), MAIN_PATH).lineChanges()).isEmpty();

        // A diff never stored reads the files without pull-request marks.
        when(pullRequests.getPullRequestDiffFiles(REPOSITORY.fullName(), 7, null, null)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "not refreshed"));
        assertThat(service.files(taskHost()).files()).noneMatch(CodeFileEntry::changedInPr);
        assertThat(service.file(taskHost(), MAIN_PATH).prPatch()).isEmpty();
    }

    @Test
    void aDevelopmentTaskIsReadOnlyOnceEndedAndRefusedWithoutItsPullRequestOrWorktree()
    {
        assertThat(service.terminalDirectory(taskHost())).isEqualTo(checkout);

        AgentTask completed = developmentTask().complete(new TaskSummary("done", Optional.empty(), AgentMetrics.empty(), Optional.empty()), NOW, NOW.plusSeconds(60));
        when(tasks.getInteractive(WORKSPACE_ID, worktree.taskId())).thenReturn(completed);
        assertThat(service.file(taskHost(), MAIN_PATH).readOnlyReason()).contains("This task has ended — read-only");
        assertThatThrownBy(() -> service.terminalDirectory(taskHost())).isInstanceOf(ReviewConflictException.class).hasMessage("The task has ended");

        when(tasks.getInteractive(WORKSPACE_ID, worktree.taskId())).thenReturn(completed.withWorktreeRemovedAt(NOW));
        assertThatThrownBy(() -> service.files(taskHost())).isInstanceOf(ReviewConflictException.class).hasMessage("The task's worktree was removed");
        assertThatThrownBy(() -> service.terminalDirectory(taskHost())).isInstanceOf(ReviewConflictException.class).hasMessage("The task's worktree was removed");

        when(tasks.getInteractive(WORKSPACE_ID, worktree.taskId())).thenReturn(developmentTask());
        when(pullRequestOwnership.owns(any(), eq(REPOSITORY.fullName()), eq(7), eq(false))).thenReturn(false);
        assertThatThrownBy(() -> service.files(taskHost())).isInstanceOf(ReviewNotFoundException.class).hasMessageContaining("does not belong to this task");
        assertThatThrownBy(() -> service.file(taskHost(), MAIN_PATH)).isInstanceOf(ReviewNotFoundException.class);
        assertThatThrownBy(() -> service.terminalDirectory(taskHost())).isInstanceOf(ReviewNotFoundException.class);
        assertThatThrownBy(() -> service.save(taskHost(), MAIN_PATH, "edited\n", "hash")).isInstanceOf(ReviewNotFoundException.class);
        verify(turns, never()).synchronizeWorktree(any(), anyLong(), any());
    }

    private TaskCodeHost taskHost()
    {
        return new TaskCodeHost(WORKSPACE_ID, worktree.taskId().toString(), REPOSITORY.fullName(), 7);
    }

    private AgentTask developmentTask()
    {
        return AgentTask.created(worktree.taskId(), true, Optional.empty(), "Develop", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), true, NOW);
    }

    private static PullRequestDetail pullRequestDetail(String baseRef)
    {
        return new PullRequestDetail(1, REPOSITORY.fullName(), 7, "Task", "https://github.com/owner/repo/pull/7", "open", false, true, "clean", "owner", null, NOW, NOW, null,
                baseRef, "bytequay/task", "", ImmutableList.of(), ImmutableList.of(), null, ImmutableList.of(), 1, 1, 1, 0, PullRequestDetail.CiStatus.PASSING,
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true);
    }

    private static CodeSymbol symbol(String name, String kind, String path, int startLine, int endLine)
    {
        return new CodeSymbol(name, kind, Optional.of("demo::" + name), path, startLine, 4, endLine, Optional.empty());
    }

    private static void assertUnavailable(CodeFile file, String reason)
    {
        assertThat(file.unavailableReason()).contains(reason);
        assertThat(file.content()).isEmpty();
        assertThat(file.contentHash()).isEmpty();
    }

    private AgentTask openTask()
    {
        return task(true);
    }

    private AgentTask task(boolean codegraph)
    {
        AgentTaskDefaults defaults = AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO);
        return AgentTask.created(worktree.taskId(), false, Optional.empty(), "Review", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree, defaults, codegraph, NOW);
    }

    private AgentReview review(AgentReview.State state)
    {
        return new AgentReview(REVIEW_ID, WORKSPACE_ID, REPOSITORY.fullName(), 7, OptionalLong.empty(), worktree.taskId().toString(), state, false,
                Optional.empty(), Optional.empty(), 0, NOW, NOW, state == AgentReview.State.CLOSED ? Optional.of(NOW) : Optional.empty());
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
        return new ReviewRun("run-" + headSha, REVIEW_ID, 1, ReviewRun.Scope.FULL, "main", mergeBaseSha, mergeBaseSha, headSha, Optional.empty(), ImmutableList.of(), diff,
                ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }
}
