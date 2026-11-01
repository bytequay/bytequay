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

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestOldWorkspaceFolderService
{
    @TempDir
    Path root;

    @Test
    void listsOnlyMarkedFoldersThatNoCurrentWorkspaceOwns()
            throws IOException
    {
        WorkspaceStore store = mock(WorkspaceStore.class);
        when(store.findById(anyString())).thenReturn(Optional.empty());
        when(store.findById("current")).thenReturn(Optional.of(WorkspaceIssueTestData.workspace("current", 1, RepoRef.of("owner", "repository"), Topology.DIRECT)));
        GitCommandRunner git = new GitCommandRunner();

        workspace("current");
        Path old = workspace("old");
        Path repository = Files.createDirectories(old.resolve("repository"));
        git.run(repository, "init", "-q").requireOutput(IllegalStateException::new);
        git.run(repository, "remote", "add", "origin", "https://github.com/owner/repository.git").requireOutput(IllegalStateException::new);
        Files.write(Files.createDirectories(old.resolve("worktrees/task-1")).resolve("build.bin"), new byte[1000]);
        Files.createDirectories(old.resolve("worktrees/task-2"));
        Files.createDirectories(root.resolve("unmarked"));
        Files.writeString(Files.createDirectories(root.resolve("renamed")).resolve(ManagedWorkspaceGit.OWNERSHIP_FILE), "other\n2\n");
        Files.createSymbolicLink(root.resolve("linked"), old);

        OldWorkspaceFolderService service = new OldWorkspaceFolderService(store, new WorkspacePaths(root.toString()), git);
        assertThat(service.list())
                .singleElement()
                .satisfies(folder -> {
                    assertThat(folder.id()).isEqualTo("old");
                    assertThat(folder.path()).isEqualTo(old.toString());
                    assertThat(folder.repository()).contains("owner/repository");
                    assertThat(folder.checkouts()).isEqualTo(2);
                    assertThat(folder.sizeBytes()).isGreaterThan(1000);
                });
        assertThat(service.path("old")).isEqualTo(old);
        for (String other : new String[] {"current", "unmarked", "renamed", "linked", "missing", "../old"}) {
            assertThatThrownBy(() -> service.path(other)).isInstanceOf(WorkspaceException.class);
        }
    }

    @Test
    void readsTheRepositoryFromItsOrigin()
    {
        assertThat(OldWorkspaceFolderService.repositoryName("https://github.com/owner/repository.git")).contains("owner/repository");
        assertThat(OldWorkspaceFolderService.repositoryName("https://gitlab.example.com/group/subgroup/project")).contains("group/subgroup/project");
        assertThat(OldWorkspaceFolderService.repositoryName("git@github.com:owner/repository.git")).contains("owner/repository");
        assertThat(OldWorkspaceFolderService.repositoryName("/local/path")).isEmpty();
    }

    private Path workspace(String id)
            throws IOException
    {
        Path folder = Files.createDirectories(root.resolve(id));
        Files.writeString(folder.resolve(ManagedWorkspaceGit.OWNERSHIP_FILE), id + "\n1\n");
        return folder;
    }
}
