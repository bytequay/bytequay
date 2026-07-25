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
import com.bytequay.app.domain.MemoryItemConfidence;
import com.bytequay.app.domain.MemoryItemKind;
import com.bytequay.app.domain.MemoryItemOrigin;
import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.domain.MemoryItemSource;
import com.bytequay.app.repository.MemoryItemStore;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.service.concepts.ConceptKind;
import com.bytequay.app.service.concepts.ConceptRegistry;
import com.bytequay.app.service.concepts.ConceptScope;
import com.bytequay.app.service.concepts.ConceptSpec;
import com.bytequay.app.service.workspaces.MemoryItemService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Deterministic back half of the extraction pipeline: verifies currentness
 * against the pinned checkout, deduplicates equivalent lessons by merging
 * provenance, records conflicts without letting either side silently win,
 * applies the type-aware activation rules, and routes each lesson to the
 * canonical knowledge store, a workspace memory proposal, or the concept
 * registry. The model argues; this class decides.
 */
@Component
public class KnowledgeIngestor
{
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestor.class);

    /** Independent merged outcomes required to activate by confirmation. */
    private static final int INDEPENDENT_CONFIRMATIONS = 2;

    private final KnowledgeItemStore store;
    private final MemoryItemService memoryItems;
    private final ConceptRegistry concepts;
    private final ObjectMapper mapper;

    public KnowledgeIngestor(
            KnowledgeItemStore store,
            MemoryItemService memoryItems,
            ConceptRegistry concepts,
            ObjectMapper mapper)
    {
        this.store = requireNonNull(store, "store is null");
        this.memoryItems = requireNonNull(memoryItems, "memoryItems is null");
        this.concepts = requireNonNull(concepts, "concepts is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** What one PR's ingest produced — the analysis loop's quality bar counts
     *  {@code newCandidates} (fresh, non-duplicate, currently applicable). */
    public record IngestResult(int newCandidates, int merged, int memoryProposals) {}

    public IngestResult ingest(
            String workspaceId,
            PrEvidenceBundle bundle,
            List<ExtractedLesson> lessons,
            Path clone)
    {
        int fresh = 0;
        int merged = 0;
        int proposals = 0;
        for (ExtractedLesson lesson : lessons) {
            if ("workspace-memory".equals(lesson.route())) {
                proposeMemory(workspaceId, bundle, lesson);
                proposals++;
                continue;
            }
            if (ingestKnowledge(workspaceId, bundle, lesson, clone)) {
                fresh++;
            }
            else {
                merged++;
            }
        }
        return new IngestResult(fresh, merged, proposals);
    }

    /** Returns true when a new item was created, false when the lesson merged
     *  into an existing one. */
    private boolean ingestKnowledge(
            String workspaceId, PrEvidenceBundle bundle, ExtractedLesson lesson, Path clone)
    {
        String digest = KnowledgeItemStore.statementDigest(lesson.statement());
        KnowledgeItem existing = store.findByDigest(workspaceId, bundle.repo(), digest)
                .or(() -> lesson.duplicateOf() == null
                        ? Optional.empty()
                        : store.findById(lesson.duplicateOf())
                                .filter(item -> bundle.repo().equals(item.repo())))
                .orElse(null);
        long now = Instant.now().toEpochMilli();
        if (existing != null) {
            mergeInto(existing, bundle, lesson, clone, now);
            return false;
        }

        Currentness currentness = checkCurrentness(clone, lesson);
        String id = UUID.randomUUID().toString();
        boolean conflicted = !lesson.conflictsWith().isEmpty();
        String lifecycle = activateOnFirstSight(bundle, lesson, currentness, conflicted)
                ? KnowledgeItem.LIFECYCLE_ACTIVE
                : KnowledgeItem.LIFECYCLE_PENDING;
        KnowledgeItem item = new KnowledgeItem(
                id, workspaceId, bundle.repo(), lesson.kind(),
                lesson.title(), lesson.statement(), lesson.rationale(),
                lesson.audiences(), lesson.confidence(), lifecycle,
                currentness == Currentness.OK ? bundle.repoSha() : null,
                now, "pr-learning", digest,
                counters(lesson, currentness), now, now);
        store.insert(item, provenance(bundle, lesson), applicability(lesson));
        if (conflicted) {
            markExistingConflicts(lesson.conflictsWith(), id, now);
        }
        if (item.isActive() && "glossary".equals(lesson.kind())) {
            registerConcept(item);
        }
        return true;
    }

    /**
     * Equivalent lesson from another PR: add provenance instead of a new row,
     * and re-evaluate independent confirmation — the second distinct merged
     * outcome is what turns a pending item active.
     */
    private void mergeInto(
            KnowledgeItem existing, PrEvidenceBundle bundle, ExtractedLesson lesson,
            Path clone, long now)
    {
        store.addProvenance(existing.id(), provenance(bundle, lesson));
        if (!KnowledgeItem.LIFECYCLE_PENDING.equals(existing.lifecycle())) {
            return;
        }
        boolean conflicted = hasConflicts(existing) || !lesson.conflictsWith().isEmpty();
        if (conflicted) {
            return;
        }
        if (store.distinctPrSources(existing.id()) >= INDEPENDENT_CONFIRMATIONS
                && checkCurrentness(clone, lesson) != Currentness.FAILED) {
            store.setLifecycle(existing.id(), KnowledgeItem.LIFECYCLE_ACTIVE,
                    bundle.repoSha(), now);
            if ("glossary".equals(existing.kind())) {
                registerConcept(existing);
            }
        }
    }

    /**
     * Activation rule for a first-sighted lesson: a verified outcome chain
     * (concern → change → resolution → merge) in this PR, currentness
     * confirmed against the pinned checkout, and no live conflict. The four
     * restricted kinds additionally require the statement to be explicit in
     * the source language — a diff shape alone never activates them.
     */
    private static boolean activateOnFirstSight(
            PrEvidenceBundle bundle, ExtractedLesson lesson,
            Currentness currentness, boolean conflicted)
    {
        if (conflicted || currentness != Currentness.OK) {
            return false;
        }
        boolean verifiedChain = bundle.chains().stream().anyMatch(
                chain -> chain.addressed() && chain.resolved() && chain.merged());
        if (!verifiedChain) {
            return false;
        }
        return !ExtractedLesson.RESTRICTED_KINDS.contains(lesson.kind())
                || lesson.explicitSourceQuote();
    }

    // ── currentness ─────────────────────────────────────────────────

    private enum Currentness
    {
        /** At least one named path anchor exists in the pinned checkout. */
        OK,
        /** Anchors were named but none exist — likely reverted/replaced. */
        FAILED,
        /** No verifiable anchors; cannot activate automatically. */
        UNKNOWN,
    }

    /**
     * Path-level check against the pinned checkout. Symbols are recorded as
     * applicability but do not decide currentness on their own.
     */
    // ponytail: path-existence only; per-symbol grep inside anchors when
    // path-level checks prove too coarse.
    private static Currentness checkCurrentness(Path clone, ExtractedLesson lesson)
    {
        if (clone == null || lesson.paths().isEmpty()) {
            return Currentness.UNKNOWN;
        }
        Path root = clone.normalize();
        for (String candidate : lesson.paths()) {
            Path resolved = root.resolve(candidate).normalize();
            if (resolved.startsWith(root) && Files.exists(resolved)) {
                return Currentness.OK;
            }
        }
        return Currentness.FAILED;
    }

    // ── routing ─────────────────────────────────────────────────────

    private void proposeMemory(String workspaceId, PrEvidenceBundle bundle, ExtractedLesson lesson)
    {
        String text = lesson.rationale() == null || lesson.rationale().isBlank()
                ? lesson.statement()
                : lesson.statement() + " — " + lesson.rationale();
        memoryItems.propose(new MemoryItemStore.NewItem(
                MemoryItemScopeKind.WORKSPACE,
                workspaceId,
                "CONVENTION".equals(lesson.memoryKind())
                        ? MemoryItemKind.CONVENTION : MemoryItemKind.DECISION,
                text,
                List.of(MemoryItemSource.pr(bundle.repo() + "#" + bundle.prNumber())),
                confidence(lesson.confidence()),
                List.of(),
                MemoryItemOrigin.DISTILL));
    }

    private void registerConcept(KnowledgeItem item)
    {
        String name = item.title() == null || item.title().isBlank()
                ? item.statement() : item.title();
        List<String> aka = store.applicability(item.id()).stream()
                .filter(tag -> "concept".equals(tag.kind()))
                .map(KnowledgeItem.Applicability::value)
                .toList();
        concepts.registerRuntime(item.repo(), new ConceptSpec(
                name, aka, ConceptKind.NOUN, item.statement(),
                List.of(), List.of(), List.of(),
                ConceptScope.REPO, "knowledge:" + item.id()));
    }

    /** Re-register active glossary knowledge after a restart so learned
     *  terminology does not silently vanish from the concept tools. */
    @EventListener(ApplicationReadyEvent.class)
    public void reloadGlossaryConcepts()
    {
        for (KnowledgeItem item : store.listActiveGlossary()) {
            try {
                registerConcept(item);
            }
            catch (RuntimeException e) {
                log.warn("could not reload glossary concept {}: {}", item.id(), e.getMessage());
            }
        }
    }

    // ── provenance / applicability ──────────────────────────────────

    private static List<KnowledgeItem.Provenance> provenance(
            PrEvidenceBundle bundle, ExtractedLesson lesson)
    {
        List<KnowledgeItem.Provenance> out = new ArrayList<>();
        out.add(new KnowledgeItem.Provenance(
                "pr", bundle.repo() + "#" + bundle.prNumber(), bundle.mergeSha(), null,
                "https://github.com/" + bundle.repo() + "/pull/" + bundle.prNumber(), null));
        List<PrEvidenceBundle.EvidenceRef> refs = bundle.refs();
        for (int index : lesson.evidenceRefs()) {
            PrEvidenceBundle.EvidenceRef ref = refs.get(index);
            String sourceRef = ref.githubId() != null ? ref.githubId()
                    : ref.filePath() != null ? ref.filePath()
                    : ref.commitSha() != null ? ref.commitSha()
                    : "E" + (index + 1);
            out.add(new KnowledgeItem.Provenance(
                    ref.kind(), sourceRef, ref.commitSha(), ref.filePath(),
                    ref.url(), ref.contentDigest()));
        }
        return out;
    }

    private static List<KnowledgeItem.Applicability> applicability(ExtractedLesson lesson)
    {
        Set<KnowledgeItem.Applicability> out = new LinkedHashSet<>();
        lesson.modules().forEach(value -> out.add(new KnowledgeItem.Applicability("module", value)));
        lesson.paths().forEach(value -> out.add(new KnowledgeItem.Applicability("path", value)));
        lesson.symbols().forEach(value -> out.add(new KnowledgeItem.Applicability("symbol", value)));
        lesson.concepts().forEach(value -> out.add(new KnowledgeItem.Applicability("concept", value)));
        return List.copyOf(out);
    }

    // ── conflict bookkeeping ────────────────────────────────────────

    private void markExistingConflicts(List<String> conflictIds, String newItemId, long now)
    {
        for (String conflictId : conflictIds) {
            store.findById(conflictId).ifPresent(existing ->
                    store.setCounters(existing.id(),
                            withConflict(existing.countersJson(), newItemId), now));
        }
    }

    private boolean hasConflicts(KnowledgeItem item)
    {
        try {
            return mapper.readTree(item.countersJson() == null ? "{}" : item.countersJson())
                    .path("conflictsWith").size() > 0;
        }
        catch (IOException e) {
            return false;
        }
    }

    private String counters(ExtractedLesson lesson, Currentness currentness)
    {
        ObjectNode counters = mapper.createObjectNode();
        counters.put("confirmations", 1);
        if (!lesson.conflictsWith().isEmpty()) {
            ArrayNode conflicts = counters.putArray("conflictsWith");
            lesson.conflictsWith().forEach(conflicts::add);
        }
        if (currentness == Currentness.FAILED) {
            counters.put("possiblyStale", true);
        }
        return counters.toString();
    }

    private String withConflict(String countersJson, String conflictId)
    {
        try {
            ObjectNode counters = (ObjectNode) mapper.readTree(
                    countersJson == null || countersJson.isBlank() ? "{}" : countersJson);
            ArrayNode conflicts = counters.path("conflictsWith").isArray()
                    ? (ArrayNode) counters.get("conflictsWith")
                    : counters.putArray("conflictsWith");
            for (JsonNode node : conflicts) {
                if (conflictId.equals(node.asText())) {
                    return counters.toString();
                }
            }
            conflicts.add(conflictId);
            return counters.toString();
        }
        catch (IOException e) {
            return countersJson;
        }
    }

    private static MemoryItemConfidence confidence(String value)
    {
        return switch (value == null ? "low" : value) {
            case "high" -> MemoryItemConfidence.HIGH;
            case "medium" -> MemoryItemConfidence.MEDIUM;
            default -> MemoryItemConfidence.LOW;
        };
    }
}
