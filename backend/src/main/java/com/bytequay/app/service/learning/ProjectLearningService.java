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

import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    private final ProjectLearningStore store;
    private final WorkspaceRepositoryResolver repositories;
    private final WatchedRepoStore watchedRepos;
    private final DocumentIndexer indexer;
    private final MergedPrCatalog catalog;
    private final PrPriorityScorer scorer;
    private final ModuleCoverageSelector selector;
    private final PrEvidenceFetcher evidenceFetcher;
    private final PatResolver patResolver;
    private final ObjectMapper json;
    private final Set<String> activeJobs = ConcurrentHashMap.newKeySet();

    public ProjectLearningService(
            ProjectLearningStore store,
            WorkspaceRepositoryResolver repositories,
            WatchedRepoStore watchedRepos,
            DocumentIndexer indexer,
            MergedPrCatalog catalog,
            PrPriorityScorer scorer,
            ModuleCoverageSelector selector,
            PrEvidenceFetcher evidenceFetcher,
            PatResolver patResolver,
            ObjectMapper json)
    {
        this.store = requireNonNull(store, "store is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.indexer = requireNonNull(indexer, "indexer is null");
        this.catalog = requireNonNull(catalog, "catalog is null");
        this.scorer = requireNonNull(scorer, "scorer is null");
        this.selector = requireNonNull(selector, "selector is null");
        this.evidenceFetcher = requireNonNull(evidenceFetcher, "evidenceFetcher is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.json = requireNonNull(json, "json is null");
    }

    /**
     * Enqueue (or reuse) the learning run for a workspace repository.
     * Idempotent: a non-failed run is returned as-is, so a repeated trigger
     * never spawns a duplicate run. Safe to call after workspace-ready — the
     * work runs on a background virtual thread once the transaction commits.
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
        store.updateRun(id, "cataloging", null, current.get().catalogCursor(),
                current.get().countsJson(), null, null, Instant.now().toEpochMilli());
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

    // ── execution ───────────────────────────────────────────────────

    private void launch(String id)
    {
        if (!activeJobs.add(id)) {
            return;
        }
        Thread.startVirtualThread(() -> {
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
                // Deterministic Phase 2: rank + snapshot-pinned evidence. Runs on
                // the same background thread once the catalog is complete; leaves
                // the run terminal at 'caught-up' when the analysis queue drains.
                analyze(run, identity.fullName(), clone, head);
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
            store.updateRun(run.id(), "caught-up", head, writeCursor(outcome.cursor()),
                    counts(run), now, null, now);
            return true;
        }
        // Partial: visible via state + last_error, retryable via the
        // preserved cursor.
        store.updateRun(run.id(), "partial", head, writeCursor(outcome.cursor()),
                counts(run), null, outcome.error(), now);
        return false;
    }

    // ── analysis (Phase 2) ──────────────────────────────────────────

    /**
     * Rank cataloged PRs and build snapshot-pinned evidence bundles. Resumable:
     * selection persists 'selected' rows and analysis flips them to 'analyzed',
     * so a restart picks up the unfinished queue. Marks the run terminal at
     * 'caught-up' when the queue drains (a crash mid-analysis parks it at
     * 'analyzing', which {@link #recover()} re-launches).
     */
    private void analyze(ProjectLearningRun run, String repoFullName, Path clone, String head)
    {
        markState(run, "analyzing", head);
        String pat = patResolver.resolve(repoFullName);

        // Drain the whole catalog in SELECT_LIMIT waves: promote a
        // module-covering batch, analyze it, then re-select until no
        // 'cataloged' rows remain. Without the outer loop only the first 50
        // PRs of a large repo would ever be analyzed.
        while (selectBatch(run) > 0) {
            while (true) {
                var selected = store.selectedSources(run.workspaceId(), run.repo(), ANALYZE_BATCH);
                if (selected.isEmpty()) {
                    break;
                }
                for (RepoPrSource source : selected) {
                    analyzeOne(run, repoFullName, pat, clone, head, source);
                }
            }
        }

        long now = Instant.now().toEpochMilli();
        store.updateRun(run.id(), "caught-up", head, run.catalogCursor(),
                counts(run), now, null, now);
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

    private void analyzeOne(
            ProjectLearningRun run, String repoFullName, String pat, Path clone, String head,
            RepoPrSource source)
    {
        long now = Instant.now().toEpochMilli();
        try {
            PrEvidenceBundle bundle = evidenceFetcher.fetch(
                    pat, run.workspaceId(), repoFullName, source.prNumber(),
                    authorOf(source), clone, head);
            double score = scorer.refine(source, bundle, bundle.chains());
            store.persistEvidence(bundle, score, now);
            store.markAnalyzed(run.workspaceId(), run.repo(), source.prNumber(),
                    score, bundle.mergeSha(), now);
        }
        catch (RuntimeException e) {
            // Don't fail the whole run on one PR — mark it analyzed with the
            // pre-rank score so the queue drains and the run can complete.
            log.warn("evidence analysis failed for {}#{}: {}",
                    repoFullName, source.prNumber(), e.getMessage());
            store.markAnalyzed(run.workspaceId(), run.repo(), source.prNumber(),
                    source.priorityScore() == null ? 0.0 : source.priorityScore(), null, now);
        }
    }

    private String authorOf(RepoPrSource source)
    {
        return metaText(source, "author");
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
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("docsIndexed", store.countDocSections(run.workspaceId(), run.repo()));
        counts.put("cataloged", store.countCataloged(run.workspaceId(), run.repo()));
        counts.put("analyzed", store.countAnalyzed(run.workspaceId(), run.repo()));
        return write(counts);
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
