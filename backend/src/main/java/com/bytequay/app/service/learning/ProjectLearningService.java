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
package com.bytequay.app.service.learning;

import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.localpr.PrMergedEvent;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

/**
 * Durable, restart-safe project-learning coordinator. A verified clone
 * enqueues one run per workspace repository; the run indexes local docs,
 * builds the bounded project capsule, then catalogs the complete merged-PR
 * history in the background — never blocking workspace-ready and never
 * calling a model in this phase.
 *
 * <p>It is a workspace-owned background run, not a coding Task: it creates no
 * branch, worktree, thread, or PR, and it does not borrow the durable
 * {@code ThreadTurn} queue. Durability is its own {@code repo_learning_run}
 * row plus the persisted {@link CatalogCursor}; a restart resumes incomplete
 * work via {@link #recover()} rather than restarting from page one.
 */
@Service
public class ProjectLearningService
{
    private static final Logger log = LoggerFactory.getLogger(ProjectLearningService.class);
    private static final int EXTRACTOR_VERSION = 1;

    /** Cataloged rows pre-ranked per analysis pass before module-coverage selection. */
    private static final int PRERANK_LIMIT = 500;
    /** PRs promoted to 'selected' per pass — bounds the evidence-fetch fan-out. */
    private static final int SELECT_LIMIT = 50;
    /** Selected rows analyzed per pass; the loop repeats until the queue drains. */
    private static final int ANALYZE_BATCH = 25;
    /** Initial quality bar: deep-analyze at least this many top-ranked PRs
     *  (when the repository has that many) before diminishing yield may stop
     *  the initial pass. */
    private static final int INITIAL_DEEP_TARGET = 200;
    /** Diminishing yield: two consecutive waves each producing fewer new,
     *  non-duplicate, currently-applicable candidates than this stop the
     *  initial pass; the long tail stays cataloged for backfill. */
    private static final int WAVE_YIELD_FLOOR = 5;
    /** Consecutive extraction failures that park the run retryable. */
    private static final int MAX_CONSECUTIVE_EXTRACTION_FAILURES = 3;
    /** Nearby existing knowledge shown to the extractor for dedup/conflicts. */
    private static final int EXISTING_CONTEXT_LIMIT = 24;

    private final ProjectLearningStore store;
    private final WorkspaceRepositoryResolver repositories;
    private final WatchedRepoStore watchedRepos;
    private final WorkspaceStore workspaceStore;
    private final DocumentIndexer indexer;
    private final MergedPrCatalog catalog;
    private final PrPriorityScorer scorer;
    private final ModuleCoverageSelector selector;
    private final PrEvidenceFetcher evidenceFetcher;
    private final LessonExtractor extractor;
    private final KnowledgeIngestor ingestor;
    private final KnowledgeItemStore knowledge;
    private final PatResolver patResolver;
    private final ObjectMapper json;
    private final Executor executor;
    private final Set<String> activeJobs = ConcurrentHashMap.newKeySet();

    public ProjectLearningService(
            ProjectLearningStore store,
            WorkspaceRepositoryResolver repositories,
            WatchedRepoStore watchedRepos,
            WorkspaceStore workspaceStore,
            DocumentIndexer indexer,
            MergedPrCatalog catalog,
            PrPriorityScorer scorer,
            ModuleCoverageSelector selector,
            PrEvidenceFetcher evidenceFetcher,
            LessonExtractor extractor,
            KnowledgeIngestor ingestor,
            KnowledgeItemStore knowledge,
            PatResolver patResolver,
            ObjectMapper json,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor)
    {
        this.store = requireNonNull(store, "store is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.workspaceStore = requireNonNull(workspaceStore, "workspaceStore is null");
        this.indexer = requireNonNull(indexer, "indexer is null");
        this.catalog = requireNonNull(catalog, "catalog is null");
        this.scorer = requireNonNull(scorer, "scorer is null");
        this.selector = requireNonNull(selector, "selector is null");
        this.evidenceFetcher = requireNonNull(evidenceFetcher, "evidenceFetcher is null");
        this.extractor = requireNonNull(extractor, "extractor is null");
        this.ingestor = requireNonNull(ingestor, "ingestor is null");
        this.knowledge = requireNonNull(knowledge, "knowledge is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.json = requireNonNull(json, "json is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    /**
     * Enqueue (or reuse) the learning run for a workspace repository.
     * Idempotent: a non-failed run is returned as-is, so a repeated trigger
     * never spawns a duplicate run. Safe to call after workspace-ready — the
     * work runs on the bounded application executor once the transaction commits.
     */
    @Transactional
    public ProjectLearningRun enqueue(String workspaceId, String repo, String trigger)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        requireNonNull(repo, "repo is null");
        Optional<ProjectLearningRun> existing = store.latestRun(workspaceId, repo);
        if (existing.isPresent() && !"failed".equals(existing.get().state())) {
            return existing.get();
        }
        long now = Instant.now().toEpochMilli();
        ProjectLearningRun run = new ProjectLearningRun(
                UUID.randomUUID().toString(), workspaceId, repo, trigger,
                "queued", null, null, "{}", EXTRACTOR_VERSION, now, now, null, null);
        store.insertRun(run);
        launchAfterCommit(run.id());
        return run;
    }

    /** Resume a partial/failed run from its persisted cursor. */
    @Transactional
    public Optional<ProjectLearningRun> retry(String id)
    {
        Optional<ProjectLearningRun> current = store.findRun(id);
        if (current.isEmpty()) {
            return current;
        }
        ProjectLearningRun run = current.get();
        store.requeueIncompleteEvidence(run.workspaceId(), run.repo());
        store.updateRun(id, "cataloging", null, run.catalogCursor(),
                run.countsJson(), null, null, Instant.now().toEpochMilli());
        launchAfterCommit(id);
        return store.findRun(id);
    }

    public Optional<ProjectLearningRun> find(String id)
    {
        return store.findRun(id);
    }

    public Optional<ProjectLearningRun> latest(String workspaceId, String repo)
    {
        return store.latestRun(workspaceId, repo);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover()
    {
        store.resumableRunIds().forEach(this::launch);
    }

    /** Pause a live run: analysis stops at the next wave boundary and the
     *  restart recovery leaves it alone until Resume (retry). */
    public Optional<ProjectLearningRun> pause(String workspaceId, String repo)
    {
        Optional<ProjectLearningRun> run = store.latestRun(workspaceId, repo);
        run.ifPresent(current -> store.updateRun(current.id(), "paused",
                null, current.catalogCursor(), current.countsJson(), null, null,
                Instant.now().toEpochMilli()));
        return store.latestRun(workspaceId, repo);
    }

    /**
     * Re-open a completed catalog from its last covered day through today.
     * The overlap is intentional: merge-date windows are day-granular, so
     * querying the checkpoint day again catches merges that landed after the
     * previous pass while the source upsert keeps the operation idempotent.
     */
    @Transactional
    public Optional<ProjectLearningRun> refreshCompleted(String id)
    {
        Optional<ProjectLearningRun> found = store.findRun(id);
        if (found.isEmpty() || !"caught-up".equals(found.get().state())) {
            return found;
        }
        ProjectLearningRun run = found.get();
        store.requeueIncompleteEvidence(run.workspaceId(), run.repo());
        store.updateRun(run.id(), "cataloging", run.snapshotSha(),
                writeCursor(incrementalCursor(run)), run.countsJson(), null, null,
                Instant.now().toEpochMilli());
        launchAfterCommit(run.id());
        return store.findRun(run.id());
    }

    // ── incremental learning (Phase 5) ──────────────────────────────

    @EventListener
    public void onPrMerged(PrMergedEvent event)
    {
        onMergedPr(event.repo(), event.remotePrNumber(), event.title(), event.author());
    }

    /**
     * One merged PR was observed by the canonical PR sync (or a user-gated
     * merge). Fan out to every workspace that has learned this repository —
     * asynchronously, so learning can never block the merge path. Idempotent
     * by (workspace, repo, PR, source digest, extractor version).
     */
    public void onMergedPr(String repoFullName, int prNumber, String title, String author)
    {
        if (repoFullName == null || repoFullName.isBlank() || prNumber <= 0) {
            return;
        }
        executor.execute(() -> {
            for (Workspace workspace : workspaceStore.listWorkspaces()) {
                try {
                    WorkspaceRepositoryResolver.RepositoryIdentity identity =
                            repositories.resolve(workspace.id());
                    if (!repoFullName.equals(identity.fullName())) {
                        continue;
                    }
                    if (store.latestRun(workspace.id(), repoFullName).isEmpty()) {
                        continue;       // repository was never learned
                    }
                    learnOne(workspace.id(), repoFullName, prNumber, title, author, "merge");
                }
                catch (RuntimeException e) {
                    log.warn("merge-triggered learning failed for {}#{} in workspace {}: {}",
                            repoFullName, prNumber, workspace.id(), e.getMessage());
                }
            }
        });
    }

    /**
     * Catalog + analyze one PR now. The source digest covers the fields a
     * re-review would change, so the same source/extractor never re-runs and
     * a changed source supersedes the earlier analysis.
     */
    void learnOne(
            String workspaceId, String repo, int prNumber,
            String title, String author, String trigger)
    {
        String digest = MergedPrCatalog.sha256(
                "merge|" + prNumber + "|" + nullToEmpty(title) + "|" + nullToEmpty(author));
        Optional<RepoPrSource> existing = store.findPrSource(workspaceId, repo, prNumber);
        if (existing.isPresent()
                && "analyzed".equals(existing.get().analysisState())
                && digest.equals(existing.get().sourceDigest())
                && existing.get().extractorVersion() == EXTRACTOR_VERSION) {
            return;
        }
        ProjectLearningRun run = store.latestRun(workspaceId, repo).orElse(null);
        if (run == null) {
            return;
        }
        WorkspaceRepositoryResolver.RepositoryIdentity identity =
                repositories.resolve(workspaceId);
        Path clone = clonePath(identity.owner(), identity.repo());
        String head = clone == null ? null : headSha(clone);
        String metadata = write(Map.of(
                "title", nullToEmpty(title), "author", nullToEmpty(author)));
        store.upsertPrSource(new RepoPrSource(
                workspaceId, repo, prNumber, null, null, metadata, "{}",
                digest, null, "selected", EXTRACTOR_VERSION, null, null));
        store.resetForAnalysis(workspaceId, repo, prNumber);
        String pat = patResolver.resolve(repo);
        RepoPrSource source = store.findPrSource(workspaceId, repo, prNumber).orElseThrow();
        try {
            analyzeOne(run, repo, pat, clone, head, source);
        }
        catch (LessonExtractor.ExtractionUnavailableException e) {
            log.warn("merge-triggered extraction unavailable for {}#{}: {}",
                    repo, prNumber, e.getMessage());
        }
        touchCounts(run);
    }

    /**
     * Daily backfill for a run parked at 'useful': analyze up to {@code cap}
     * more cataloged PRs, flipping to 'caught-up' when the catalog drains.
     * Failures leave the run in its prior state for the next day's pass.
     */
    void backfill(String workspaceId, String repo, int cap)
    {
        ProjectLearningRun run = store.latestRun(workspaceId, repo).orElse(null);
        if (run == null || !"useful".equals(run.state())) {
            return;
        }
        WorkspaceRepositoryResolver.RepositoryIdentity identity =
                repositories.resolve(workspaceId);
        Path clone = clonePath(identity.owner(), identity.repo());
        String head = clone == null ? null : headSha(clone);
        String pat = patResolver.resolve(repo);
        int analyzed = 0;
        store.requeueIncompleteEvidence(workspaceId, repo);
        if (selectBatch(run) == 0) {
            long now = Instant.now().toEpochMilli();
            store.updateRun(run.id(), "caught-up", head, run.catalogCursor(),
                    counts(run), now, null, now);
            return;
        }
        while (analyzed < cap) {
            var selected = store.selectedSources(workspaceId, repo,
                    Math.min(ANALYZE_BATCH, cap - analyzed));
            if (selected.isEmpty()) {
                break;
            }
            for (RepoPrSource source : selected) {
                try {
                    analyzeOne(run, repo, pat, clone, head, source);
                }
                catch (LessonExtractor.ExtractionUnavailableException e) {
                    log.warn("backfill extraction unavailable for {}: {}",
                            repo, e.getMessage());
                    touchCounts(run);
                    return;
                }
                analyzed++;
            }
        }
        touchCounts(run);
    }

    private void touchCounts(ProjectLearningRun run)
    {
        ProjectLearningRun current = store.findRun(run.id()).orElse(run);
        store.updateRun(current.id(), current.state(), current.snapshotSha(),
                current.catalogCursor(), counts(current), current.completedAtMs(),
                current.lastError(), Instant.now().toEpochMilli());
    }

    // ── execution ───────────────────────────────────────────────────

    private void launch(String id)
    {
        if (!activeJobs.add(id)) {
            return;
        }
        executor.execute(() -> {
            try {
                execute(id);
            }
            finally {
                activeJobs.remove(id);
            }
        });
    }

    private void launchAfterCommit(String id)
    {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            launch(id);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization()
                {
                    @Override
                    public void afterCommit()
                    {
                        launch(id);
                    }
                });
    }

    /** Package-private so tests can drive the run synchronously. */
    void execute(String id)
    {
        ProjectLearningRun run = store.findRun(id).orElse(null);
        if (run == null
                || (!run.isLive() && !"partial".equals(run.state()) && !"analyzing".equals(run.state()))) {
            return;
        }
        try {
            WorkspaceRepositoryResolver.RepositoryIdentity identity =
                    repositories.resolve(run.workspaceId());
            Path clone = clonePath(identity.owner(), identity.repo());
            if (clone == null) {
                fail(run, "workspace has no verified clone to learn from");
                return;
            }
            String head = headSha(clone);

            // Only (re)index docs when the catalog hasn't already started —
            // a resumed run picks up mid-catalog and must not redo indexing.
            if (run.catalogCursor() == null) {
                markState(run, "indexing", head);
                DocumentIndexer.IndexResult docs =
                        indexer.index(run.workspaceId(), run.repo(), clone, head);
                store.upsertCapsule(run.workspaceId(), run.repo(),
                        docs.capsuleMd(), docs.sourceDigest(), Instant.now().toEpochMilli());
            }

            boolean caughtUp = catalogHistory(run, identity.fullName(), head);
            if (caughtUp) {
                // Re-read the run so analysis carries the cursor the catalog
                // just persisted instead of writing the stale one back.
                ProjectLearningRun cataloged = store.findRun(id).orElse(run);
                analyze(cataloged, identity.fullName(), clone, head);
            }
        }
        catch (RuntimeException e) {
            log.warn("project learning run {} failed: {}", id, e.getMessage());
            fail(run, e.getMessage() == null ? "Project learning failed" : e.getMessage());
        }
    }

    /** Returns true when the catalog reached caught-up (analysis may proceed). */
    private boolean catalogHistory(ProjectLearningRun run, String repoFullName, String head)
    {
        String pat = patResolver.resolve(repoFullName);
        CatalogCursor start = run.catalogCursor() == null
                ? MergedPrCatalog.initialCursor(LocalDate.now())
                : readCursor(run.catalogCursor());
        markCataloging(run, head, start);

        MergedPrCatalog.Outcome outcome = catalog.catalog(
                run.workspaceId(), repoFullName, pat, run.extractorVersion(), start,
                new MergedPrCatalog.Sink()
                {
                    @Override
                    public void record(RepoPrSource source)
                    {
                        store.upsertPrSource(source);
                    }

                    @Override
                    public void checkpoint(CatalogCursor cursor)
                    {
                        // Persist the cursor after every page so a restart
                        // resumes the unfinished window at its saved page.
                        store.updateRun(run.id(), "cataloging", head,
                                writeCursor(cursor), counts(run), null, null,
                                Instant.now().toEpochMilli());
                    }
                });

        long now = Instant.now().toEpochMilli();
        if (outcome.state() == MergedPrCatalog.State.CAUGHT_UP) {
            // Hand off to analysis without a transient terminal state — an
            // observer must never see 'caught-up' while work is still queued.
            store.updateRun(run.id(), "analyzing", head, writeCursor(outcome.cursor()),
                    counts(run), null, null, now);
            return true;
        }
        // Partial: visible via state + last_error, retryable via the
        // preserved cursor.
        store.updateRun(run.id(), "partial", head, writeCursor(outcome.cursor()),
                counts(run), null, outcome.error(), now);
        return false;
    }

    // ── analysis (Phases 2 + 3) ─────────────────────────────────────

    /**
     * Rank cataloged PRs, build snapshot-pinned evidence bundles, and distill
     * lessons from each. Resumable: selection persists 'selected' rows and
     * analysis flips them to 'analyzed', so a restart picks up the unfinished
     * queue, and the recent per-wave yield history rides in counts_json so
     * the quality bar keeps its memory across restarts.
     *
     * <p>Terminal states: 'caught-up' when the catalog drains; 'useful' when
     * the initial quality bar is met with rows remaining (backfill continues
     * incrementally later); 'partial' when extraction is unavailable or keeps
     * failing, leaving the queue intact and the run retryable.
     */
    private void analyze(ProjectLearningRun run, String repoFullName, Path clone, String head)
    {
        markState(run, "analyzing", head);
        String pat = patResolver.resolve(repoFullName);
        List<Integer> recentWaves = readRecentWaves(run.id());
        int consecutiveFailures = 0;

        while (true) {
            if (paused(run.id())) {
                return;
            }
            if (qualityBarMet(run, recentWaves)) {
                long now = Instant.now().toEpochMilli();
                store.updateRun(run.id(), "useful", head, run.catalogCursor(),
                        counts(run, recentWaves), now, null, now);
                return;
            }
            if (selectBatch(run) == 0) {
                break;
            }
            int waveLessons = 0;
            while (true) {
                var selected = store.selectedSources(run.workspaceId(), run.repo(), ANALYZE_BATCH);
                if (selected.isEmpty()) {
                    break;
                }
                for (RepoPrSource source : selected) {
                    Integer fresh;
                    try {
                        fresh = analyzeOne(run, repoFullName, pat, clone, head, source);
                    }
                    catch (LessonExtractor.ExtractionUnavailableException e) {
                        // No usable provider/key: leave the queue 'selected'
                        // and park retryable rather than draining lessonless.
                        parkPartial(run, head, recentWaves, e.getMessage());
                        return;
                    }
                    if (fresh == null) {
                        consecutiveFailures++;
                        if (consecutiveFailures >= MAX_CONSECUTIVE_EXTRACTION_FAILURES) {
                            parkPartial(run, head, recentWaves,
                                    "extraction failed " + consecutiveFailures
                                            + " times in a row");
                            return;
                        }
                    }
                    else {
                        consecutiveFailures = 0;
                        waveLessons += fresh;
                    }
                }
            }
            recentWaves.add(waveLessons);
            while (recentWaves.size() > 2) {
                recentWaves.removeFirst();
            }
            store.updateRun(run.id(), "analyzing", head, run.catalogCursor(),
                    counts(run, recentWaves), null, null, Instant.now().toEpochMilli());
        }

        long now = Instant.now().toEpochMilli();
        store.updateRun(run.id(), "caught-up", head, run.catalogCursor(),
                counts(run, recentWaves), now, null, now);
    }

    /**
     * The initial deep-analysis bar: at least {@link #INITIAL_DEEP_TARGET}
     * top-ranked PRs analyzed (a smaller catalog just drains) and two
     * consecutive waves under {@link #WAVE_YIELD_FLOOR} fresh candidates.
     */
    // ponytail: module-coverage (>=5 analyzed per major module) biases
    // selection via ModuleCoverageSelector but is not yet a stop condition.
    private boolean qualityBarMet(ProjectLearningRun run, List<Integer> recentWaves)
    {
        if (store.countAnalyzed(run.workspaceId(), run.repo()) < INITIAL_DEEP_TARGET) {
            return false;
        }
        return recentWaves.size() >= 2
                && recentWaves.get(recentWaves.size() - 1) < WAVE_YIELD_FLOOR
                && recentWaves.get(recentWaves.size() - 2) < WAVE_YIELD_FLOOR;
    }

    private void parkPartial(
            ProjectLearningRun run, String head, List<Integer> recentWaves, String error)
    {
        log.warn("project learning run {} parked partial: {}", run.id(), error);
        store.updateRun(run.id(), "partial", head, run.catalogCursor(),
                counts(run, recentWaves), null, error, Instant.now().toEpochMilli());
    }

    /** User-requested pause wins over any in-flight wave. */
    private boolean paused(String runId)
    {
        return store.findRun(runId)
                .map(current -> "paused".equals(current.state()))
                .orElse(true);
    }

    /**
     * Pre-rank cataloged rows and promote a module-covering batch to 'selected'.
     * Returns the number of rows promoted; 0 means the catalog is drained, which
     * stops the analyze loop.
     */
    private int selectBatch(ProjectLearningRun run)
    {
        var cataloged = store.catalogedSources(run.workspaceId(), run.repo(), PRERANK_LIMIT);
        if (cataloged.isEmpty()) {
            return 0;
        }
        Map<Integer, Double> scoreByPr = new LinkedHashMap<>();
        var candidates = new ArrayList<ModuleCoverageSelector.Candidate>();
        for (RepoPrSource source : cataloged) {
            double pre = scorer.preRank(source);
            scoreByPr.put(source.prNumber(), pre);
            candidates.add(new ModuleCoverageSelector.Candidate(
                    source.prNumber(), pre, modulesOf(source)));
        }
        int promoted = 0;
        for (int prNumber : selector.select(candidates, SELECT_LIMIT)) {
            store.markSelected(run.workspaceId(), run.repo(), prNumber,
                    scoreByPr.getOrDefault(prNumber, 0.0));
            promoted++;
        }
        return promoted;
    }

    /**
     * Evidence + extraction for one PR. Returns the number of fresh (new,
     * non-duplicate) knowledge candidates it produced, or null when the
     * extraction call itself failed so the caller can count consecutive
     * failures. Throws {@link LessonExtractor.ExtractionUnavailableException}
     * when no provider can be used at all.
     */
    private Integer analyzeOne(
            ProjectLearningRun run, String repoFullName, String pat, Path clone, String head,
            RepoPrSource source)
    {
        long now = Instant.now().toEpochMilli();
        PrEvidenceBundle bundle;
        double score;
        try {
            bundle = evidenceFetcher.fetch(
                    pat, run.workspaceId(), repoFullName, source.prNumber(),
                    authorOf(source), titleOf(source), clone, head);
            score = scorer.refine(source, bundle, bundle.chains());
            store.persistEvidence(bundle, score, now);
        }
        catch (RuntimeException e) {
            // Don't fail the whole run on one PR — mark it analyzed with the
            // pre-rank score so the queue drains and the run can complete.
            log.warn("evidence analysis failed for {}#{}: {}",
                    repoFullName, source.prNumber(), e.getMessage());
            store.markAnalyzed(run.workspaceId(), run.repo(), source.prNumber(),
                    source.priorityScore() == null ? 0.0 : source.priorityScore(), null, now,
                    "evidence: " + e.getMessage());
            return 0;
        }

        try {
            List<KnowledgeItem> existing = existingContext(run.workspaceId(), run.repo());
            List<ExtractedLesson> lessons =
                    extractor.extract(run.workspaceId(), bundle, existing);
            KnowledgeIngestor.IngestResult result =
                    ingestor.ingest(run.workspaceId(), bundle, lessons, clone);
            store.markAnalyzed(run.workspaceId(), run.repo(), source.prNumber(),
                    score, bundle.mergeSha(), Instant.now().toEpochMilli());
            return result.newCandidates();
        }
        catch (LessonExtractor.ExtractionFailedException e) {
            log.warn("lesson extraction failed for {}#{}: {}",
                    repoFullName, source.prNumber(), e.getMessage());
            store.markAnalyzed(run.workspaceId(), run.repo(), source.prNumber(),
                    score, bundle.mergeSha(), Instant.now().toEpochMilli(),
                    "extraction: " + e.getMessage());
            return null;
        }
    }

    /** Recent knowledge for the extractor's dedup/conflict context: pending
     *  and active learned rows for this repository, newest first. */
    private List<KnowledgeItem> existingContext(String workspaceId, String repo)
    {
        List<KnowledgeItem> out = new ArrayList<>();
        out.addAll(knowledge.listByLifecycle(workspaceId, KnowledgeItem.LIFECYCLE_ACTIVE));
        out.addAll(knowledge.listByLifecycle(workspaceId, KnowledgeItem.LIFECYCLE_PENDING));
        return out.stream()
                .filter(item -> repo.equals(item.repo()))
                .limit(EXISTING_CONTEXT_LIMIT)
                .toList();
    }

    private String authorOf(RepoPrSource source)
    {
        return metaText(source, "author");
    }

    private String titleOf(RepoPrSource source)
    {
        return metaText(source, "title");
    }

    /**
     * Coarse module hints from the head branch name, for coverage selection.
     * Best-effort only: changed-file paths aren't available before evidence
     * fetch, so this leans on the branch's leading token. Branches with unique
     * per-PR names (e.g. {@code chenjian2664-patch-1}) yield unique modules, in
     * which case coverage selection degrades toward pure priority ranking.
     */
    private Set<String> modulesOf(RepoPrSource source)
    {
        String headRef = metaText(source, "headRef");
        if (headRef == null || headRef.isBlank()) {
            return Set.of();
        }
        // "feature/scheduler/fix" -> "scheduler"; "fix-parser" -> "fix".
        String[] parts = headRef.split("[/_-]");
        for (String part : parts) {
            if (!part.isBlank()) {
                return Set.of(part.toLowerCase(Locale.ROOT));
            }
        }
        return Set.of();
    }

    private String metaText(RepoPrSource source, String field)
    {
        try {
            var node = json.readTree(source.metadataJson() == null ? "{}" : source.metadataJson());
            var value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        }
        catch (IOException e) {
            return null;
        }
    }

    // ── state helpers ───────────────────────────────────────────────

    private void markState(ProjectLearningRun run, String state, String head)
    {
        store.updateRun(run.id(), state, head, run.catalogCursor(),
                counts(run), null, null, Instant.now().toEpochMilli());
    }

    private void markCataloging(ProjectLearningRun run, String head, CatalogCursor cursor)
    {
        store.updateRun(run.id(), "cataloging", head, writeCursor(cursor),
                counts(run), null, null, Instant.now().toEpochMilli());
    }

    private void fail(ProjectLearningRun run, String message)
    {
        store.updateRun(run.id(), "failed", null, run.catalogCursor(),
                counts(run), null, message, Instant.now().toEpochMilli());
    }

    private String counts(ProjectLearningRun run)
    {
        return counts(run, readRecentWaves(run.id()));
    }

    private String counts(ProjectLearningRun run, List<Integer> recentWaves)
    {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("docsIndexed", store.countDocSections(run.workspaceId(), run.repo()));
        counts.put("cataloged", store.countCataloged(run.workspaceId(), run.repo()));
        counts.put("analyzed", store.countAnalyzed(run.workspaceId(), run.repo()));
        counts.put("lessons", knowledge.countByCreator(
                run.workspaceId(), run.repo(), "pr-learning"));
        counts.put("pendingLessons", knowledge.countPending(run.workspaceId(), run.repo()));
        counts.put("recentWaves", recentWaves);
        return write(counts);
    }

    /** The last wave yields, restored from the persisted counts so the
     *  diminishing-yield bar keeps its memory across restarts. */
    private List<Integer> readRecentWaves(String runId)
    {
        List<Integer> waves = new ArrayList<>();
        String countsJson = store.findRun(runId)
                .map(ProjectLearningRun::countsJson)
                .orElse(null);
        if (countsJson == null || countsJson.isBlank()) {
            return waves;
        }
        try {
            for (JsonNode wave : json.readTree(countsJson).path("recentWaves")) {
                waves.add(wave.asInt());
            }
        }
        catch (IOException e) {
            return new ArrayList<>();
        }
        return waves;
    }

    private Path clonePath(String owner, String repo)
    {
        return watchedRepos.find(owner, repo)
                .map(WatchedRepo::localClonePath)
                .filter(p -> p != null && Files.isDirectory(Path.of(p)))
                .map(Path::of)
                .orElse(null);
    }

    /** Resolve HEAD by reading {@code .git/HEAD} — pure IO, no subprocess. */
    private static String headSha(Path clone)
    {
        try {
            Path gitHead = clone.resolve(".git").resolve("HEAD");
            if (!Files.isRegularFile(gitHead)) {
                return null;
            }
            String head = Files.readString(gitHead, StandardCharsets.UTF_8).trim();
            if (head.startsWith("ref:")) {
                Path ref = clone.resolve(".git").resolve(head.substring(4).trim());
                return Files.isRegularFile(ref)
                        ? Files.readString(ref, StandardCharsets.UTF_8).trim() : head;
            }
            return head;
        }
        catch (IOException e) {
            return null;
        }
    }

    private CatalogCursor readCursor(String value)
    {
        try {
            return json.readValue(value, CatalogCursor.class);
        }
        catch (IOException e) {
            throw new IllegalStateException("corrupt catalog cursor", e);
        }
    }

    private CatalogCursor incrementalCursor(ProjectLearningRun run)
    {
        LocalDate today = LocalDate.now();
        if (run.catalogCursor() == null) {
            return MergedPrCatalog.initialCursor(today);
        }
        LocalDate lastCovered = readCursor(run.catalogCursor()).partitions().stream()
                .map(CatalogCursor.Partition::to)
                .map(LocalDate::parse)
                .max(LocalDate::compareTo)
                .orElse(today);
        if (lastCovered.isAfter(today)) {
            lastCovered = today;
        }
        return new CatalogCursor(List.of(new CatalogCursor.Partition(
                lastCovered.toString(), today.toString(), 1, false)));
    }

    private String writeCursor(CatalogCursor cursor)
    {
        return write(cursor);
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize learning state", e);
        }
    }
}
