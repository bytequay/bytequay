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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.PR;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.localpr.PRSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestUpstreamCherryPickService
{
    @Test
    @SuppressWarnings("unchecked")
    void appliesEverySelectedCommitAndRecordsProvenanceWithCherryPickX(@TempDir Path root)
            throws Exception
    {
        Path target = Files.createDirectory(root.resolve("target"));
        Path upstream = Files.createDirectory(root.resolve("upstream"));
        initialise(target);
        initialise(upstream);
        commit(target, "base.txt", "base", "Fork base");
        commit(upstream, "one.txt", "one", "Part one (#101)");
        String first = output(upstream, "rev-parse", "HEAD");
        commit(upstream, "two.txt", "two", "Part two (#101)");
        String second = output(upstream, "rev-parse", "HEAD");

        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
        WorkspaceRepositoryResolver.RepositoryIdentity targetIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "fork", "acme/fork", "main");
        WorkspaceRepositoryResolver.RepositoryIdentity upstreamIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "upstream", "acme/upstream", "main");
        WorkspaceRelationService.WorkspaceRelationDto relationDto =
                new WorkspaceRelationService.WorkspaceRelationDto(
                        "fork-ws", "upstream-ws", "Upstream", "acme/upstream",
                        true, true, false, false, null, 15, 2);
        when(relations.requireResolved("fork-ws"))
                .thenReturn(new WorkspaceRelationService.ResolvedRelation(
                        relationDto, targetIdentity, upstreamIdentity, target, upstream));
        when(relations.defaultBranch(upstreamIdentity, upstream)).thenReturn("main");
        when(relations.defaultBranch(targetIdentity, target)).thenReturn("main");
        when(relations.resolveFetchedRemoteRef(upstream, "main")).thenReturn("main");
        when(relations.resolveFetchedRemoteRef(target, "main")).thenReturn("main");
        GitRunner git = new GitRunner();
        UpstreamCherryPickService service = new UpstreamCherryPickService(
                jdbc, new ObjectMapper(), relations, git, mock(PatResolver.class),
                mock(PullRequestRepository.class), mock(PRSyncService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                new SyncRunStream());

        UpstreamCherryPickService.UpstreamCherryPickJobDto started = service.enqueue(
                "fork-ws",
                new UpstreamCherryPickService.StartRequest(
                        "main", "same-pr-pick", List.of(first, second),
                        null, null, null, null, null, false, false, null));
        UpstreamCherryPickService.UpstreamCherryPickJobDto completed = awaitStatus(
                service, "fork-ws", started.jobId(), Set.of("COMPLETED", "FAILED"));

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.appliedCount()).isEqualTo(2);
        assertThat(completed.skippedCount()).isZero();
        // Provenance is now git's own `-x` line rather than a trailer.
        assertThat(output(target, "log", "--format=%B", "main..same-pr-pick"))
                .contains("(cherry picked from commit " + first + ")")
                .contains("(cherry picked from commit " + second + ")");
        assertThat(output(
                target, "rev-list", "--count", "main..same-pr-pick"))
                .isEqualTo("2");
        assertThat(output(
                target, "log", "--reverse", "--format=%s", "main..same-pr-pick")
                .lines().toList())
                .containsExactly("Part one (#101)", "Part two (#101)");

        // The run view reads the same job through its own payload: the queue
        // with a state per commit, and the command log the picks wrote.
        UpstreamCherryPickService.UpstreamCherryPickRunDto run =
                service.run("fork-ws", started.jobId(), 100);
        assertThat(run.commits())
                .extracting(
                        UpstreamCherryPickService.UpstreamCherryPickCommitDto::subject,
                        UpstreamCherryPickService.UpstreamCherryPickCommitDto::state)
                .containsExactly(
                        tuple("Part one (#101)", "applied"),
                        tuple("Part two (#101)", "applied"));
        assertThat(run.events())
                .extracting(UpstreamCherryPickService.UpstreamCherryPickEventDto::kind)
                .startsWith("start")
                .contains("command")
                .endsWith("done");
        List<UpstreamCherryPickService.UpstreamCherryPickEventDto> commands =
                run.events().stream().filter(event -> "command".equals(event.kind())).toList();
        assertThat(commands).hasSize(2);
        assertThat(commands)
                .allSatisfy(event -> {
                    assertThat(event.title()).startsWith("git cherry-pick -x ");
                    assertThat(event.exitCode()).isZero();
                    assertThat(event.durationMs()).isNotNull();
                    // git's own summary line, so the log can be judged without
                    // leaving the app.
                    assertThat(event.detail()).contains("1 file changed");
                });
        assertThat(commands)
                .extracting(UpstreamCherryPickService.UpstreamCherryPickEventDto::pickIndex)
                .containsExactly(0, 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRequestedParkResumesFromTheNextCommitAndKeepsItsLog(@TempDir Path root)
            throws Exception
    {
        Path target = Files.createDirectory(root.resolve("target"));
        Path upstream = Files.createDirectory(root.resolve("upstream"));
        Path worktree = Files.createDirectory(root.resolve("worktree"));
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        jdbc.update("""
                INSERT INTO upstream_cherry_pick_job (
                    id, workspace_id, upstream_workspace_id, status,
                    source_branch, source_ref, base_branch, base_ref,
                    result_branch, commit_specs_json, applied_shas_json,
                    skipped_shas_json, next_commit_index,
                    conflict_paths_json, worktree_path,
                    open_draft_pr, create_harness_watch, budget_milli_usd,
                    pause_requested, error_message, created_at_ms, updated_at_ms)
                VALUES ('job-1', 'fork-ws', 'upstream-ws', 'PAUSED_CONFLICT',
                    'main', 'origin/main', 'main', 'base-sha',
                    'pick-release',
                    '[{"sha":"commit-1","subject":"First"},'
                    || '{"sha":"commit-2","subject":"Second"}]',
                    '["commit-1"]', '[]', 1, '[]', ?,
                    0, 0, 5000, 1, 'paused at your request', 1, 1)
                """, worktree.toString());

        WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
        GitRunner git = mock(GitRunner.class);
        WorkspaceRepositoryResolver.RepositoryIdentity targetIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "fork", "acme/fork", "main");
        WorkspaceRepositoryResolver.RepositoryIdentity upstreamIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "upstream", "acme/upstream", "main");
        WorkspaceRelationService.WorkspaceRelationDto relationDto =
                new WorkspaceRelationService.WorkspaceRelationDto(
                        "fork-ws", "upstream-ws", "Upstream", "acme/upstream",
                        true, true, false, false, null, 15, 2);
        WorkspaceRelationService.ResolvedRelation resolved =
                new WorkspaceRelationService.ResolvedRelation(
                        relationDto, targetIdentity, upstreamIdentity, target, upstream);
        when(relations.requireResolved("fork-ws")).thenReturn(resolved);
        when(git.cherryPickInProgress(worktree)).thenReturn(false);
        when(git.listCommits(worktree, "base-sha..HEAD", 3))
                .thenReturn(List.of(new GitRunner.CommitEntry(
                        "picked-head", "picked", "Test", "test@example.com",
                        "2026-08-05T00:00:00Z", "First")));
        when(relations.pickedCommitSubjects(resolved, "base-sha")).thenReturn(Set.of());
        when(git.cherryPick(worktree, List.of("commit-2"), true))
                .thenReturn(new GitRunner.CherryPickOutcome(
                        true, 1, null, List.of(),
                        "[pick-release ab12cd3] Second\n 1 file changed, 2 insertions(+)"));
        UpstreamCherryPickService service = new UpstreamCherryPickService(
                jdbc, new ObjectMapper(), relations, git, mock(PatResolver.class),
                mock(PullRequestRepository.class), mock(PRSyncService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                new SyncRunStream());

        service.guide("fork-ws", "job-1", "prefer our fork's config names");
        service.resume("fork-ws", "job-1");
        UpstreamCherryPickService.UpstreamCherryPickJobDto completed = awaitTerminal(service);

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.appliedCount()).isEqualTo(2);
        assertThat(completed.pauseRequested()).isFalse();
        UpstreamCherryPickService.UpstreamCherryPickRunDto run =
                service.run("fork-ws", "job-1", 100);
        assertThat(run.events())
                .extracting(UpstreamCherryPickService.UpstreamCherryPickEventDto::kind)
                .containsSubsequence("guidance", "note", "command", "done");
        assertThat(run.events())
                .filteredOn(event -> "guidance".equals(event.kind()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.title()).isEqualTo("prefer our fork's config names");
                    assertThat(event.pickIndex()).isEqualTo(1);
                });
        assertThat(run.commits())
                .extracting(UpstreamCherryPickService.UpstreamCherryPickCommitDto::state)
                .containsExactly("applied", "applied");
    }

    @Test
    void allowsOnlyOneLiveOrPausedJobPerWorkspace(@TempDir Path root)
    {
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        insertJob(jdbc, "job-1", "fork-ws", "PAUSED_CONFLICT", root.resolve("one"));

        assertThatThrownBy(() -> insertJob(
                jdbc, "job-2", "fork-ws", "QUEUED", root.resolve("two")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("UNIQUE constraint failed");

        insertJob(jdbc, "job-3", "other-ws", "RUNNING", root.resolve("three"));
        jdbc.update("UPDATE upstream_cherry_pick_job SET status = 'COMPLETED' WHERE id = 'job-1'");
        insertJob(jdbc, "job-4", "fork-ws", "QUEUED", root.resolve("four"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM upstream_cherry_pick_job", Integer.class))
                .isEqualTo(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsDurableJobsNewestFirstForDialogRecovery(@TempDir Path root)
            throws Exception
    {
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        insertJob(jdbc, "older", "fork-ws", "COMPLETED", root.resolve("older"));
        insertJob(jdbc, "newer", "fork-ws", "PAUSED_CONFLICT", root.resolve("newer"));
        insertJob(jdbc, "other", "other-ws", "COMPLETED", root.resolve("other"));
        jdbc.update("UPDATE upstream_cherry_pick_job SET created_at_ms = 10 WHERE id = 'older'");
        jdbc.update("UPDATE upstream_cherry_pick_job SET created_at_ms = 20 WHERE id = 'newer'");
        UpstreamCherryPickService service = new UpstreamCherryPickService(
                jdbc, new ObjectMapper(), mock(WorkspaceRelationService.class),
                mock(GitRunner.class), mock(PatResolver.class),
                mock(PullRequestRepository.class), mock(PRSyncService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                new SyncRunStream());

        assertThat(service.list("fork-ws", 20))
                .extracting(UpstreamCherryPickService.UpstreamCherryPickJobDto::jobId)
                .containsExactly("newer", "older");
        assertThat(service.list("fork-ws", 1))
                .extracting(UpstreamCherryPickService.UpstreamCherryPickJobDto::jobId)
                .containsExactly("newer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsAnExistingRemoteResultBranchAfterFetchingBothRepositories(
            @TempDir Path root)
            throws Exception
    {
        Path target = Files.createDirectory(root.resolve("target"));
        Path upstream = Files.createDirectory(root.resolve("upstream"));
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
        GitRunner git = mock(GitRunner.class);
        WorkspaceRepositoryResolver.RepositoryIdentity targetIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "fork", "acme/fork", "main");
        WorkspaceRepositoryResolver.RepositoryIdentity upstreamIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "upstream", "acme/upstream", "main");
        WorkspaceRelationService.WorkspaceRelationDto relationDto =
                new WorkspaceRelationService.WorkspaceRelationDto(
                        "fork-ws", "upstream-ws", "Upstream", "acme/upstream",
                        true, true, false, false, null, 15, 1);
        when(relations.requireResolved("fork-ws")).thenReturn(
                new WorkspaceRelationService.ResolvedRelation(
                        relationDto, targetIdentity, upstreamIdentity, target, upstream));
        when(relations.defaultBranch(upstreamIdentity, upstream)).thenReturn("main");
        when(relations.defaultBranch(targetIdentity, target)).thenReturn("main");
        when(relations.resolveFetchedRemoteRef(upstream, "main"))
                .thenReturn("origin/main");
        when(relations.resolveFetchedRemoteRef(target, "main"))
                .thenReturn("origin/main");
        when(git.isValidBranchName("release-pick")).thenReturn(true);
        when(git.resolveCommitSha(target, "origin/main"))
                .thenReturn(Optional.of("base-sha"));
        when(git.refExists(target, "release-pick")).thenReturn(false);
        when(git.refExists(target, "origin/release-pick")).thenReturn(true);
        UpstreamCherryPickService service = new UpstreamCherryPickService(
                jdbc, new ObjectMapper(), relations, git, mock(PatResolver.class),
                mock(PullRequestRepository.class), mock(PRSyncService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                new SyncRunStream());

        assertThatThrownBy(() -> service.enqueue(
                "fork-ws",
                new UpstreamCherryPickService.StartRequest(
                        null, "release-pick", List.of("commit-1"),
                        null, null, null, null, null, false, false, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getReason()).contains("already exists");
                });

        verify(git).fetch(upstream);
        verify(git).fetch(target);
        verify(git, never()).listDecoratedCommits(any(), any(), eq(5_000));
    }

    @Test
    @SuppressWarnings("unchecked")
    void restartRepairsACherryPickThatCompletedBeforeProgressWasPersisted(
            @TempDir Path root)
            throws Exception
    {
        Path target = Files.createDirectory(root.resolve("target"));
        Path upstream = Files.createDirectory(root.resolve("upstream"));
        Path worktree = Files.createDirectory(root.resolve("worktree"));
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        jdbc.update("""
                INSERT INTO upstream_cherry_pick_job (
                    id, workspace_id, upstream_workspace_id, status,
                    source_branch, source_ref, base_branch, base_ref,
                    result_branch, commit_specs_json, applied_shas_json,
                    skipped_shas_json, next_commit_index,
                    conflict_paths_json, worktree_path,
                    open_draft_pr, create_harness_watch, budget_milli_usd,
                    created_at_ms, updated_at_ms)
                VALUES ('job-1', 'fork-ws', 'upstream-ws', 'RUNNING',
                    'main', 'source-sha', 'main', 'base-sha',
                    'pick-release',
                    '[{"sha":"commit-1","subject":"Feature (#101)"}]',
                    '[]', '[]', 0, '[]', ?, 0, 0, 5000, 1, 1)
                """, worktree.toString());

        WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
        GitRunner git = mock(GitRunner.class);
        PatResolver pats = mock(PatResolver.class);
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        ObjectProvider<HarnessWatchHandoff> handoff = mock(ObjectProvider.class);
        WorkspaceRelationService.WorkspaceRelationDto relationDto =
                new WorkspaceRelationService.WorkspaceRelationDto(
                        "fork-ws", "upstream-ws", "Upstream", "acme/upstream",
                        true, true, false, false, null, 15, 1);
        WorkspaceRepositoryResolver.RepositoryIdentity targetIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "fork", "acme/fork", "main");
        WorkspaceRepositoryResolver.RepositoryIdentity upstreamIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "upstream", "acme/upstream", "main");
        when(relations.requireResolved("fork-ws"))
                .thenReturn(new WorkspaceRelationService.ResolvedRelation(
                        relationDto, targetIdentity, upstreamIdentity, target, upstream));
        when(git.cherryPickInProgress(worktree)).thenReturn(false);
        when(git.listCommits(worktree, "base-sha..HEAD", 2))
                .thenReturn(List.of(new GitRunner.CommitEntry(
                        "new-head", "new-head", "Test", "test@example.com",
                        "2026-07-24T00:00:00Z", "Feature (#101)")));
        when(git.listCommits(target, "base-sha", 5_000)).thenReturn(List.of());

        UpstreamCherryPickService service = new UpstreamCherryPickService(
                jdbc,
                new ObjectMapper(),
                relations,
                git,
                pats,
                pullRequests,
                mock(PRSyncService.class),
                handoff,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new SyncRunStream());
        service.recover();

        UpstreamCherryPickService.UpstreamCherryPickJobDto job =
                awaitTerminal(service);
        assertThat(job.status()).isEqualTo("COMPLETED");
        assertThat(job.appliedCount()).isEqualTo(1);
        verify(git, never()).cherryPick(eq(worktree), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void retriesAFailedJobFromItsDurableProgressAndRejectsInvalidStates(
            @TempDir Path root)
            throws Exception
    {
        Path target = Files.createDirectory(root.resolve("target"));
        Path upstream = Files.createDirectory(root.resolve("upstream"));
        Path worktree = Files.createDirectory(root.resolve("worktree"));
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        jdbc.update("""
                INSERT INTO upstream_cherry_pick_job (
                    id, workspace_id, upstream_workspace_id, status,
                    source_branch, source_ref, base_branch, base_ref,
                    result_branch, commit_specs_json, applied_shas_json,
                    skipped_shas_json, next_commit_index,
                    conflict_paths_json, worktree_path,
                    open_draft_pr, create_harness_watch, budget_milli_usd,
                    error_message, created_at_ms, updated_at_ms)
                VALUES ('job-1', 'fork-ws', 'upstream-ws', 'FAILED',
                    'main', 'origin/main', 'main', 'base-sha',
                    'pick-release',
                    '[{"sha":"commit-1","subject":"First"},'
                    || '{"sha":"commit-2","subject":"Second"}]',
                    '["commit-1"]', '[]', 1, '["stale.txt"]', ?,
                    0, 0, 5000, 'temporary failure', 1, 1)
                """, worktree.toString());

        WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
        GitRunner git = mock(GitRunner.class);
        WorkspaceRepositoryResolver.RepositoryIdentity targetIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "fork", "acme/fork", "main");
        WorkspaceRepositoryResolver.RepositoryIdentity upstreamIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "upstream", "acme/upstream", "main");
        WorkspaceRelationService.WorkspaceRelationDto relationDto =
                new WorkspaceRelationService.WorkspaceRelationDto(
                        "fork-ws", "upstream-ws", "Upstream", "acme/upstream",
                        true, true, false, false, null, 15, 2);
        WorkspaceRelationService.ResolvedRelation resolved =
                new WorkspaceRelationService.ResolvedRelation(
                        relationDto, targetIdentity, upstreamIdentity, target, upstream);
        when(relations.requireResolved("fork-ws")).thenReturn(resolved);
        when(git.cherryPickInProgress(worktree)).thenReturn(false);
        when(git.listCommits(worktree, "base-sha..HEAD", 3))
                .thenReturn(List.of(new GitRunner.CommitEntry(
                        "picked-head", "picked", "Test", "test@example.com",
                        "2026-07-24T00:00:00Z", "First")));
        List<GitRunner.DecoratedCommitEntry> history = List.of(
                new GitRunner.DecoratedCommitEntry(
                        "commit-2", "commit-2", "Test", "test@example.com",
                        "2026-07-24T00:00:00Z", "Second", List.of()),
                new GitRunner.DecoratedCommitEntry(
                        "commit-1", "commit-1", "Test", "test@example.com",
                        "2026-07-23T00:00:00Z", "First", List.of()));
        when(git.listDecoratedCommits(upstream, "origin/main", 5_000))
                .thenReturn(history);
        // "First" is already on the target branch, so it is skipped by subject.
        when(relations.pickedCommitSubjects(resolved, "base-sha"))
                .thenReturn(Set.of("first"));
        when(git.cherryPick(worktree, List.of("commit-2"), true))
                .thenReturn(new GitRunner.CherryPickOutcome(
                        true, 0, "commit-2", List.of(), null));
        UpstreamCherryPickService service = new UpstreamCherryPickService(
                jdbc, new ObjectMapper(), relations, git, mock(PatResolver.class),
                mock(PullRequestRepository.class), mock(PRSyncService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                new SyncRunStream());

        assertThatThrownBy(() -> service.retry("other-ws", "job-1"))
                .isInstanceOf(NoSuchElementException.class);
        service.retry("fork-ws", "job-1");
        UpstreamCherryPickService.UpstreamCherryPickJobDto completed =
                awaitTerminal(service);

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.appliedCount()).isEqualTo(2);
        assertThat(completed.conflictPaths()).isEmpty();
        verify(git, never()).cherryPick(worktree, List.of("commit-1"), true);
        verify(git).cherryPick(worktree, List.of("commit-2"), true);
        assertThatThrownBy(() -> service.retry("fork-ws", "job-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure ->
                        assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aConflictWithNoRepairAgentIsCommittedThenParked(@TempDir Path root)
            throws Exception
    {
        Path target = Files.createDirectory(root.resolve("target"));
        Path upstream = Files.createDirectory(root.resolve("upstream"));
        Path worktree = Files.createDirectory(root.resolve("worktree"));
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        jdbc.update("""
                INSERT INTO upstream_cherry_pick_job (
                    id, workspace_id, upstream_workspace_id, status,
                    source_branch, source_ref, base_branch, base_ref,
                    result_branch, commit_specs_json, applied_shas_json,
                    skipped_shas_json, next_commit_index,
                    conflict_paths_json, worktree_path,
                    open_draft_pr, create_harness_watch, budget_milli_usd,
                    created_at_ms, updated_at_ms)
                VALUES ('job-1', 'fork-ws', 'upstream-ws', 'QUEUED',
                    'main', 'source-sha', 'main', 'base-sha',
                    'pick-release',
                    '[{"sha":"commit-1","subject":"Feature (#101)"}]',
                    '[]', '[]', 0, '[]', ?, 0, 0, 5000, 1, 1)
                """, worktree.toString());

        WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
        GitRunner git = mock(GitRunner.class);
        PatResolver pats = mock(PatResolver.class);
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        ObjectProvider<HarnessWatchHandoff> handoff = mock(ObjectProvider.class);
        WorkspaceRelationService.WorkspaceRelationDto relationDto =
                new WorkspaceRelationService.WorkspaceRelationDto(
                        "fork-ws", "upstream-ws", "Upstream", "acme/upstream",
                        true, true, false, false, null, 15, 1);
        WorkspaceRepositoryResolver.RepositoryIdentity targetIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "fork", "acme/fork", "main");
        WorkspaceRepositoryResolver.RepositoryIdentity upstreamIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "upstream", "acme/upstream", "main");
        when(relations.requireResolved("fork-ws"))
                .thenReturn(new WorkspaceRelationService.ResolvedRelation(
                        relationDto, targetIdentity, upstreamIdentity, target, upstream));
        when(git.cherryPickInProgress(worktree)).thenReturn(false, true, false, false);
        // git's own resolution is committed, markers and all — but a conflicted
        // pick is never carried unjudged. With no repair agent registered there is
        // nothing to judge it, so the run parks instead of pushing a guess.
        when(git.continueCherryPick(worktree)).thenReturn(
                new GitRunner.CherryPickOutcome(true, 1, "commit-1", List.of(), null));
        when(git.listCommits(worktree, "base-sha..HEAD", 2))
                .thenReturn(
                        List.of(),
                        List.of(new GitRunner.CommitEntry(
                                "picked-head", "picked", "Test", "test@example.com",
                                "2026-07-24T00:00:00Z", "Feature (#101)")));
        when(git.commitDetail(worktree, "HEAD"))
                .thenReturn(Optional.of(new GitRunner.CommitDetailEntry(
                        "picked-head", "Feature (#101)", "")));
        when(git.listCommits(target, "base-sha", 5_000)).thenReturn(List.of());
        when(git.cherryPick(worktree, List.of("commit-1"), true))
                .thenReturn(new GitRunner.CherryPickOutcome(
                        false, 0, "commit-1", List.of("src/Main.java"),
                        "resolve conflicts and continue"));

        UpstreamCherryPickService service = new UpstreamCherryPickService(
                jdbc, new ObjectMapper(), relations, git, pats, pullRequests,
                mock(PRSyncService.class), handoff, mock(ObjectProvider.class),
                mock(ObjectProvider.class), new SyncRunStream());
        service.recover();

        UpstreamCherryPickService.UpstreamCherryPickJobDto job = awaitStatus(
                service, "fork-ws", "job-1", Set.of("COMPLETED", "FAILED", "PAUSED_CONFLICT"));
        assertThat(job.status()).isEqualTo("PAUSED_CONFLICT");
        assertThat(job.errorMessage()).contains("no repair agent");
        // The pick itself did land — git finished it; only the judging is missing.
        assertThat(job.appliedCount()).isEqualTo(1);
        assertThat(job.conflictPaths()).isEmpty();
        verify(git).continueCherryPick(worktree);
        assertThat(service.list("fork-ws", 1))
                .extracting(UpstreamCherryPickService.UpstreamCherryPickJobDto::jobId)
                .containsExactly("job-1");
        // Nothing is published from a parked run.
        verifyNoInteractions(handoff);
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void harnessHandoffCarriesTheSyncedLocalPrId(@TempDir Path root)
            throws Exception
    {
        Path target = Files.createDirectory(root.resolve("target"));
        Path upstream = Files.createDirectory(root.resolve("upstream"));
        Path worktree = Files.createDirectory(root.resolve("worktree"));
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        jdbc.update("""
                INSERT INTO upstream_cherry_pick_job (
                    id, workspace_id, upstream_workspace_id, status,
                    source_branch, source_ref, base_branch, base_ref,
                    result_branch, commit_specs_json, applied_shas_json,
                    skipped_shas_json, next_commit_index,
                    conflict_paths_json, worktree_path,
                    open_draft_pr, create_harness_watch, budget_milli_usd,
                    pr_number, pr_url, created_at_ms, updated_at_ms)
                VALUES ('job-1', 'fork-ws', 'upstream-ws', 'QUEUED',
                    'main', 'source-sha', 'main', 'base-sha',
                    'pick-release', '[]', '[]', '[]', 0, '[]', ?,
                    1, 1, 5000, 123, 'https://example.test/pr/123', 1, 1)
                """, worktree.toString());

        WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
        GitRunner git = mock(GitRunner.class);
        PRSyncService prSync = mock(PRSyncService.class);
        ObjectProvider<HarnessWatchHandoff> provider = mock(ObjectProvider.class);
        HarnessWatchHandoff handoff = mock(HarnessWatchHandoff.class);
        WorkspaceRepositoryResolver.RepositoryIdentity targetIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "fork", "acme/fork", "main");
        WorkspaceRepositoryResolver.RepositoryIdentity upstreamIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "upstream", "acme/upstream", "main");
        WorkspaceRelationService.WorkspaceRelationDto relationDto =
                new WorkspaceRelationService.WorkspaceRelationDto(
                        "fork-ws", "upstream-ws", "Upstream", "acme/upstream",
                        true, true, false, false, null, 15, 0);
        when(relations.requireResolved("fork-ws"))
                .thenReturn(new WorkspaceRelationService.ResolvedRelation(
                        relationDto, targetIdentity, upstreamIdentity, target, upstream));
        when(git.cherryPickInProgress(worktree)).thenReturn(false);
        when(git.listCommits(worktree, "base-sha..HEAD", 1)).thenReturn(List.of());
        when(git.listCommits(target, "base-sha", 5_000)).thenReturn(List.of());
        PR localPr = PR.createExternal(
                "local-pr-1", "acme/fork", 123, "https://example.test/pr/123",
                "octocat", "pick-release", "main", "Release pick", "",
                PR.STATUS_REMOTE_DRAFTED, Instant.now(), null, null);
        when(prSync.syncExternalPR("acme/fork", 123)).thenReturn(Optional.of(localPr));
        when(provider.getIfAvailable()).thenReturn(handoff);
        when(handoff.create(
                "fork-ws", "acme/fork", 123, "local-pr-1", "pick-release",
                worktree.toString(), 5_000L, null)).thenReturn("watch-1");
        UpstreamCherryPickService service = new UpstreamCherryPickService(
                jdbc, new ObjectMapper(), relations, git, mock(PatResolver.class),
                mock(PullRequestRepository.class), prSync, provider,
                mock(ObjectProvider.class), mock(ObjectProvider.class), new SyncRunStream());

        service.recover();
        UpstreamCherryPickService.UpstreamCherryPickJobDto completed =
                awaitTerminal(service);

        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.harnessWatchId()).isEqualTo("watch-1");
        verify(prSync).syncExternalPR("acme/fork", 123);
        verify(handoff).create(
                "fork-ws", "acme/fork", 123, "local-pr-1", "pick-release",
                worktree.toString(), 5_000L, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aConflictedPickIsRepairedByTheAgentBeforeTheNextOneStarts(@TempDir Path root)
            throws Exception
    {
        Conflict setup = conflictingRepositories(root);
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        ConflictRepairAdvisor advisor = mock(ConflictRepairAdvisor.class);
        // The agent owns the repair now: it edits, commits the fixup and
        // validates. The program only reads the verdict off the end.
        when(advisor.repair(any(), any(), any(), any(), any(), anyLong(), any(), any()))
                .thenAnswer(invocation -> {
                    Path worktree = invocation.getArgument(0);
                    String subject = invocation.getArgument(2);
                    Files.writeString(worktree.resolve("conflict.txt"), "fork and upstream\n");
                    run(worktree, "add", "conflict.txt");
                    run(worktree, "commit", "-m", "fixup! " + subject);
                    return new ConflictRepairAdvisor.Outcome(
                            true, true, "kept the fork's line and upstream's change",
                            120, "session-1");
                });
        UpstreamCherryPickService service = service(jdbc, setup, advisor);

        UpstreamCherryPickService.UpstreamCherryPickJobDto started = service.enqueue(
                "fork-ws",
                new UpstreamCherryPickService.StartRequest(
                        "main", "repaired-pick", List.of(setup.upstreamSha()),
                        null, null, null, null, null, false, false, null));
        UpstreamCherryPickService.UpstreamCherryPickJobDto done = awaitStatus(
                service, "fork-ws", started.jobId(), Set.of("COMPLETED", "FAILED", "PAUSED_CONFLICT"));

        assertThat(done.status()).isEqualTo("COMPLETED");
        // The fixup sits beside its pick rather than inside it.
        assertThat(output(setup.target(), "log", "--reverse", "--format=%s",
                "main..repaired-pick").lines().toList())
                .containsExactly("Change the shared line", "fixup! Change the shared line");
        String worktreeFile = Files.readString(
                Path.of(done.worktreePath()).resolve("conflict.txt"));
        assertThat(worktreeFile).isEqualTo("fork and upstream\n").doesNotContain("<<<<<<<");
        // The agent is told which pick its fixup must name.
        verify(advisor).repair(
                any(), any(), eq("Change the shared line"), any(), any(), anyLong(),
                any(), any());

        assertThat(service.run("fork-ws", started.jobId(), 100).events())
                .extracting(UpstreamCherryPickService.UpstreamCherryPickEventDto::kind)
                .containsSubsequence("command", "note", "agent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRepairThatLeavesTheWorktreeDirtyParksRatherThanBreakTheNextPick(@TempDir Path root)
            throws Exception
    {
        Conflict setup = conflictingRepositories(root);
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        ConflictRepairAdvisor advisor = mock(ConflictRepairAdvisor.class);
        // Claims success but leaves uncommitted work behind. A cherry-pick can
        // only be applied to a clean tree, so carrying on would fail later with
        // an error pointing at the wrong commit.
        when(advisor.repair(any(), any(), any(), any(), any(), anyLong(), any(), any()))
                .thenAnswer(invocation -> {
                    Path worktree = invocation.getArgument(0);
                    Files.writeString(worktree.resolve("conflict.txt"), "half done\n");
                    return new ConflictRepairAdvisor.Outcome(
                            true, true, "resolved it", 100, "session-1");
                });
        UpstreamCherryPickService service = service(jdbc, setup, advisor);

        UpstreamCherryPickService.UpstreamCherryPickJobDto started = service.enqueue(
                "fork-ws",
                new UpstreamCherryPickService.StartRequest(
                        "main", "dirty-pick", List.of(setup.upstreamSha()),
                        null, null, null, null, null, false, false, null));
        UpstreamCherryPickService.UpstreamCherryPickJobDto parked = awaitStatus(
                service, "fork-ws", started.jobId(),
                Set.of("COMPLETED", "FAILED", "PAUSED_CONFLICT"));

        assertThat(parked.status()).isEqualTo("PAUSED_CONFLICT");
        assertThat(parked.errorMessage()).contains("uncommitted changes");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAgentsTranscriptIsKeptAsItsOwnLogLine(@TempDir Path root)
            throws Exception
    {
        Conflict setup = conflictingRepositories(root);
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        ConflictRepairAdvisor advisor = mock(ConflictRepairAdvisor.class);
        when(advisor.repair(any(), any(), any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(new ConflictRepairAdvisor.Outcome(
                        false, false, "the repair agent did not run: claude: not found",
                        "{\"type\":\"result\",\"is_error\":true}", 0, null));
        UpstreamCherryPickService service = service(jdbc, setup, advisor);

        UpstreamCherryPickService.UpstreamCherryPickJobDto started = service.enqueue(
                "fork-ws",
                new UpstreamCherryPickService.StartRequest(
                        "main", "logged-pick", List.of(setup.upstreamSha()),
                        null, null, null, null, null, false, false, null));
        awaitStatus(service, "fork-ws", started.jobId(),
                Set.of("COMPLETED", "FAILED", "PAUSED_CONFLICT"));

        // This used to be written by a hand-rolled INSERT that named columns the
        // table does not have; it threw on every run and the failure was caught
        // and logged, so the feature never once worked and nothing said so.
        assertThat(service.run("fork-ws", started.jobId(), 100).events())
                .filteredOn(event -> "agent_log".equals(event.kind()))
                .singleElement()
                .satisfies(event -> assertThat(event.detail()).contains("is_error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAgentThatParksStopsTheRunWithoutPushingAnything(@TempDir Path root)
            throws Exception
    {
        Conflict setup = conflictingRepositories(root);
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        ConflictRepairAdvisor advisor = mock(ConflictRepairAdvisor.class);
        when(advisor.repair(any(), any(), any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(new ConflictRepairAdvisor.Outcome(
                        false, false, "upstream dropped the setter this fork calls",
                        500, "session-1"));
        PullRequestRepository pullRequests = mock(PullRequestRepository.class);
        UpstreamCherryPickService service = service(jdbc, setup, advisor, pullRequests);

        UpstreamCherryPickService.UpstreamCherryPickJobDto started = service.enqueue(
                "fork-ws",
                new UpstreamCherryPickService.StartRequest(
                        "main", "parked-pick", List.of(setup.upstreamSha()),
                        null, null, null, null, null, true, false, null));
        UpstreamCherryPickService.UpstreamCherryPickJobDto parked = awaitStatus(
                service, "fork-ws", started.jobId(),
                Set.of("COMPLETED", "FAILED", "PAUSED_CONFLICT"));

        assertThat(parked.status()).isEqualTo("PAUSED_CONFLICT");
        // The agent's own reason reaches the user, not a generic one.
        assertThat(parked.errorMessage()).contains("upstream dropped the setter");
        // The agent decides when it is stuck; the program does not retry it.
        verify(advisor).repair(any(), any(), any(), any(), any(), anyLong(), isNull(), any());
        verify(pullRequests, never()).createPullRequest(any(), any(), any());
        assertThat(parked.prNumber()).isNull();
        assertThat(service.run("fork-ws", started.jobId(), 100).events())
                .extracting(UpstreamCherryPickService.UpstreamCherryPickEventDto::kind)
                .endsWith("park");
        // The turn is charged to the run's budget whether or not it helped.
        assertThat(parked.spentMilliUsd()).isEqualTo(500);
    }

    @Test
    @SuppressWarnings("unchecked")
    void closingAParkedRunStopsItsWatchRemovesItsWorktreeAndRefusesFurtherActions(
            @TempDir Path root)
            throws Exception
    {
        Path target = Files.createDirectory(root.resolve("target"));
        Path upstream = Files.createDirectory(root.resolve("upstream"));
        Path worktree = Files.createDirectory(root.resolve("worktree"));
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        jdbc.update("""
                INSERT INTO upstream_cherry_pick_job (
                    id, workspace_id, upstream_workspace_id, status,
                    source_branch, source_ref, base_branch, base_ref,
                    result_branch, commit_specs_json, applied_shas_json,
                    skipped_shas_json, next_commit_index,
                    conflict_paths_json, worktree_path,
                    open_draft_pr, create_harness_watch, budget_milli_usd,
                    harness_watch_id, created_at_ms, updated_at_ms)
                VALUES ('job-1', 'fork-ws', 'upstream-ws', 'PAUSED_CONFLICT',
                    'main', 'origin/main', 'main', 'base-sha',
                    'pick-release',
                    '[{"sha":"commit-1","subject":"First"}]',
                    '[]', '[]', 0, '["src/Main.java"]', ?,
                    1, 1, 5000, 'watch-1', 1, 1)
                """, worktree.toString());

        WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
        GitRunner git = mock(GitRunner.class);
        ObjectProvider<HarnessWatchHandoff> provider = mock(ObjectProvider.class);
        HarnessWatchHandoff handoff = mock(HarnessWatchHandoff.class);
        WorkspaceRepositoryResolver.RepositoryIdentity targetIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "fork", "acme/fork", "main");
        WorkspaceRepositoryResolver.RepositoryIdentity upstreamIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "upstream", "acme/upstream", "main");
        WorkspaceRelationService.WorkspaceRelationDto relationDto =
                new WorkspaceRelationService.WorkspaceRelationDto(
                        "fork-ws", "upstream-ws", "Upstream", "acme/upstream",
                        true, true, false, false, null, 15, 1);
        when(relations.requireResolved("fork-ws"))
                .thenReturn(new WorkspaceRelationService.ResolvedRelation(
                        relationDto, targetIdentity, upstreamIdentity, target, upstream));
        when(provider.getIfAvailable()).thenReturn(handoff);
        UpstreamCherryPickService service = new UpstreamCherryPickService(
                jdbc, new ObjectMapper(), relations, git, mock(PatResolver.class),
                mock(PullRequestRepository.class), mock(PRSyncService.class), provider,
                mock(ObjectProvider.class), mock(ObjectProvider.class), new SyncRunStream());

        UpstreamCherryPickService.UpstreamCherryPickJobDto closed =
                service.close("fork-ws", "job-1");

        assertThat(closed.closedAt()).isNotNull();
        // The status column has a CHECK constraint with no closed value in it,
        // so closing must not invent one — closedAt is the terminal marker and
        // the status stays as honest history of how the run ended.
        assertThat(closed.status()).isEqualTo("PAUSED_CONFLICT");
        // The agent side stops with the run, and the isolated worktree goes.
        verify(handoff).stopWatch("fork-ws", "watch-1");
        verify(git).worktreeRemove(target, worktree);
        verify(git).worktreePrune(target);
        // What the run committed is never touched by a close.
        verify(git, never()).deleteBranches(any(), anyList());
        assertThat(service.run("fork-ws", "job-1", 100).events())
                .extracting(UpstreamCherryPickService.UpstreamCherryPickEventDto::kind)
                .contains("closed");

        assertThatThrownBy(() -> service.resume("fork-ws", "job-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(failure.getReason()).contains("closed");
                });
        assertThatThrownBy(() -> service.guide("fork-ws", "job-1", "carry on"))
                .isInstanceOf(ResponseStatusException.class);
        // Closing twice is a no-op rather than a second stop or removal.
        service.close("fork-ws", "job-1");
        verify(handoff).stopWatch("fork-ws", "watch-1");
        // The slot is free again: a closed run no longer blocks the next sync.
        insertJob(jdbc, "job-2", "fork-ws", "QUEUED", root.resolve("next"));
        assertThat(service.list("fork-ws", 10)).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMergedPullRequestClosesTheRunItCameFrom(@TempDir Path root)
            throws Exception
    {
        Path target = Files.createDirectory(root.resolve("target"));
        Path upstream = Files.createDirectory(root.resolve("upstream"));
        Path worktree = Files.createDirectory(root.resolve("worktree"));
        JdbcTemplate jdbc = jdbc(root.resolve("jobs.db"));
        createTable(jdbc);
        jdbc.update("""
                INSERT INTO upstream_cherry_pick_job (
                    id, workspace_id, upstream_workspace_id, status,
                    source_branch, source_ref, base_branch, base_ref,
                    result_branch, commit_specs_json, applied_shas_json,
                    skipped_shas_json, next_commit_index,
                    conflict_paths_json, worktree_path,
                    open_draft_pr, create_harness_watch, budget_milli_usd,
                    pr_number, pr_url, harness_watch_id,
                    created_at_ms, updated_at_ms)
                VALUES ('job-1', 'fork-ws', 'upstream-ws', 'COMPLETED',
                    'main', 'origin/main', 'main', 'base-sha',
                    'pick-release',
                    '[{"sha":"commit-1","subject":"First"}]',
                    '["commit-1"]', '[]', 1, '[]', ?,
                    1, 1, 5000, 123, 'https://example.test/pr/123', 'watch-1', 1, 1)
                """, worktree.toString());

        WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
        GitRunner git = mock(GitRunner.class);
        PRSyncService prSync = mock(PRSyncService.class);
        ObjectProvider<HarnessWatchHandoff> provider = mock(ObjectProvider.class);
        HarnessWatchHandoff handoff = mock(HarnessWatchHandoff.class);
        WorkspaceRepositoryResolver.RepositoryIdentity targetIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "fork", "acme/fork", "main");
        WorkspaceRepositoryResolver.RepositoryIdentity upstreamIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "upstream", "acme/upstream", "main");
        WorkspaceRelationService.WorkspaceRelationDto relationDto =
                new WorkspaceRelationService.WorkspaceRelationDto(
                        "fork-ws", "upstream-ws", "Upstream", "acme/upstream",
                        true, true, false, false, null, 15, 1);
        when(relations.requireResolved("fork-ws"))
                .thenReturn(new WorkspaceRelationService.ResolvedRelation(
                        relationDto, targetIdentity, upstreamIdentity, target, upstream));
        when(provider.getIfAvailable()).thenReturn(handoff);
        UpstreamCherryPickService service = new UpstreamCherryPickService(
                jdbc, new ObjectMapper(), relations, git, mock(PatResolver.class),
                mock(PullRequestRepository.class), prSync, provider,
                mock(ObjectProvider.class), mock(ObjectProvider.class), new SyncRunStream());

        // Still open on the remote: the run keeps its worktree.
        when(prSync.syncExternalPR("acme/fork", 123)).thenReturn(Optional.of(externalPr(
                PR.STATUS_REMOTE_OPEN)));
        service.closeRunsWhosePullRequestEnded();
        assertThat(service.require("fork-ws", "job-1").closedAt()).isNull();
        verify(git, never()).worktreeRemove(any(), any());

        // Merged: nothing is left for the run or its worktree to do.
        when(prSync.syncExternalPR("acme/fork", 123)).thenReturn(Optional.of(externalPr(
                PR.STATUS_MERGED)));
        service.closeRunsWhosePullRequestEnded();

        assertThat(service.require("fork-ws", "job-1").closedAt()).isNotNull();
        verify(handoff).stopWatch("fork-ws", "watch-1");
        verify(git).worktreeRemove(target, worktree);
        assertThat(service.run("fork-ws", "job-1", 100).events())
                .filteredOn(event -> "closed".equals(event.kind()))
                .singleElement()
                .satisfies(event -> assertThat(event.title()).contains("#123 was merged"));

        // A second sweep has nothing left to close.
        service.closeRunsWhosePullRequestEnded();
        verify(handoff).stopWatch("fork-ws", "watch-1");
    }

    private static PR externalPr(String status)
    {
        return PR.createExternal(
                "local-pr-1", "acme/fork", 123, "https://example.test/pr/123",
                "octocat", "pick-release", "main", "Release pick", "",
                status, Instant.now(), null, null);
    }

    private static UpstreamCherryPickService.UpstreamCherryPickJobDto awaitTerminal(
            UpstreamCherryPickService service)
            throws InterruptedException
    {
        return awaitStatus(
                service, "fork-ws", "job-1", Set.of("COMPLETED", "FAILED"));
    }

    private static UpstreamCherryPickService.UpstreamCherryPickJobDto awaitStatus(
            UpstreamCherryPickService service,
            Set<String> statuses)
            throws InterruptedException
    {
        return awaitStatus(service, "fork-ws", "job-1", statuses);
    }

    private static UpstreamCherryPickService.UpstreamCherryPickJobDto awaitStatus(
            UpstreamCherryPickService service,
            String workspaceId,
            String jobId,
            Set<String> statuses)
            throws InterruptedException
    {
        UpstreamCherryPickService.UpstreamCherryPickJobDto job =
                service.require(workspaceId, jobId);
        for (int i = 0; i < 200 && !statuses.contains(job.status()); i++) {
            Thread.sleep(10);
            job = service.require(workspaceId, jobId);
        }
        // The status lands inside the worker, which then runs on for a few
        // instructions. Waiting for the thread too keeps @TempDir from being
        // deleted while something is still writing into it.
        for (int idle = 0; idle < 200 && service.isWorking(jobId); idle++) {
            Thread.sleep(20);
        }
        return job;
    }

    /** Two repositories whose shared file has drifted, so the pick conflicts. */
    private record Conflict(Path target, Path upstream, String upstreamSha) {}

    private static Conflict conflictingRepositories(Path root)
            throws Exception
    {
        Path target = Files.createDirectory(root.resolve("target"));
        Path upstream = Files.createDirectory(root.resolve("upstream"));
        initialise(target);
        initialise(upstream);
        commit(upstream, "conflict.txt", "base\n", "Shared base");
        commit(upstream, "conflict.txt", "upstream\n", "Change the shared line");
        commit(target, "conflict.txt", "fork\n", "Fork's own line");
        return new Conflict(target, upstream, output(upstream, "rev-parse", "HEAD"));
    }

    @SuppressWarnings("unchecked")
    private static UpstreamCherryPickService service(
            JdbcTemplate jdbc,
            Conflict setup,
            ConflictRepairAdvisor advisor)
            throws Exception
    {
        return service(jdbc, setup, advisor, mock(PullRequestRepository.class));
    }

    @SuppressWarnings("unchecked")
    private static UpstreamCherryPickService service(
            JdbcTemplate jdbc,
            Conflict setup,
            ConflictRepairAdvisor advisor,
            PullRequestRepository pullRequests)
            throws Exception
    {
        WorkspaceRelationService relations = mock(WorkspaceRelationService.class);
        WorkspaceRepositoryResolver.RepositoryIdentity targetIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "fork", "acme/fork", "main");
        WorkspaceRepositoryResolver.RepositoryIdentity upstreamIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "upstream", "acme/upstream", "main");
        WorkspaceRelationService.WorkspaceRelationDto relationDto =
                new WorkspaceRelationService.WorkspaceRelationDto(
                        "fork-ws", "upstream-ws", "Upstream", "acme/upstream",
                        true, true, false, false, null, 15, 1);
        when(relations.requireResolved("fork-ws"))
                .thenReturn(new WorkspaceRelationService.ResolvedRelation(
                        relationDto, targetIdentity, upstreamIdentity,
                        setup.target(), setup.upstream()));
        when(relations.defaultBranch(upstreamIdentity, setup.upstream())).thenReturn("main");
        when(relations.defaultBranch(targetIdentity, setup.target())).thenReturn("main");
        when(relations.resolveFetchedRemoteRef(setup.upstream(), "main")).thenReturn("main");
        when(relations.resolveFetchedRemoteRef(setup.target(), "main")).thenReturn("main");
        ObjectProvider<ConflictRepairAdvisor> advisorProvider = mock(ObjectProvider.class);
        when(advisorProvider.getIfAvailable()).thenReturn(advisor);
        return new UpstreamCherryPickService(
                jdbc, new ObjectMapper(), relations, new GitRunner(),
                mock(PatResolver.class), pullRequests, mock(PRSyncService.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), advisorProvider,
                new SyncRunStream());
    }

    private static JdbcTemplate jdbc(Path database)
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + database);
        return new JdbcTemplate(dataSource);
    }

    private static void createTable(JdbcTemplate jdbc)
    {
        jdbc.execute("""
                CREATE TABLE upstream_cherry_pick_job (
                    id TEXT PRIMARY KEY,
                    workspace_id TEXT NOT NULL,
                    upstream_workspace_id TEXT NOT NULL,
                    status TEXT NOT NULL
                        CHECK (status IN ('QUEUED', 'RUNNING', 'PAUSED_CONFLICT',
                            'COMPLETED', 'FAILED')),
                    source_branch TEXT NOT NULL,
                    source_ref TEXT NOT NULL,
                    base_branch TEXT NOT NULL,
                    base_ref TEXT NOT NULL,
                    result_branch TEXT NOT NULL,
                    commit_specs_json TEXT NOT NULL,
                    applied_shas_json TEXT NOT NULL,
                    skipped_shas_json TEXT NOT NULL,
                    next_commit_index INTEGER NOT NULL,
                    conflict_paths_json TEXT NOT NULL,
                    worktree_path TEXT NOT NULL,
                    open_draft_pr INTEGER NOT NULL,
                    create_harness_watch INTEGER NOT NULL,
                    budget_milli_usd INTEGER NOT NULL,
                    pr_number INTEGER,
                    pr_url TEXT,
                    harness_watch_id TEXT,
                    error_message TEXT,
                    pr_description TEXT,
                    skip_filters_json TEXT NOT NULL
                        DEFAULT '{"startsWith":[],"contains":[]}',
                    compile_script TEXT,
                    ci_job_name TEXT,
                    repair_pending INTEGER NOT NULL DEFAULT 0,
                    conflicted_shas_json TEXT NOT NULL DEFAULT '[]',
                    pause_requested INTEGER NOT NULL DEFAULT 0,
                    closed_at_ms INTEGER,
                    local_gate_unavailable INTEGER NOT NULL DEFAULT 0,
                    spent_milli_usd INTEGER NOT NULL DEFAULT 0,
                    agent_session_id TEXT,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL)
                """);
        jdbc.execute("""
                CREATE UNIQUE INDEX idx_upstream_cherry_pick_job_one_live
                ON upstream_cherry_pick_job(workspace_id)
                WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED_CONFLICT')
                  AND closed_at_ms IS NULL
                """);
        jdbc.execute("""
                CREATE TABLE upstream_cherry_pick_event (
                    id TEXT PRIMARY KEY,
                    job_id TEXT NOT NULL,
                    ordinal INTEGER NOT NULL,
                    pick_index INTEGER,
                    kind TEXT NOT NULL,
                    title TEXT NOT NULL,
                    detail TEXT,
                    exit_code INTEGER,
                    duration_ms INTEGER,
                    created_at_ms INTEGER NOT NULL)
                """);
    }

    private static void insertJob(
            JdbcTemplate jdbc,
            String id,
            String workspaceId,
            String status,
            Path worktree)
    {
        jdbc.update("""
                INSERT INTO upstream_cherry_pick_job (
                    id, workspace_id, upstream_workspace_id, status,
                    source_branch, source_ref, base_branch, base_ref,
                    result_branch, commit_specs_json, applied_shas_json,
                    skipped_shas_json, next_commit_index,
                    conflict_paths_json, worktree_path,
                    open_draft_pr, create_harness_watch, budget_milli_usd,
                    created_at_ms, updated_at_ms)
                VALUES (?, ?, 'upstream-ws', ?, 'main', 'source-sha',
                    'main', 'base-sha', 'pick-release',
                    '[{"sha":"commit-1","subject":"Feature (#101)"}]',
                    '[]', '[]', 0, '[]', ?, 0, 0, 5000, 1, 1)
                """, id, workspaceId, status, worktree.toString());
    }

    private static void initialise(Path repo)
            throws IOException, InterruptedException
    {
        run(repo, "init", "-b", "main");
        run(repo, "config", "user.email", "test@example.com");
        run(repo, "config", "user.name", "Test");
        run(repo, "remote", "add", "origin", repo.toString());
    }

    private static void commit(Path repo, String file, String content, String message)
            throws IOException, InterruptedException
    {
        Files.writeString(repo.resolve(file), content);
        run(repo, "add", file);
        run(repo, "commit", "-m", message);
    }

    private static void run(Path repo, String... args)
            throws IOException, InterruptedException
    {
        Process process = process(repo, args).start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed: " + output);
        }
    }

    private static String output(Path repo, String... args)
            throws IOException, InterruptedException
    {
        Process process = process(repo, args).start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed: " + output);
        }
        return output.strip();
    }

    private static ProcessBuilder process(Path repo, String... args)
    {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        return new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true);
    }
}
