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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestInvestigationReviewContext
{
    @TempDir
    private Path root;

    @Test
    void validationCountsLinesBeyondThePromptTruncationLimit()
            throws Exception
    {
        String path = "src/Large.java";
        String content = IntStream.rangeClosed(1, 5_000)
                .mapToObj(line -> "line " + line + " " + "x".repeat(30))
                .collect(Collectors.joining("\n"));
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve(path), content);
        InvestigationReviewContext context = new InvestigationReviewContext(
                mock(PRService.class), mock(PullRequestService.class),
                mock(TaskStore.class), mock(WatchedRepoStore.class), mock(GitRunner.class));
        PR pr = PR.createExternal(
                "pr-1", "acme/widget", 1, "https://example.test/1", "octocat",
                "feature", "main", "Large file", "", PR.STATUS_REMOTE_OPEN,
                Instant.EPOCH, null, null);
        InvestigationReviewContext.Snapshot snapshot = new InvestigationReviewContext.Snapshot(
                pr, "base", "head", "", List.of(), root);

        assertThat(context.readFile(snapshot, path)).contains("file truncated");
        assertThat(context.fileLineCount(snapshot, path)).isEqualTo(5_000);
    }

    @Test
    void usesAWatchedCloneOnlyWhenItAlreadyContainsTheReviewedSha()
            throws Exception
    {
        PRService prs = mock(PRService.class);
        PullRequestService pullRequests = mock(PullRequestService.class);
        TaskStore tasks = mock(TaskStore.class);
        WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
        GitRunner git = mock(GitRunner.class);
        PR pr = PR.createExternal(
                "pr-2", "acme/widget", 2, "https://example.test/2", "octocat",
                "feature", "main", "Use local context", "", PR.STATUS_REMOTE_OPEN,
                Instant.EPOCH, null, null);
        when(prs.commits(pr.id())).thenReturn(List.of(
                new PRCommit("c1", pr.id(), "base", "base", 0, 0, Instant.EPOCH, Instant.EPOCH),
                new PRCommit("c2", pr.id(), "head", "head", 1, 0, Instant.EPOCH, Instant.EPOCH)));
        when(pullRequests.getPullRequestDiffFiles(pr.repo(), pr.remotePrNumber()))
                .thenReturn(List.of());
        when(watchedRepos.find("acme", "widget")).thenReturn(Optional.of(
                new WatchedRepo(1, "acme", "widget", 0, root.toString(), null, null)));
        when(git.refExists(root, "head")).thenReturn(true);
        when(git.fileAtRef(root, "head", "src/Exact.java")).thenReturn("exact reviewed source");
        InvestigationReviewContext context = new InvestigationReviewContext(
                prs, pullRequests, tasks, watchedRepos, git);

        InvestigationReviewContext.Snapshot snapshot = context.load(pr);

        assertThat(snapshot.localRoot()).isNull();
        assertThat(snapshot.repositoryRoot()).isEqualTo(root);
        assertThat(snapshot.capabilities().sourceMode()).isEqualTo("local-source");
        assertThat(context.readFile(snapshot, "src/Exact.java")).isEqualTo("exact reviewed source");
    }

    @Test
    void remoteOnlyReviewNeverUsesAnOtherwiseAvailableWatchedClone()
    {
        PRService prs = mock(PRService.class);
        PullRequestService pullRequests = mock(PullRequestService.class);
        TaskStore tasks = mock(TaskStore.class);
        WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
        GitRunner git = mock(GitRunner.class);
        PR pr = PR.createExternal(
                "pr-remote", "acme/widget", 4, "https://example.test/4", "octocat",
                "feature", "main", "Remote review", "", PR.STATUS_REMOTE_OPEN,
                Instant.EPOCH, null, null);
        when(prs.commits(pr.id())).thenReturn(List.of(
                new PRCommit("c1", pr.id(), "base", "base", 0, 0, Instant.EPOCH, Instant.EPOCH),
                new PRCommit("c2", pr.id(), "head", "head", 1, 0, Instant.EPOCH, Instant.EPOCH)));
        when(pullRequests.getPullRequestDiffFiles(pr.repo(), pr.remotePrNumber())).thenReturn(List.of());
        when(watchedRepos.find("acme", "widget")).thenReturn(Optional.of(
                new WatchedRepo(1, "acme", "widget", 0, root.toString(), null, null)));
        InvestigationReviewContext context = new InvestigationReviewContext(
                prs, pullRequests, tasks, watchedRepos, git);

        InvestigationReviewContext.Snapshot snapshot = context.load(pr, false);

        assertThat(snapshot.repositoryRoot()).isNull();
        assertThat(snapshot.capabilities().sourceMode()).isEqualTo("remote-only");
    }

    @Test
    void readsATaskBackedRemotePrFromTheReviewedShaInsteadOfTheWorktree()
            throws Exception
    {
        PRService prs = mock(PRService.class);
        PullRequestService pullRequests = mock(PullRequestService.class);
        TaskStore tasks = mock(TaskStore.class);
        WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
        GitRunner git = mock(GitRunner.class);
        Task task = mock(Task.class);
        PR pr = PR.create("pr-task", "task-1", "feature", "main", "Task PR", "", Instant.EPOCH)
                .withRemote("acme/widget", 3, "https://example.test/3", Instant.EPOCH)
                .withStatus(PR.STATUS_REMOTE_OPEN, Instant.EPOCH);
        when(prs.commits(pr.id())).thenReturn(List.of(
                new PRCommit("c1", pr.id(), "base", "base", 0, 0, Instant.EPOCH, Instant.EPOCH),
                new PRCommit("c2", pr.id(), "head", "head", 1, 0, Instant.EPOCH, Instant.EPOCH)));
        when(pullRequests.getPullRequestDiffFiles(pr.repo(), pr.remotePrNumber()))
                .thenReturn(List.of());
        when(tasks.findTaskById("task-1")).thenReturn(Optional.of(task));
        when(task.worktreePath()).thenReturn(root.toString());
        when(git.refExists(root, "head")).thenReturn(true);
        when(git.fileAtRef(root, "head", "src/Exact.java")).thenReturn("reviewed, not working tree");
        InvestigationReviewContext context = new InvestigationReviewContext(
                prs, pullRequests, tasks, watchedRepos, git);

        InvestigationReviewContext.Snapshot snapshot = context.load(pr);

        assertThat(snapshot.localRoot()).isNull();
        assertThat(snapshot.repositoryRoot()).isEqualTo(root);
        assertThat(context.readFile(snapshot, "src/Exact.java"))
                .isEqualTo("reviewed, not working tree");
    }
}
