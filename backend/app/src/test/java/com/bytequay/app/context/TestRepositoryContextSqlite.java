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

import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.app.context.RepositoryContextPreparation.Admission;
import com.bytequay.app.context.RepositoryContextPreparation.Execution;
import com.bytequay.app.context.RepositoryContextPreparation.Plan;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ActiveContextPreparation;
import com.bytequay.core.context.ContextClaim;
import com.bytequay.core.context.ContextDocumentRead;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextPreparationResult;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewOperation;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

import static com.bytequay.app.context.RepositoryContextPreparation.Status.JOINED;
import static com.bytequay.app.context.RepositoryContextPreparation.Status.OWNED;
import static com.bytequay.core.context.ContextEntryKind.KNOWLEDGE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises notebook serialization and retention through the application's real SQLite settings port. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:${java.io.tmpdir}/bytequay-context-test-${random.uuid}.db"
        + "?journal_mode=WAL&busy_timeout=30000&synchronous=NORMAL&foreign_keys=ON&temp_store=MEMORY")
class TestRepositoryContextSqlite
{
    private static final Logger log = LoggerFactory.getLogger(TestRepositoryContextSqlite.class);

    @Autowired
    private RepositoryContextService context;

    @Autowired
    private AppSettingsStore settings;

    @Autowired
    private WorkspaceStore workspaces;

    @Autowired
    private AgentReviewStore reviews;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private AgentUnitOfWork unitOfWork;

    @Autowired
    private DataSource dataSource;

    private String workspaceId;

    @BeforeEach
    void createWorkspace()
    {
        UUID identity = UUID.randomUUID();
        workspaceId = identity.toString();
        long repositoryId = Math.max(1, identity.getMostSignificantBits() & Long.MAX_VALUE);
        RepoRef repository = RepoRef.of("context-test", "repository-" + workspaceId);
        workspaces.createOrGet(new Workspace(
                workspaceId, "Context test", repositoryId, repository, Optional.empty(), Optional.empty(), repositoryId, repository,
                Workspace.Topology.DIRECT, "main", "https://github.com/" + repository.fullName() + ".git", Optional.empty(), Optional.empty(),
                "/tmp/repository-context-test/" + workspaceId, Optional.empty(), Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void absentNotebookDefaultsOnWithoutCreatingASettingsRow()
    {
        assertThat(context.getNotebook(workspaceId)).isEqualTo(RepositoryNotebook.empty());
        assertThat(settings.get(key())).isEmpty();
    }

    @Test
    void discoveryProvenanceRoundTripsWithoutKnowledgeAndSurvivesUnwatch()
    {
        Workspace workspace = workspaces.findById(workspaceId).orElseThrow();
        String sha = "a".repeat(40);
        ContextSourceEvidence proof = new ContextSourceEvidence("discovery:readme",
                new ContextReference.RepositoryFile(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), "component/README.md", sha),
                "Captured component README", "See https://example.com/docs/", ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(Instant.EPOCH), Optional.of(sha), Optional.of(sha), false);
        ContextScope scope = new ContextScope("path:component/", "Component", new ContextMatch.Selected(ImmutableList.of("component/"), ImmutableList.of()),
                true, Optional.empty(), Optional.of(proof.id()));
        RegisteredContextSource source = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT,
                "https://example.com/docs/", ContextEntryOrigin.GENERATED, Optional.of(proof.id()));

        RepositoryNotebook saved = context.registerDiscovered(workspaceId, 0, ImmutableList.of(scope), ImmutableList.of(source), ImmutableList.of(proof));
        workspaces.setWatched(workspaceId, false);
        RepositoryContextService recreated = new RepositoryContextService(settings, mapper, workspaces, reviews);

        assertThat(recreated.getNotebook(workspaceId)).isEqualTo(saved);
        RepositoryNotebook disabled = recreated.setUseInReviews(workspaceId, saved.revision(), false);
        assertThat(disabled.entries()).isEmpty();
        assertThat(disabled.sources()).containsExactly(source);
        assertThat(disabled.scopes()).containsExactly(ContextScope.project(), scope);
        assertThat(disabled.sourceEvidence()).containsExactly(proof);
    }

    @Test
    void notebookSurvivesServiceRecreationAndUnwatchWithOptimisticEdits()
    {
        ContextScope scope = new ContextScope("path:plugin/iceberg/", "Iceberg",
                new ContextMatch.Selected(ImmutableList.of("plugin/iceberg/"), ImmutableList.of("iceberg")), true, Optional.empty(), Optional.empty());
        context.configure(workspaceId, 0, new EditContextConfiguration(
                Optional.of(false),
                Optional.of(ImmutableList.of(new EditContextConfiguration.Source(
                        "iceberg-docs", RegisteredContextSource.Kind.DOCUMENTATION_ROOT, "https://iceberg.apache.org/docs/latest/"))),
                Optional.of(ImmutableList.of(ContextScope.project(), scope))));
        RepositoryNotebook saved = context.editEntry(workspaceId, 1, new EditContextEntry(
                ContextEntry.knowledgeId(scope.id()), scope.id(), "Connector questions", "Questions to investigate", "Check the snapshot contract.",
                ImmutableList.of(new ContextClaim("Which snapshot is visible?", ContextClaim.Basis.QUESTION, ImmutableList.of())), ImmutableList.of()));

        workspaces.setWatched(workspaceId, false);
        RepositoryContextService recreated = new RepositoryContextService(settings, mapper, workspaces, reviews);

        assertThat(recreated.getNotebook(workspaceId)).isEqualTo(saved);
        assertThat(recreated.getNotebook(workspaceId).useInReviews()).isFalse();
        assertThatThrownBy(() -> recreated.setUseInReviews(workspaceId, 1, true)).isInstanceOf(ContextConflictException.class);
        assertThat(recreated.getNotebook(workspaceId)).isEqualTo(saved);
        assertThat(recreated.setUseInReviews(workspaceId, saved.revision(), true).entries()).isEqualTo(saved.entries());
    }

    @Test
    void preparationReceiptsSurviveReloadAndPreferenceChangesIncludingBlankSources()
            throws Exception
    {
        String sha = "a".repeat(40);
        String readId = "context-read:" + UUID.randomUUID();
        ContextReference.RepositoryFile reference = new ContextReference.RepositoryFile(1, RepoRef.of("example", "repository"), "README.md", sha);
        ContextRepositoryRead read = new ContextRepositoryRead(readId, reference, sha, Instant.EPOCH, 1, 1, 1, 6, false, Optional.of(readId));
        ContextSourceEvidence evidence = new ContextSourceEvidence(readId, reference, "Captured baseline source", "source", ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(Instant.EPOCH), Optional.of(sha), Optional.empty(), false);
        RegisteredContextSource documentation = new RegisteredContextSource("docs", RegisteredContextSource.Kind.DOCUMENTATION_PAGE,
                "https://example.com/docs/page", ContextEntryOrigin.USER, Optional.empty());
        ContextDocumentRead blank = new ContextDocumentRead("context-read:" + UUID.randomUUID(), new ContextReference.Document("docs", documentation.url()),
                documentation, 0, UUID.randomUUID().toString(), "b".repeat(64), Instant.EPOCH, 1, 1, 0, true, false, false, Optional.empty());
        ContextInputFingerprint input = ContextInputFingerprint.create("project", ImmutableMap.of("git:README.md", "100644 blob " + sha), ImmutableList.of());
        ContextPreparationResult result = new ContextPreparationResult("completed-attempt", input, ContextPreparationResult.Outcome.EMPTY, ImmutableList.of(),
                ImmutableList.of(read, blank), "The inspected sources did not establish a useful note");
        ContextScope scope = new ContextScope("project", "Project", new ContextMatch.Repository(), true, Optional.of(result), Optional.empty());
        ActiveContextPreparation active = new ActiveContextPreparation("active-attempt", "request", "sha256:" + "a".repeat(64),
                "task", "turn", "turn-key", Optional.of("review"), Optional.of("run"), 0, ImmutableList.of(input));
        RepositoryNotebook notebook = new RepositoryNotebook(1, 0, true, ImmutableList.of(documentation), ImmutableList.of(scope), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(evidence), Optional.of(active));
        settings.put(key(), mapper.writeValueAsString(notebook));

        RepositoryContextService recreated = new RepositoryContextService(settings, mapper, workspaces, reviews);
        assertThat(recreated.getNotebook(workspaceId)).isEqualTo(notebook);
        RepositoryNotebook disabled = recreated.setUseInReviews(workspaceId, 0, false);
        assertThat(disabled.revision()).isEqualTo(1);
        assertThat(disabled.scopes()).containsExactly(scope);
        assertThat(disabled.sourceEvidence()).containsExactly(evidence);
        assertThat(recreated.getNotebook(workspaceId)).isEqualTo(disabled);
    }

    @Test
    void notebookAndReviewReceiptRollbackTogetherWhenRunWriteFails()
    {
        String sha = "a".repeat(40);
        ReviewRun run = reviews.insertRun(new ReviewRun(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, ReviewRun.Scope.FULL,
                "main", sha, sha, sha, Optional.empty(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ReviewRun.State.RUNNING,
                Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false, ImmutableMap.of(), ImmutableMap.of(), Optional.empty(),
                Optional.empty(), 0, Instant.EPOCH, Instant.EPOCH, Optional.empty()));
        ContextInputFingerprint input = ContextInputFingerprint.create("project", ImmutableMap.of("git:README.md", "100644 blob " + sha), ImmutableList.of());
        ActiveContextPreparation active = new ActiveContextPreparation("attempt", "request", "sha256:" + "a".repeat(64),
                "task", "turn", "turn-key", Optional.of(run.reviewId()), Optional.of(run.id()), 0, ImmutableList.of(input));
        RepositoryNotebook empty = context.getNotebook(workspaceId);
        RepositoryNotebook owned = new RepositoryNotebook(1, 0, true, empty.sources(), empty.scopes(), empty.entries(),
                empty.suppressedKnowledgeIds(), empty.sourceEvidence(), Optional.of(active));
        ReviewOperation receipt = new ReviewOperation("a".repeat(64), "{\"status\":\"OWNED\"}");

        assertThatThrownBy(() -> WorkspaceOperationLocks.withLock(workspaceId, () -> unitOfWork.execute(() -> {
            context.savePreparation(workspaceId, empty, owned);
            reviews.updateRun(run.withOperation("begin", receipt, Instant.EPOCH).withVersion(run.version() + 1));
            return null;
        }))).isInstanceOf(RuntimeException.class);
        assertThat(context.getNotebook(workspaceId)).isEqualTo(empty);
        assertThat(reviews.findRun(run.id()).orElseThrow()).isEqualTo(run);

        WorkspaceOperationLocks.withLock(workspaceId, () -> unitOfWork.execute(() -> {
            context.savePreparation(workspaceId, empty, owned);
            reviews.updateRun(run.withOperation("begin", receipt, Instant.EPOCH));
            return null;
        }));
        assertThat(context.getNotebook(workspaceId)).isEqualTo(owned);
        assertThat(reviews.findRun(run.id()).orElseThrow().operations()).containsEntry("begin", receipt);
    }

    @Test
    void separatesConnectionWaitFromBoundedNotebookWrite()
            throws Exception
    {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
        RepositoryNotebook notebook = capturedNotebook();
        WriteTiming initial = measureWrite(() -> context.savePreparation(workspaceId, RepositoryNotebook.empty(), notebook));
        assertThat(context.getNotebook(workspaceId)).isEqualTo(notebook);
        long notebookBytes = settings.get(key()).orElseThrow().getBytes(UTF_8).length;
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<WriteTiming> waiting;
            try (Connection held = dataSource.getConnection()) {
                try (var statement = held.createStatement(); var result = statement.executeQuery("PRAGMA journal_mode")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualToIgnoringCase("wal");
                }
                waiting = executor.submit(() -> {
                    started.countDown();
                    return measureWrite(() -> {
                        callbackEntered.countDown();
                        context.setUseInReviews(workspaceId, 1, false);
                    });
                });
                assertThat(started.await(5, SECONDS)).isTrue();
                assertThatThrownBy(() -> waiting.get(150, MILLISECONDS)).isInstanceOf(TimeoutException.class);
                assertThat(callbackEntered.getCount()).isEqualTo(1);
                assertThat(pool.getHikariPoolMXBean().getThreadsAwaitingConnection()).isEqualTo(1);
            }
            WriteTiming contended = waiting.get(5, SECONDS);
            assertThat(context.getNotebook(workspaceId).entries()).isEqualTo(notebook.entries());
            assertThat(context.getNotebook(workspaceId).useInReviews()).isFalse();
            log.info("Repository context acceptance: {}", ImmutableMap.of(
                    "sample", "one synthetic notebook write and one write after a deliberately held pool connection; no remote work",
                    "notebookUtf8Bytes", Long.toString(notebookBytes),
                    "initialTransactionMicros", Long.toString(initial.totalMicros()),
                    "initialCallbackBeforeFlushMicros", Long.toString(initial.callbackMicros()),
                    "contendedConnectionAcquisitionMicros", Long.toString(contended.acquisitionMicros()),
                    "contendedCallbackBeforeFlushMicros", Long.toString(contended.callbackMicros()),
                    "contendedTransactionAfterAcquisitionMicros", Long.toString(contended.totalMicros() - contended.acquisitionMicros())));
        }
    }

    @Test
    void concurrentInputInspectionLeavesDatabaseFreeAndAdmitsOneWorkspaceOwner()
            throws Exception
    {
        Workspace workspace = workspaces.findById(workspaceId).orElseThrow();
        ContextInputReader inputs = mock(ContextInputReader.class);
        WorkspaceRepositoryService repositories = mock(WorkspaceRepositoryService.class);
        when(repositories.requireReadableRepository(workspaceId)).thenReturn(new WorkspaceRepositoryContext(workspaceId, Path.of(workspace.localRepositoryPath()),
                workspace.monitoredRepositoryId(), workspace.monitoredRepository(), OptionalLong.empty(), workspace.monitoredRepository(),
                "origin", "origin", "main", ImmutableList.of()));
        RepositoryContextPreparation preparation = new RepositoryContextPreparation(context, inputs, repositories);
        CountDownLatch inspecting = new CountDownLatch(2);
        CountDownLatch releaseInspection = new CountDownLatch(1);
        when(inputs.capture(any(), any(), any(), anyList())).thenAnswer(_ -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((ReentrantLock) WorkspaceOperationLocks.get(workspaceId)).isHeldByCurrentThread()).isFalse();
            inspecting.countDown();
            assertThat(releaseInspection.await(5, SECONDS)).isTrue();
            return ImmutableList.of(ContextInputFingerprint.create("project", ImmutableMap.of("git:README.md", "a".repeat(40)), ImmutableList.of()));
        });
        Execution first = execution(workspace, "first");
        Execution second = execution(workspace, "second");
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Plan> firstPlan = executor.submit(() -> preparation.plan(first, ImmutableList.of("project")));
            Future<Plan> secondPlan = executor.submit(() -> preparation.plan(second, ImmutableList.of("project")));
            long notebookTransactionMicros;
            try {
                assertThat(inspecting.await(5, SECONDS)).isTrue();
                WriteTiming duringInspection = measureWrite(() -> context.savePreparation(workspaceId, RepositoryNotebook.empty(), RepositoryNotebook.empty()));
                assertThat(firstPlan.isDone()).isFalse();
                assertThat(secondPlan.isDone()).isFalse();
                notebookTransactionMicros = duringInspection.totalMicros();
            }
            finally {
                releaseInspection.countDown();
            }
            Plan firstInputs = firstPlan.get(5, SECONDS);
            Plan secondInputs = secondPlan.get(5, SECONDS);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch race = new CountDownLatch(1);
            Future<Admission> firstAdmission = executor.submit(() -> beginTogether(preparation, first, firstInputs, ready, race));
            Future<Admission> secondAdmission = executor.submit(() -> beginTogether(preparation, second, secondInputs, ready, race));
            assertThat(ready.await(5, SECONDS)).isTrue();
            Stopwatch elapsed = Stopwatch.createStarted();
            race.countDown();
            List<Admission> admitted = ImmutableList.of(firstAdmission.get(5, SECONDS), secondAdmission.get(5, SECONDS));
            log.info("Repository context acceptance: {}", ImmutableMap.of(
                    "sample", "two concurrent preparation requests with deliberately blocked input capture; real SQLite notebook writes",
                    "notebookTransactionWhileTwoInputReadsBlockedMicros", Long.toString(notebookTransactionMicros),
                    "twoConcurrentAdmissionsMicros", Long.toString(elapsed.elapsed(MICROSECONDS))));
            assertThat(admitted).extracting(Admission::status).containsExactlyInAnyOrder(OWNED, JOINED);
            ActiveContextPreparation owner = context.getNotebook(workspaceId).activePreparation().orElseThrow();
            assertThat(admitted).allSatisfy(admission -> assertThat(admission.attempt()).contains(owner));
            assertThat(context.getNotebook(workspaceId).revision()).isZero();
        }
    }

    private Admission beginTogether(RepositoryContextPreparation preparation, Execution execution, Plan plan, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException
    {
        ready.countDown();
        assertThat(start.await(5, SECONDS)).isTrue();
        return WorkspaceOperationLocks.withLock(workspaceId, () -> unitOfWork.execute(() -> preparation.begin(execution, execution.turnId(),
                "sha256:" + "a".repeat(64), 0, plan)));
    }

    private static Execution execution(Workspace workspace, String id)
    {
        return new Execution(workspace.id(), workspace.monitoredRepository().fullName(), workspace.monitoredRepositoryId(), "a".repeat(40),
                "task-" + id, "turn-" + id, "key-" + id, Optional.empty(), Optional.empty(), ImmutableList.of(), ImmutableMap.of());
    }

    private WriteTiming measureWrite(Runnable mutation)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            Stopwatch total = Stopwatch.createStarted();
            AtomicLong acquisition = new AtomicLong();
            long callback = unitOfWork.execute(() -> {
                acquisition.set(total.elapsed(MICROSECONDS));
                Stopwatch elapsed = Stopwatch.createStarted();
                mutation.run();
                return elapsed.elapsed(MICROSECONDS);
            });
            return new WriteTiming(acquisition.get(), callback, total.elapsed(MICROSECONDS));
        });
    }

    private RepositoryNotebook capturedNotebook()
    {
        Workspace workspace = workspaces.findById(workspaceId).orElseThrow();
        String sha = "a".repeat(40);
        List<ContextSourceEvidence> evidence = IntStream.range(0, 11).mapToObj(index -> new ContextSourceEvidence("context-read:" + UUID.randomUUID(),
                new ContextReference.RepositoryFile(workspace.monitoredRepositoryId(), workspace.monitoredRepository(), "docs/source-" + index + ".md", sha),
                "Synthetic captured documentation for local storage measurement", "x".repeat(8192), ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(Instant.EPOCH), Optional.of(sha), Optional.empty(), false)).collect(ImmutableList.toImmutableList());
        ContextEntry entry = new ContextEntry("knowledge:project", KNOWLEDGE, "project", "Synthetic project overview", "Storage fixture", "b".repeat(1519),
                ImmutableList.of(new ContextClaim("Synthetic documented claim", ContextClaim.Basis.DOCUMENTED, ImmutableList.of(evidence.getFirst().id()))),
                ImmutableList.of(evidence.getFirst().id()), ContextEntryOrigin.GENERATED, true, 1);
        return new RepositoryNotebook(1, 1, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()), ImmutableList.of(entry),
                ImmutableList.of(), evidence, Optional.empty());
    }

    private record WriteTiming(long acquisitionMicros, long callbackMicros, long totalMicros) {}

    @Test
    void corruptSavedDataIsNotReplacedByReadOrMutation()
    {
        String corrupt = "{\"schemaVersion\":1,\"entries\":[";
        settings.put(key(), corrupt);

        assertThatThrownBy(() -> context.getNotebook(workspaceId)).isInstanceOf(ContextStorageException.class);
        assertThatThrownBy(() -> context.setUseInReviews(workspaceId, 0, false)).isInstanceOf(ContextStorageException.class);
        assertThat(settings.get(key())).contains(corrupt);
    }

    @Test
    void explicitDeletionRemovesOnlyTheOwningNotebook()
    {
        context.setUseInReviews(workspaceId, 0, false);
        settings.put("workspace.repositoryContext.unrelated", "retained");

        context.deleteWorkspaceData(workspaceId);

        assertThat(settings.get(key())).isEmpty();
        assertThat(settings.get("workspace.repositoryContext.unrelated")).contains("retained");
    }

    private String key()
    {
        return "workspace.repositoryContext." + workspaceId;
    }
}
