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
package com.bytequay.app.memory;

import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.app.context.ContextBuildService;
import com.bytequay.app.context.RepositoryGuidelineReader;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.review.AgentReviewStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.app.memory.MemoryFixtures.NOW;
import static com.bytequay.app.memory.MemoryFixtures.rule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestMemoryPageReader
{
    private final GitCommandRunner git = new GitCommandRunner();
    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final MemoryService memories = new MemoryService(store, new InMemoryAppSettingsStore(), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final ContextBuildService builds = mock(ContextBuildService.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final MemoryHarvestService harvests = mock(MemoryHarvestService.class);
    private final MemoryPageReader reader = new MemoryPageReader(memories, builds, workspaces, git,
            new RepositoryGuidelineReader(git, mock(AgentReviewStore.class), mock(AgentTurnStore.class)), harvests);

    @BeforeEach
    void harvests()
    {
        when(harvests.state("workspace")).thenReturn(MemoryHarvestService.HarvestState.EMPTY);
    }

    @TempDir
    private Path repository;

    @Test
    void flagsScopePathsGoneFromTheMonitoredHeadAndListsTheGuidelinesItCarries()
            throws IOException
    {
        git.run(repository, "init", "-q");
        write("plugin/storage/src/DeltaLakeWriter.java", "class DeltaLakeWriter {}\n");
        write("CONTRIBUTING.md", "x".repeat(20 * 1024));
        write(".github/DEVELOPMENT.md", "Run one module with -pl.\n");
        git.run(repository, "add", "-A");
        assertThat(git.run(repository, ImmutableList.of("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Initial")).successful()).isTrue();
        String head = git.run(repository, "rev-parse", "HEAD").output().strip();
        when(builds.baseline("workspace")).thenReturn(Optional.of(new ContextBuildService.Baseline("master", head)));
        when(workspaces.requireReadyRepository("workspace")).thenReturn(new WorkspaceRepositoryContext("workspace", repository, 1, RepoRef.of("owner", "repository"),
                OptionalLong.empty(), RepoRef.of("owner", "repository"), "origin", "origin", "master", ImmutableList.of()));
        store.save(rule("m1", "workspace", "Delta rule", ImmutableList.of("plugin/storage/", "plugin/gone/", "**/Test*.java")));
        store.save(rule("m2", "workspace", "Docs rule", ImmutableList.of("CONTRIBUTING.md", "docs/connector")));
        store.save(rule("m3", "workspace", "Repository rule", ImmutableList.of()));
        store.save(rule("m4", "workspace", "Delta directory rule", ImmutableList.of("plugin/storage", "plugin/gone")));

        MemoryPageReader.MemoryPage page = reader.page("workspace");

        assertThat(page.head()).contains(head);
        assertThat(page.switches()).isEqualTo(MemorySwitches.ON);
        assertThat(page.memories()).extracting(entry -> entry.memory().id()).containsExactly("m4", "m3", "m2", "m1");
        assertThat(page.memories()).extracting(MemoryPageReader.Entry::missingScope)
                .containsExactly(ImmutableList.of("plugin/gone"), ImmutableList.of(), ImmutableList.of("docs/connector"), ImmutableList.of("plugin/gone/"));
        assertThat(page.guidelines()).containsExactly(
                new MemoryPageReader.GuidelineView("CONTRIBUTING.md", 20 * 1024, true, "https://github.com/owner/repository/blob/" + head + "/CONTRIBUTING.md"),
                new MemoryPageReader.GuidelineView(".github/DEVELOPMENT.md", 25, false, "https://github.com/owner/repository/blob/" + head + "/.github/DEVELOPMENT.md"));
    }

    @Test
    void anUnreadableRepositoryStillListsTheMemories()
    {
        store.save(rule("m1", "workspace", "Rule", ImmutableList.of("plugin/")));
        when(builds.baseline("workspace")).thenReturn(Optional.empty());
        MemoryPageReader.MemoryPage page = reader.page("workspace");
        assertThat(page.memories()).singleElement().satisfies(entry -> assertThat(entry.missingScope()).isEmpty());
        assertThat(page.guidelines()).isEmpty();
        assertThat(page.head()).isEmpty();
    }

    private void write(String path, String content)
            throws IOException
    {
        Path file = repository.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
