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
package com.bytequay.app.agent.codegraph;

import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;

class TestCodeGraphBaselineRefresher
{
    private final GitCommandRunner git = new GitCommandRunner();

    @Test
    void schedulesARefreshAtTheFetchedDefaultBranchCommit(@TempDir Path temp)
            throws IOException
    {
        FakeCodeGraphService codeGraph = new FakeCodeGraphService();
        Fixture fixture = Fixture.create(temp, git, true);

        refresher(codeGraph, fixture).refreshAfterFetch(fixture.workspace);

        assertThat(codeGraph.refreshes).containsExactly(new FakeCodeGraphService.Refresh(new RepositoryIdentity("owner", "name"), fixture.repository, fixture.head));
    }

    @Test
    void skipsWhenTheHelperIsUnavailable(@TempDir Path temp)
            throws IOException
    {
        FakeCodeGraphService codeGraph = new FakeCodeGraphService();
        codeGraph.available = false;
        Fixture fixture = Fixture.create(temp, git, true);

        refresher(codeGraph, fixture).refreshAfterFetch(fixture.workspace);

        assertThat(codeGraph.refreshes).isEmpty();
    }

    @Test
    void skipsWhenTheDefaultBranchWasNeverFetched(@TempDir Path temp)
            throws IOException
    {
        FakeCodeGraphService codeGraph = new FakeCodeGraphService();
        Fixture fixture = Fixture.create(temp, git, false);

        refresher(codeGraph, fixture).refreshAfterFetch(fixture.workspace);

        assertThat(codeGraph.refreshes).isEmpty();
    }

    @Test
    void aFailedRefreshNeverReachesTheFetch(@TempDir Path temp)
            throws IOException
    {
        FakeCodeGraphService codeGraph = new FakeCodeGraphService();
        codeGraph.failRefresh = true;
        Fixture fixture = Fixture.create(temp, git, true);

        refresher(codeGraph, fixture).refreshAfterFetch(fixture.workspace);

        assertThat(codeGraph.refreshes).isEmpty();
    }

    private CodeGraphBaselineRefresher refresher(FakeCodeGraphService codeGraph, Fixture fixture)
    {
        return new CodeGraphBaselineRefresher(codeGraph, fixture.paths, git, Runnable::run);
    }

    private record Fixture(WorkspacePaths paths, Workspace workspace, Path repository, String head)
    {
        static Fixture create(Path temp, GitCommandRunner git, boolean fetched)
                throws IOException
        {
            WorkspacePaths paths = new WorkspacePaths(temp.resolve("workspaces").toString());
            Path repository = paths.repository("workspace-1");
            Files.createDirectories(repository);
            Files.writeString(repository.resolve("README.md"), "hello\n");
            run(git, repository, "init", "-q", "-b", "main");
            run(git, repository, "add", "-A");
            run(git, repository, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "Initial");
            String head = run(git, repository, "rev-parse", "HEAD");
            if (fetched) {
                run(git, repository, "update-ref", "refs/remotes/origin/main", head);
            }
            return new Fixture(paths, workspace("workspace-1", repository), repository, head);
        }

        private static Workspace workspace(String id, Path repository)
        {
            Instant now = Instant.parse("2026-09-05T00:00:00Z");
            RepoRef selected = RepoRef.of("owner", "name");
            return new Workspace(
                    id,
                    id,
                    100,
                    selected,
                    Optional.empty(),
                    Optional.empty(),
                    100,
                    selected,
                    Topology.DIRECT,
                    "main",
                    "https://github.com/owner/name.git",
                    Optional.empty(),
                    Optional.empty(),
                    repository.toString(),
                    Optional.empty(),
                    Optional.empty(),
                    0,
                    now,
                    now);
        }

        private static String run(GitCommandRunner git, Path directory, String... arguments)
        {
            Result result = git.run(directory, arguments);
            checkState(result.successful(), "git %s failed: %s", String.join(" ", arguments), result.output());
            return result.output().strip();
        }
    }
}
