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

import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestWorkspaceRelationService
{
    @Test
    void rejectsSelfLinksAndCycles(@TempDir Path root)
            throws Exception
    {
        Fixture fixture = fixture(root);

        assertThatThrownBy(() -> fixture.service().link(
                "fork-ws",
                new WorkspaceRelationService.RelationUpdate(
                        "fork-ws", true, true, 15)))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure ->
                        assertThat(failure.getStatusCode())
                                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        fixture.jdbc().update("""
                INSERT INTO workspace_relation (
                    workspace_id, upstream_workspace_id, commits_enabled,
                    tags_enabled, last_fetched_at_ms,
                    auto_fetch_interval_minutes, indexed_commit_count,
                    created_at_ms, updated_at_ms)
                VALUES ('upstream-ws', 'fork-ws', 1, 1, NULL, 15, 0, 1, 1)
                """);

        assertThatThrownBy(() -> fixture.service().link(
                "fork-ws",
                new WorkspaceRelationService.RelationUpdate(
                        "upstream-ws", true, true, 15)))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(failure.getReason()).contains("cycle");
                    // The plain reverse link, not a longer chain.
                    assertThat(failure.getReason())
                            .contains("already reads from this workspace");
                });
    }

    /**
     * The picker must not offer a workspace the write path would refuse:
     * both read the same cycle check, so an ineligible candidate comes
     * back carrying the same sentence the 422 would have used.
     */
    @Test
    void marksCandidatesThatWouldFormACycleInsteadOfOfferingThem(@TempDir Path root)
            throws Exception
    {
        Fixture fixture = fixture(root);
        fixture.jdbc().update("""
                INSERT INTO workspace_relation (
                    workspace_id, upstream_workspace_id, commits_enabled,
                    tags_enabled, last_fetched_at_ms,
                    auto_fetch_interval_minutes, indexed_commit_count,
                    created_at_ms, updated_at_ms)
                VALUES ('upstream-ws', 'fork-ws', 1, 1, NULL, 15, 0, 1, 1)
                """);

        List<WorkspaceRelationService.RelationCandidateDto> candidates =
                fixture.service().candidates("fork-ws");

        assertThat(candidates).isNotEmpty();
        assertThat(candidates)
                .filteredOn(row -> "upstream-ws".equals(row.workspaceId()))
                .singleElement()
                .satisfies(row -> assertThat(row.ineligibleReason())
                        .contains("already reads from this workspace"));
    }

    @Test
    void offersAWorkspaceWithNoRelationOfItsOwn(@TempDir Path root)
            throws Exception
    {
        Fixture fixture = fixture(root);

        assertThat(fixture.service().candidates("fork-ws"))
                .filteredOn(row -> "upstream-ws".equals(row.workspaceId()))
                .singleElement()
                .satisfies(row -> assertThat(row.ineligibleReason()).isNull());
    }

    @Test
    void persistsCapabilitiesAndKeepsFetchStateWhenUpdatingTheSameLink(
            @TempDir Path root)
            throws Exception
    {
        Fixture fixture = fixture(root);
        WorkspaceRelationService.WorkspaceRelationDto linked = fixture.service().link(
                "fork-ws",
                new WorkspaceRelationService.RelationUpdate(
                        "upstream-ws", true, false, 30));

        assertThat(linked.commitsEnabled()).isTrue();
        assertThat(linked.tagsEnabled()).isFalse();
        assertThat(linked.autoFetchIntervalMinutes()).isEqualTo(30);
        assertThat(linked.lastFetchedAt()).isNull();

        fixture.jdbc().update("""
                UPDATE workspace_relation
                SET last_fetched_at_ms = 123, indexed_commit_count = 42
                WHERE workspace_id = 'fork-ws'
                """);
        WorkspaceRelationService.WorkspaceRelationDto updated = fixture.service().link(
                "fork-ws",
                new WorkspaceRelationService.RelationUpdate(
                        "upstream-ws", false, true, 60));

        assertThat(updated.commitsEnabled()).isFalse();
        assertThat(updated.tagsEnabled()).isTrue();
        assertThat(updated.autoFetchIntervalMinutes()).isEqualTo(60);
        assertThat(updated.lastFetchedAt()).isEqualTo(Instant.ofEpochMilli(123));
        assertThat(updated.indexedCommitCount()).isEqualTo(42);

        fixture.service().unlink("fork-ws");
        assertThat(fixture.service().find("fork-ws")).isEmpty();
    }

    @Test
    void resolvesTheFetchedRemoteRefBeforeAStaleLocalBranch(@TempDir Path root)
            throws Exception
    {
        Fixture fixture = fixture(root);
        Path upstream = root.resolve("upstream");
        when(fixture.git().refExists(upstream, "origin/main")).thenReturn(true);
        when(fixture.git().refExists(upstream, "main")).thenReturn(true);

        assertThat(fixture.service().resolveRef(upstream, "main"))
                .isEqualTo("origin/main");
        verify(fixture.git(), never()).refExists(upstream, "main");
    }

    @Test
    void fetchedRemoteResolutionRejectsALocalOnlyBranch(@TempDir Path root)
            throws Exception
    {
        Fixture fixture = fixture(root);
        Path upstream = root.resolve("upstream");
        when(fixture.git().isValidBranchName("local-only")).thenReturn(true);
        when(fixture.git().refExists(upstream, "origin/local-only")).thenReturn(false);
        when(fixture.git().refExists(upstream, "local-only")).thenReturn(true);

        assertThatThrownBy(() -> fixture.service().resolveFetchedRemoteRef(
                upstream, "local-only"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fetched origin");
        verify(fixture.git(), never()).refExists(upstream, "local-only");
    }

    @Test
    void subjectNormalizationIgnoresCaseAndWhitespaceButNothingElse()
    {
        assertThat(WorkspaceRelationService.normalizeSubject("  Fix   Checkstyle issues "))
                .isEqualTo(WorkspaceRelationService.normalizeSubject("fix checkstyle ISSUES"));
        assertThat(WorkspaceRelationService.normalizeSubject(null)).isEmpty();

        // Distinct work stays distinct.
        assertThat(WorkspaceRelationService.normalizeSubject("Bump guava to 33"))
                .isNotEqualTo(WorkspaceRelationService.normalizeSubject("Bump guava to 34"));

        // The known hazard of matching on subjects: two genuinely different
        // upstream commits that happen to share a message are indistinguishable,
        // so the second is treated as already present and silently skipped.
        assertThat(WorkspaceRelationService.normalizeSubject("Fix flaky test"))
                .isEqualTo(WorkspaceRelationService.normalizeSubject("Fix flaky test"));
    }

    private static Fixture fixture(Path root)
            throws Exception
    {
        Path fork = Files.createDirectory(root.resolve("fork"));
        Path upstream = Files.createDirectory(root.resolve("upstream"));
        JdbcTemplate jdbc = jdbc(root.resolve("relations.db"));
        createTables(jdbc);
        jdbc.update("INSERT INTO workspaces (id, name) VALUES ('fork-ws', 'Fork')");
        jdbc.update("INSERT INTO workspaces (id, name) VALUES ('upstream-ws', 'Upstream')");
        jdbc.update("""
                INSERT INTO workspace_repos (workspace_id, repo_full_name)
                VALUES ('fork-ws', 'acme/fork')
                """);
        jdbc.update("""
                INSERT INTO workspace_repos (workspace_id, repo_full_name)
                VALUES ('upstream-ws', 'acme/upstream')
                """);

        WorkspaceService workspaces = mock(WorkspaceService.class);
        WorkspaceRepositoryResolver resolver = mock(WorkspaceRepositoryResolver.class);
        WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
        RepoService repos = mock(RepoService.class);
        GitRunner git = mock(GitRunner.class);
        WorkspaceRepositoryResolver.RepositoryIdentity forkIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "fork", "acme/fork", "main");
        WorkspaceRepositoryResolver.RepositoryIdentity upstreamIdentity =
                new WorkspaceRepositoryResolver.RepositoryIdentity(
                        "acme", "upstream", "acme/upstream", "main");
        when(resolver.resolve("fork-ws")).thenReturn(forkIdentity);
        when(resolver.resolve("upstream-ws")).thenReturn(upstreamIdentity);
        when(watchedRepos.find("acme", "fork")).thenReturn(Optional.of(
                new WatchedRepo(1, "acme", "fork", 0, fork.toString(), null, null)));
        when(watchedRepos.find("acme", "upstream")).thenReturn(Optional.of(
                new WatchedRepo(
                        2, "acme", "upstream", 1, upstream.toString(), null, null)));
        when(git.isGitWorkingTree(fork)).thenReturn(true);
        when(git.isGitWorkingTree(upstream)).thenReturn(true);
        // candidates() enumerates every other workspace, so the picker
        // needs the same roster the resolver already knows about.
        when(workspaces.list()).thenReturn(List.of(
                new Workspace("fork-ws", "Fork", null, false, null,
                        Instant.EPOCH, Instant.EPOCH),
                new Workspace("upstream-ws", "Upstream", null, false, null,
                        Instant.EPOCH, Instant.EPOCH)));
        return new Fixture(
                jdbc,
                git,
                new WorkspaceRelationService(
                        jdbc, workspaces, resolver, watchedRepos, repos, git));
    }

    private static JdbcTemplate jdbc(Path database)
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + database);
        return new JdbcTemplate(dataSource);
    }

    private static void createTables(JdbcTemplate jdbc)
    {
        jdbc.execute("CREATE TABLE workspaces (id TEXT PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("""
                CREATE TABLE workspace_repos (
                    workspace_id TEXT PRIMARY KEY,
                    repo_full_name TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE workspace_relation (
                    workspace_id TEXT PRIMARY KEY,
                    upstream_workspace_id TEXT NOT NULL,
                    commits_enabled INTEGER NOT NULL,
                    tags_enabled INTEGER NOT NULL,
                    last_fetched_at_ms INTEGER,
                    auto_fetch_interval_minutes INTEGER NOT NULL,
                    indexed_commit_count INTEGER NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL)
                """);
    }

    private record Fixture(
            JdbcTemplate jdbc,
            GitRunner git,
            WorkspaceRelationService service) {}
}
