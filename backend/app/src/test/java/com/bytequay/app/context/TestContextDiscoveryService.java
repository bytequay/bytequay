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
package com.bytequay.app.context;

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceDetails;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.app.workspace.WorkspaceSummary;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestContextDiscoveryService
{
    private static final String WORKSPACE = "workspace";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    private Path repository;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final GitCommandRunner git = new GitCommandRunner();
    private final RepositoryContextService notebooks = mock(RepositoryContextService.class);
    private final WorkspaceRepositoryService workspaces = mock(WorkspaceRepositoryService.class);
    private final ContextHistoryDiscovery history = mock(ContextHistoryDiscovery.class);

    @AfterEach
    void closeExecutor()
    {
        executor.close();
    }

    @Test
    void discoversFrozenUpstreamFilesWithoutSyncOrUnrequestedHistory()
            throws IOException
    {
        initialize();
        write("README.md", "[Documentation](https://docs.example.com/current/)\nGuide: https://example.com/docs/api.html\n");
        write("pom.xml", "<project/>");
        write("plugin/iceberg/pom.xml", "<project>iceberg</project>");
        String baseline = freeze();
        String blob = command("rev-parse", baseline + ":README.md").strip();
        write("README.md", "Documentation https://new.example.com/docs/\n");
        String head = commit();
        command("replace", blob, command("rev-parse", head + ":README.md").strip());
        write("README.md", "Documentation https://working.example.com/docs/\n");
        ContextDiscoveryService.Preview preview = service(RepositoryNotebook.empty()).discover(WORKSPACE, request("iceberg"));

        assertThat(preview.baselineSha()).isEqualTo(baseline);
        assertThat(preview.scopes()).singleElement().satisfies(item -> {
            assertThat(item.scope().id()).isEqualTo("path:plugin/iceberg/");
            assertThat(item.scope().discoveryEvidenceRef()).contains(item.evidence().id());
            assertThat(item.evidence().reference()).isEqualTo(new ContextReference.RepositoryFile(7, RepoRef.of("trino", "trino"),
                    "plugin/iceberg/pom.xml", baseline));
            assertThat(item.evidence().capture()).isEqualTo(ContextSourceEvidence.Capture.BYTEQUAY_READ);
        });
        assertThat(preview.sources()).extracting(item -> item.source().url())
                .containsExactly("https://docs.example.com/current/", "https://example.com/docs/api.html");
        assertThat(preview.sources().getFirst().source().kind()).isEqualTo(RegisteredContextSource.Kind.DOCUMENTATION_ROOT);
        assertThat(preview.sources().getLast().source().kind()).isEqualTo(RegisteredContextSource.Kind.DOCUMENTATION_PAGE);
        assertThat(preview.sources()).allSatisfy(item -> {
            assertThat(item.source().origin()).isEqualTo(ContextEntryOrigin.GENERATED);
            assertThat(item.evidence().excerpt()).contains(item.source().url());
            assertThat(item.evidence().contentHash()).contains("git-blob:" + blob);
        });
        assertThat(preview.gaps()).anyMatch(gap -> gap.contains("Choose an enabled scope"));
        verifyNoInteractions(history);
        verify(workspaces, never()).requireReadyRepository(WORKSPACE);
        verify(workspaces, never()).requireReadableRepository(WORKSPACE);
        verifyNoSave();
    }

    @Test
    void findsMixedLanguageComponentsAndDocumentationBelowExistingManifestBoundaries()
            throws IOException
    {
        initialize();
        for (String manifest : ImmutableList.of(
                "products/frontend/package.json", "products/frontend/packages/widget/package.json",
                "products/backend/build.gradle.kts", "products/backend/native/CMakeLists.txt",
                "products/rust/Cargo.toml", "products/go/go.mod", "products/python/pyproject.toml",
                "products/java/build.gradle", "products/maven/pom.xml")) {
            write(manifest, "Component declaration\n");
        }
        write("products/frontend/packages/widget/guides/README.md", "Documentation https://docs.example.com/widget/\n");
        freeze();

        ContextDiscoveryService.Preview preview = service(RepositoryNotebook.empty()).discover(WORKSPACE, request(""));

        assertThat(preview.scopes()).extracting(item -> item.scope().id()).containsExactlyInAnyOrder(
                "path:products/frontend/", "path:products/frontend/packages/widget/",
                "path:products/backend/", "path:products/backend/native/", "path:products/rust/",
                "path:products/go/", "path:products/python/", "path:products/java/", "path:products/maven/");
        assertThat(preview.scopes()).noneMatch(item -> item.scope().id().equals("project"));
        assertThat(preview.sources()).singleElement().satisfies(item -> {
            assertThat(item.source().url()).isEqualTo("https://docs.example.com/widget/");
            assertThat(((ContextReference.RepositoryFile) item.evidence().reference()).path())
                    .isEqualTo("products/frontend/packages/widget/guides/README.md");
        });
        verifyNoInteractions(history);
        verifyNoSave();
    }

    @Test
    void queryFindsDeepComponentsWithoutPruningUnrelatedAncestors()
            throws IOException
    {
        initialize();
        write("outer/build.gradle", "Outer component\n");
        write("outer/source/nested/search-target/package.json", "Target component\n");
        write("outer/source/nested/other/pyproject.toml", "Unrelated component\n");
        freeze();

        ContextDiscoveryService.Preview preview = service(RepositoryNotebook.empty()).discover(WORKSPACE, request("search-target"));

        assertThat(preview.scopes()).extracting(item -> item.scope().id()).containsExactly("path:outer/source/nested/search-target/");
        assertThat(preview.inspected().directories()).isGreaterThan(4).isLessThanOrEqualTo(24);
        verifyNoSave();
    }

    @Test
    void deepAndWideTraversalStopsAtTheDirectoryBudgetWithAnExplicitCoverageGap()
            throws IOException
    {
        initialize();
        for (int branch = 0; branch < 3; branch++) {
            write("area-" + branch + "/" + "level/".repeat(20) + "package.json", "Beyond this discovery window\n");
        }
        freeze();

        ContextDiscoveryService.Preview preview = service(RepositoryNotebook.empty()).discover(WORKSPACE, request(""));

        assertThat(preview.inspected().directories()).isEqualTo(24);
        assertThat(preview.inspected().files()).isZero();
        assertThat(preview.scopes()).isEmpty();
        assertThat(preview.gaps()).anyMatch(gap -> gap.contains("stopped at 24 directories"));
        verifyNoSave();
    }

    @Test
    void excludesSymlinksUnsafeAndPartialDocumentationLinks()
            throws IOException
    {
        initialize();
        write("README.md", "Documentation https://docs.example.com/api(v2)/guide\n"
                + "Documentation http://docs.example.com/ https://127.0.0.1/docs/ https://example.com/docs/?token=a\n"
                + "Documentation https://docs.example.com/" + "a".repeat(17000));
        write("actual/pom.xml", "<project/>");
        for (String excluded : ImmutableList.of(".hidden", "node_modules", "target", "build", "dist", "vendor")) {
            write("actual/" + excluded + "/nested/package.json", "Excluded component\n");
            write("actual/" + excluded + "/README.md", "Documentation https://docs.example.com/excluded/\n");
        }
        Files.createSymbolicLink(repository.resolve("module-link"), Path.of("actual"));
        Files.createSymbolicLink(repository.resolve("ARCHITECTURE.md"), Path.of("README.md"));
        freeze();

        ContextDiscoveryService.Preview preview = service(RepositoryNotebook.empty()).discover(WORKSPACE, request(""));

        assertThat(preview.sources()).isEmpty();
        assertThat(preview.scopes()).extracting(item -> item.scope().id()).containsExactly("path:actual/");
        assertThat(preview.gaps()).anyMatch(gap -> gap.contains("ambiguous boundary")).anyMatch(gap -> gap.contains("unfinished excerpt line"));
    }

    @Test
    void preservesDisabledConfigurationAndBoundsSuggestions()
            throws IOException
    {
        initialize();
        write("README.md", "Documentation https://docs.example.com/current/\n");
        for (int index = 0; index < 13; index++) {
            write("module-%02d/pom.xml".formatted(index), "<project/>");
        }
        freeze();
        ContextScope disabled = new ContextScope("manual", "Keep disabled", new ContextMatch.Selected(ImmutableList.of("module-00/"), ImmutableList.of()),
                false, Optional.empty(), Optional.empty());
        RegisteredContextSource existing = new RegisteredContextSource("manual-docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT,
                "https://docs.example.com/current/", ContextEntryOrigin.USER, Optional.empty());
        RepositoryNotebook notebook = new RepositoryNotebook(1, 0, true, ImmutableList.of(existing), ImmutableList.of(ContextScope.project(), disabled),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.empty());

        ContextDiscoveryService.Preview preview = service(notebook).discover(WORKSPACE, request(""));

        assertThat(preview.sources()).isEmpty();
        assertThat(preview.scopes()).hasSize(10).noneMatch(item -> item.scope().id().equals("path:module-00/"));
        assertThat(preview.gaps()).anyMatch(gap -> gap.contains("At most ten"));
        assertThat(preview.inspected().directories()).isLessThanOrEqualTo(24);
        assertThat(preview.inspected().files()).isLessThanOrEqualTo(24);
    }

    @Test
    void savesOnlySelectedRediscoveredConfigurationWithItsEvidence()
            throws IOException
    {
        initialize();
        write("README.md", "Documentation https://docs.example.com/current/\n");
        write("module/pom.xml", "<project/>");
        freeze();
        RepositoryNotebook notebook = RepositoryNotebook.empty();
        ContextDiscoveryService service = service(notebook);
        ContextDiscoveryService.Preview preview = service.discover(WORKSPACE, request(""));
        when(notebooks.registerDiscovered(eq(WORKSPACE), eq(0L), anyList(), anyList(), anyList())).thenAnswer(invocation -> {
            List<ContextScope> scopes = invocation.getArgument(2);
            List<RegisteredContextSource> sources = invocation.getArgument(3);
            List<ContextSourceEvidence> evidence = invocation.getArgument(4);
            assertThat(scopes).extracting(ContextScope::id).containsExactly("path:module/");
            assertThat(sources).extracting(RegisteredContextSource::url).containsExactly("https://docs.example.com/current/");
            assertThat(evidence).hasSize(2);
            assertThat(evidence).allSatisfy(item -> assertThat(((ContextReference.RepositoryFile) item.reference()).commit()).isEqualTo(preview.baselineSha()));
            assertThat(evidence).extracting(ContextSourceEvidence::id)
                    .containsExactly(scopes.getFirst().discoveryEvidenceRef().orElseThrow(), sources.getFirst().discoveryEvidenceRef().orElseThrow());
            return notebook;
        });
        ContextDiscoveryService.ApplyRequest apply = new ContextDiscoveryService.ApplyRequest(0, preview.baselineSha(), preview.repositoryFingerprint(), "",
                ImmutableList.of("path:module/"), ImmutableList.of(preview.sources().getFirst().source().id()));

        assertThat(service.apply(WORKSPACE, apply)).isEqualTo(notebook);
        verify(notebooks).registerDiscovered(eq(WORKSPACE), eq(0L), anyList(), anyList(), anyList());
        verifyNoInteractions(history);
    }

    @Test
    void rejectsStaleUnknownMovedAndSyncedPreviewsBeforeSaving()
            throws IOException
    {
        initialize();
        write("module/pom.xml", "<project/>");
        freeze();
        ContextDiscoveryService service = service(RepositoryNotebook.empty());
        ContextDiscoveryService.Preview preview = service.discover(WORKSPACE, request(""));
        assertThatThrownBy(() -> service.apply(WORKSPACE, apply(preview, 1, "path:module/"))).isInstanceOf(ContextConflictException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> service.apply(WORKSPACE, apply(preview, 0, "path:forged/"))).withMessageContaining("unavailable");
        when(workspaces.getWorkspace(WORKSPACE)).thenReturn(details(NOW.plusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.apply(WORKSPACE, apply(preview, 0, "path:module/"))).withMessageContaining("sync state changed");
        when(workspaces.getWorkspace(WORKSPACE)).thenReturn(details(NOW));
        write("README.md", "new baseline");
        freeze();
        assertThatIllegalArgumentException().isThrownBy(() -> service.apply(WORKSPACE, apply(preview, 0, "path:module/"))).withMessageContaining("baseline changed");
        verifyNoSave();
    }

    @Test
    void rechecksSyncObservationAtAcceptanceAndRejectsChangedRoutingWithSameCommit()
            throws IOException
    {
        initialize();
        write("module/pom.xml", "<project/>");
        freeze();
        ContextDiscoveryService service = service(RepositoryNotebook.empty());
        ContextDiscoveryService.Preview preview = service.discover(WORKSPACE, request(""));
        when(workspaces.getWorkspace(WORKSPACE)).thenReturn(details(NOW), details(NOW.plusSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> service.apply(WORKSPACE, apply(preview, 0, "path:module/"))).withMessageContaining("sync state changed");
        when(workspaces.getWorkspace(WORKSPACE)).thenReturn(details(NOW));
        WorkspaceRepositoryContext changed = new WorkspaceRepositoryContext(WORKSPACE, repository, 1, RepoRef.of("fork", "trino"), OptionalLong.of(8),
                RepoRef.of("another", "trino"), "upstream", "origin", "main", ImmutableList.of());
        when(workspaces.requireLocalRepository(WORKSPACE)).thenReturn(changed);
        assertThatIllegalArgumentException().isThrownBy(() -> service.apply(WORKSPACE, apply(preview, 0, "path:module/"))).withMessageContaining("routing or sync");
        verifyNoSave();
    }

    @Test
    void rejectsRefMovementFromAFailedSyncWithoutTimestampChange()
            throws IOException
    {
        initialize();
        write("module/pom.xml", "<project/>");
        String baseline = freeze();
        ContextDiscoveryService service = service(RepositoryNotebook.empty());
        ContextDiscoveryService.Preview preview = service.discover(WORKSPACE, request(""));
        write("README.md", "new fetched commit");
        String fetched = commit();
        command("update-ref", "refs/remotes/upstream/main", baseline);
        AtomicInteger observations = new AtomicInteger();
        when(workspaces.getWorkspace(WORKSPACE)).thenAnswer(ignored -> {
            if (observations.incrementAndGet() == 2) {
                command("update-ref", "refs/remotes/upstream/main", fetched);
            }
            return details(NOW);
        });

        assertThatIllegalArgumentException().isThrownBy(() -> service.apply(WORKSPACE, apply(preview, 0, "path:module/")))
                .withMessageContaining("baseline changed");
        verifyNoSave();
    }

    @Test
    void passesExplicitRemoteChoiceToHistoryAndRequiresBoundedRequests()
            throws IOException
    {
        initialize();
        write("README.md", "overview");
        String baseline = freeze();
        RepositoryNotebook notebook = RepositoryNotebook.empty();
        ContextDiscoveryService service = service(notebook);
        when(history.discover(workspace(), baseline, notebook.scopes(), "", false))
                .thenReturn(new ContextHistoryDiscovery.Result(ImmutableList.of(), ImmutableList.of("Local coverage"), 2, 0));
        ContextDiscoveryService.Preview preview = service.discover(WORKSPACE, new ContextDiscoveryService.Request(0, ImmutableList.of("project"), "", false));
        assertThat(preview.inspected().pullRequests()).isEqualTo(2);
        verify(history).discover(workspace(), baseline, notebook.scopes(), "", false);
        assertThatIllegalArgumentException().isThrownBy(() -> new ContextDiscoveryController.DiscoveryRequest(null, ImmutableList.of(), "", false));
        assertThatIllegalArgumentException().isThrownBy(() -> new ContextDiscoveryController.DiscoveryRequest(0L, ImmutableList.of(), "", null));
        assertThatIllegalArgumentException().isThrownBy(() -> request("repo:other/repository"));
        assertThatIllegalArgumentException().isThrownBy(() -> request("a".repeat(101)));
    }

    @Test
    void timedOutReadCannotAddSuggestionsWhenItCompletesAfterThePreview()
            throws Exception
    {
        initialize();
        write("README-A.md", "Documentation https://docs.example.com/retained/\n");
        write("README-Z.md", "Documentation https://docs.example.com/late/\n");
        String baseline = freeze();
        ContextBaselineReader realReader = new ContextBaselineReader(git, CLOCK);
        ContextBaselineReader.Read retained = realReader.read(workspace(), 7, baseline, "README-A.md", 1, Optional.empty());
        ContextBaselineReader.Read late = realReader.read(workspace(), 7, baseline, "README-Z.md", 1, Optional.empty());
        ContextBaselineReader reader = mock(ContextBaselineReader.class);
        when(reader.directory(workspace(), 7, baseline, "", 0)).thenReturn(realReader.directory(workspace(), 7, baseline, "", 0));
        when(reader.read(workspace(), 7, baseline, "README-A.md", 1, Optional.empty())).thenReturn(retained);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(reader.read(workspace(), 7, baseline, "README-Z.md", 1, Optional.empty())).thenAnswer(ignored -> {
            entered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = release.await(5, SECONDS);
                    assertThat(released).as("Late source must be released by the test").isTrue();
                }
                catch (InterruptedException failure) {
                    interrupted.set(true);
                }
            }
            return late;
        });

        ContextDiscoveryService.Preview preview;
        try {
            preview = service(RepositoryNotebook.empty(), reader, Duration.ofSeconds(1)).discover(WORKSPACE, request(""));
            assertThat(entered.getCount()).isZero();
            assertThat(preview.sources()).extracting(item -> item.source().url()).containsExactly("https://docs.example.com/retained/");
            assertThat(preview.gaps()).anyMatch(gap -> gap.contains("time budget") && gap.contains("completed suggestions are retained"));
            assertThat(preview.inspected().files()).isEqualTo(2);
        }
        finally {
            release.countDown();
            executor.close();
        }

        assertThat(interrupted).isTrue();
        assertThat(preview.sources()).extracting(item -> item.source().url()).containsExactly("https://docs.example.com/retained/");
        assertThat(preview.inspected().files()).isEqualTo(2);
        verifyNoInteractions(history);
        verifyNoSave();
    }

    @Test
    void interruptedDiscoveryAbortsBeforeHistorySearch()
            throws Exception
    {
        assertReadInterruption(false);
    }

    @Test
    void interruptedApplyAbortsBeforeSavingConfiguration()
            throws Exception
    {
        assertReadInterruption(true);
    }

    @Test
    void rejectsNonpositiveScanTimeoutsBeforeReadingSources()
    {
        ContextBaselineReader reader = mock(ContextBaselineReader.class);
        for (Duration timeout : ImmutableList.of(Duration.ZERO, Duration.ofNanos(-1))) {
            assertThatIllegalArgumentException().isThrownBy(() -> service(RepositoryNotebook.empty(), reader, timeout))
                    .withMessageContaining("scanTimeout must be positive");
        }
        verifyNoInteractions(reader, history);
        verifyNoSave();
    }

    private void assertReadInterruption(boolean applying)
            throws Exception
    {
        initialize();
        write("module/pom.xml", "<project/>");
        String baseline = freeze();
        RepositoryNotebook notebook = RepositoryNotebook.empty();
        ContextDiscoveryService.Preview preview = service(notebook).discover(WORKSPACE, request(""));
        ContextBaselineReader reader = mock(ContextBaselineReader.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.directory(workspace(), 7, baseline, "", 0)).thenAnswer(ignored -> {
            entered.countDown();
            assertThat(release.await(5, SECONDS)).as("Reader should be interrupted before release").isTrue();
            return new ContextBaselineReader.Directory("", baseline, ImmutableList.of(), 0, false);
        });
        ContextDiscoveryService service = service(notebook, reader, Duration.ofSeconds(30));
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                if (applying) {
                    service.apply(WORKSPACE, apply(preview, 0, "path:module/"));
                }
                else {
                    service.discover(WORKSPACE, new ContextDiscoveryService.Request(0, ImmutableList.of("project"), "", true));
                }
                outcome.complete(null);
            }
            catch (Throwable failure) {
                interrupted.set(Thread.currentThread().isInterrupted());
                outcome.complete(failure);
            }
        });
        try {
            assertThat(entered.await(5, SECONDS)).as("Source read started").isTrue();
            caller.interrupt();
            assertThat(outcome.get(5, SECONDS)).isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");
            assertThat(interrupted).isTrue();
            verifyNoInteractions(history);
            verifyNoSave();
        }
        finally {
            caller.interrupt();
            release.countDown();
            caller.join();
        }
    }

    private ContextDiscoveryService service(RepositoryNotebook notebook)
    {
        return service(notebook, new ContextBaselineReader(git, CLOCK), Duration.ofSeconds(30));
    }

    private ContextDiscoveryService service(RepositoryNotebook notebook, ContextBaselineReader reader, Duration timeout)
    {
        when(notebooks.getNotebook(WORKSPACE)).thenReturn(notebook);
        when(workspaces.requireLocalRepository(WORKSPACE)).thenReturn(workspace());
        when(workspaces.getWorkspace(WORKSPACE)).thenReturn(details(NOW));
        return new ContextDiscoveryService(notebooks, workspaces, git, reader, history, executor, timeout);
    }

    private WorkspaceDetails details(Instant syncedAt)
    {
        WorkspaceSummary summary = new WorkspaceSummary(WORKSPACE, "Trino", 1, "fork", "trino", 7L, "trino", "trino", 7, "trino", "trino",
                Workspace.Topology.FORK, "main", "main", repository.toString(), syncedAt, null, 0);
        return new WorkspaceDetails(summary, "", null, NOW, NOW);
    }

    private static ContextDiscoveryService.Request request(String query)
    {
        return new ContextDiscoveryService.Request(0, ImmutableList.of(), query, false);
    }

    private static ContextDiscoveryService.ApplyRequest apply(ContextDiscoveryService.Preview preview, long revision, String scope)
    {
        return new ContextDiscoveryService.ApplyRequest(revision, preview.baselineSha(), preview.repositoryFingerprint(), "", ImmutableList.of(scope), ImmutableList.of());
    }

    private void verifyNoSave()
    {
        verify(notebooks, never()).registerDiscovered(eq(WORKSPACE), eq(0L), anyList(), anyList(), anyList());
    }

    private WorkspaceRepositoryContext workspace()
    {
        return new WorkspaceRepositoryContext(WORKSPACE, repository, 1, RepoRef.of("fork", "trino"), OptionalLong.of(7), RepoRef.of("trino", "trino"),
                "upstream", "origin", "main", ImmutableList.of());
    }

    private void initialize()
    {
        command("init", "-q");
    }

    private void write(String path, String text)
            throws IOException
    {
        Files.createDirectories(repository.resolve(path).getParent());
        Files.writeString(repository.resolve(path), text);
    }

    private String freeze()
    {
        String sha = commit();
        command("update-ref", "refs/remotes/upstream/main", sha);
        return sha;
    }

    private String commit()
    {
        command("add", ".");
        command("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-qm", "Fixture");
        return command("rev-parse", "HEAD").strip();
    }

    private String command(String... arguments)
    {
        GitCommandRunner.Result result = git.run(repository, arguments);
        assertThat(result.successful()).as("git %s: %s", String.join(" ", arguments), result.output()).isTrue();
        return result.output();
    }
}
