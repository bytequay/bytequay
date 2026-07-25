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
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.bytequay.app.service.workspaces;

import com.bytequay.app.beans.workspace.BrainBlockDto;
import com.bytequay.app.beans.workspace.DistillOperationDto;
import com.bytequay.app.beans.workspace.DistillRunDto;
import com.bytequay.app.beans.workspace.KBEntryDto;
import com.bytequay.app.beans.workspace.WorkspaceMemoryDto;
import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.domain.MemoryItem;
import com.bytequay.app.domain.MemoryItemConfidence;
import com.bytequay.app.domain.MemoryItemKind;
import com.bytequay.app.domain.MemoryItemOrigin;
import com.bytequay.app.domain.MemoryItemScopeKind;
import com.bytequay.app.domain.MemoryItemSource;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceMemoryProposal;
import com.bytequay.app.repository.MemoryItemStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.service.learning.KnowledgeIngestor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Structured workspace brain, audience-scoped knowledge base, and reversible
 * distillation previews. {@code memory_md} remains a generated compatibility
 * mirror while applied {@link MemoryItem} rows are the durable brain blocks.
 */
@Service
public class WorkspaceKnowledgeService
{
    private static final Set<String> AUDIENCES = Set.of(
            "plan", "dev", "review", "ci-fix");
    private static final Set<String> DECISIONS = Set.of(
            "accepted", "edited", "skipped");
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {};
    private static final TypeReference<List<DistillOperationDto>> OPERATIONS = new TypeReference<>() {};
    private static final TypeReference<List<InverseOperation>> INVERSES = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WorkspaceService workspaces;
    private final WorkspaceConfigurationService configuration;
    private final MemoryItemService memoryItems;
    private final MemoryItemStore memoryStore;
    private final KnowledgeItemStore knowledgeStore;
    private final KnowledgeIngestor ingestor;
    private final WorkspaceRepositoryResolver repositories;
    private final WatchedRepoStore watchedRepos;
    private final WorkspaceMemoryDistiller distiller;
    private final WorkspaceMemoryProposalParser proposalParser;

    public WorkspaceKnowledgeService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            WorkspaceService workspaces,
            WorkspaceConfigurationService configuration,
            MemoryItemService memoryItems,
            MemoryItemStore memoryStore,
            KnowledgeItemStore knowledgeStore,
            KnowledgeIngestor ingestor,
            WorkspaceRepositoryResolver repositories,
            WatchedRepoStore watchedRepos,
            WorkspaceMemoryDistiller distiller,
            WorkspaceMemoryProposalParser proposalParser)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.configuration = requireNonNull(configuration, "configuration is null");
        this.memoryItems = requireNonNull(memoryItems, "memoryItems is null");
        this.memoryStore = requireNonNull(memoryStore, "memoryStore is null");
        this.knowledgeStore = requireNonNull(knowledgeStore, "knowledgeStore is null");
        this.ingestor = requireNonNull(ingestor, "ingestor is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.distiller = requireNonNull(distiller, "distiller is null");
        this.proposalParser = requireNonNull(proposalParser, "proposalParser is null");
    }

    public WorkspaceMemoryDto get(String workspaceId)
    {
        workspaces.require(workspaceId);
        String markdown = renderBrain(workspaceId);
        return new WorkspaceMemoryDto(
                markdown,
                markdown.length(),
                configuration.settings(workspaceId).brainBudgetChars(),
                blocks(workspaceId),
                listKnowledge(workspaceId),
                listRuns(workspaceId));
    }

    public List<BrainBlockDto> blocks(String workspaceId)
    {
        workspaces.require(workspaceId);
        return memoryItems.listLive(MemoryItemScopeKind.WORKSPACE, workspaceId)
                .stream()
                .map(BrainBlockDto::from)
                .toList();
    }

    /**
     * Replaces the public Markdown document while retaining every exact
     * unchanged block row/id. Changed and new blocks receive USER_TYPED
     * provenance; removed blocks remain in audit history as resolved rows.
     */
    @Transactional
    public WorkspaceMemoryDto replaceMarkdown(String workspaceId, String markdown)
    {
        workspaces.require(workspaceId);
        List<BlockDraft> desired = parseMarkdown(markdown);
        enforceBudget(workspaceId, renderDrafts(desired));

        List<MemoryItem> live = memoryItems.listLive(
                MemoryItemScopeKind.WORKSPACE, workspaceId);
        Map<String, List<MemoryItem>> reusable = new HashMap<>();
        for (MemoryItem item : live) {
            reusable.computeIfAbsent(blockKey(item.kind(), item.text()),
                    ignored -> new ArrayList<>()).add(item);
        }
        Set<Long> kept = new LinkedHashSet<>();
        for (BlockDraft draft : desired) {
            List<MemoryItem> matches = reusable.get(blockKey(draft.kind(), draft.body()));
            if (matches != null && !matches.isEmpty()) {
                kept.add(matches.removeFirst().id());
            }
            else {
                insertApplied(workspaceId, draft.kind(), draft.body(),
                        MemoryItemOrigin.USER_TYPED,
                        MemoryItemSource.pr("workspace-memory-editor"));
            }
        }
        long now = Instant.now().toEpochMilli();
        for (MemoryItem item : live) {
            if (!kept.contains(item.id())) {
                memoryStore.markResolved(item.id(), now);
            }
        }
        syncMirror(workspaceId);
        return get(workspaceId);
    }

    public List<KBEntryDto> listKnowledge(String workspaceId)
    {
        workspaces.require(workspaceId);
        return knowledgeStore.listManaged(workspaceId).stream()
                .map(this::toDto)
                .toList();
    }

    public KBEntryDto getKnowledge(String workspaceId, String entryId)
    {
        return listKnowledge(workspaceId).stream()
                .filter(entry -> entry.id().equals(entryId))
                .findFirst()
                .orElseThrow(() -> status(404, "knowledge entry not found: " + entryId));
    }

    @Transactional
    public KBEntryDto saveKnowledge(
            String workspaceId,
            String entryId,
            String title,
            String body,
            List<String> audience,
            Map<String, Object> provenance)
    {
        workspaces.require(workspaceId);
        String titleValue = required(title, "title");
        String bodyValue = required(body, "body");
        List<String> audienceValue = validateAudience(audience);
        String id = entryId == null || entryId.isBlank()
                ? UUID.randomUUID().toString()
                : entryId;
        long now = Instant.now().toEpochMilli();
        KnowledgeItem existing = knowledgeStore.findById(id)
                .filter(item -> workspaceId.equals(item.workspaceId()))
                .orElse(null);
        if (existing != null) {
            // Edit keeps learned evidence links; only the curation provenance
            // (distill-operation / imported / user) is replaced.
            knowledgeStore.updateContent(
                    id, titleValue, bodyValue, existing.rationale(), audienceValue, now);
            knowledgeStore.replaceCurationProvenance(id, provenanceRows(provenance));
        }
        else {
            String repo = repoNameOf(workspaceId);
            boolean fromDistill = provenance != null
                    && provenance.containsKey("distillOperation");
            knowledgeStore.insert(
                    new KnowledgeItem(
                            id, workspaceId, repo, "doc-note", titleValue, bodyValue,
                            null, audienceValue, "high", KnowledgeItem.LIFECYCLE_ACTIVE,
                            null, null, fromDistill ? "distill" : "user", null, "{}",
                            now, now),
                    provenanceRows(provenance),
                    List.of());
        }
        return getKnowledge(workspaceId, id);
    }

    @Transactional
    public void deleteKnowledge(String workspaceId, String entryId)
    {
        workspaces.require(workspaceId);
        KnowledgeItem existing = knowledgeStore.findById(entryId)
                .filter(item -> workspaceId.equals(item.workspaceId()))
                .filter(item -> KnowledgeItemStore.isManagedCreator(item.createdBy()))
                .orElseThrow(() -> status(404, "knowledge entry not found: " + entryId));
        knowledgeStore.delete(existing.id());
    }

    /** One learned (pr-learning) knowledge row for the review surface, with
     *  its lifecycle and typed evidence links. */
    public record LearnedKnowledgeDto(
            String id,
            String kind,
            String title,
            String statement,
            String rationale,
            String confidence,
            String lifecycle,
            List<String> audience,
            List<Map<String, Object>> sources,
            String validatedAtCommit,
            long updatedAt) {}

    public List<LearnedKnowledgeDto> listLearned(String workspaceId, String lifecycle)
    {
        workspaces.require(workspaceId);
        List<KnowledgeItem> items = new ArrayList<>();
        if (lifecycle == null || lifecycle.isBlank()) {
            for (String state : List.of("pending", "active", "decayed", "retired")) {
                items.addAll(knowledgeStore.listByLifecycle(workspaceId, state));
            }
        }
        else {
            items.addAll(knowledgeStore.listByLifecycle(workspaceId, lifecycle));
        }
        return items.stream()
                .filter(item -> "pr-learning".equals(item.createdBy()))
                .map(this::toLearnedDto)
                .toList();
    }

    /**
     * User decision on a learned item: {@code activate} (user confirmation is
     * always sufficient) or {@code retire}. Provenance is untouched either
     * way — accepting or rejecting a lesson never drops its evidence.
     */
    @Transactional
    public LearnedKnowledgeDto decideLearned(String workspaceId, String itemId, String action)
    {
        workspaces.require(workspaceId);
        boolean known = knowledgeStore.findById(itemId)
                .filter(found -> workspaceId.equals(found.workspaceId()))
                .filter(found -> "pr-learning".equals(found.createdBy()))
                .isPresent();
        if (!known) {
            throw status(404, "learned knowledge not found: " + itemId);
        }
        String lifecycle = switch (required(action, "action").toLowerCase(Locale.ROOT)) {
            case "activate" -> KnowledgeItem.LIFECYCLE_ACTIVE;
            case "retire" -> KnowledgeItem.LIFECYCLE_RETIRED;
            default -> throw status(400, "action must be activate or retire");
        };
        knowledgeStore.setLifecycle(itemId, lifecycle, null, Instant.now().toEpochMilli());
        KnowledgeItem updated = knowledgeStore.findById(itemId).orElseThrow();
        if (updated.isActive()) {
            ingestor.onActivated(updated);
        }
        return toLearnedDto(updated);
    }

    private LearnedKnowledgeDto toLearnedDto(KnowledgeItem item)
    {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (KnowledgeItem.Provenance row : knowledgeStore.provenance(item.id())) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("kind", row.sourceKind());
            source.put("ref", row.sourceRef());
            if (row.url() != null) {
                source.put("url", row.url());
            }
            if (row.filePath() != null) {
                source.put("path", row.filePath());
            }
            sources.add(source);
        }
        return new LearnedKnowledgeDto(
                item.id(), item.kind(), item.title(), item.statement(),
                item.rationale(), item.confidence(), item.lifecycle(),
                item.audiences(), sources, item.validatedAtCommit(),
                item.updatedAtMs());
    }

    private KBEntryDto toDto(KnowledgeItem item)
    {
        return new KBEntryDto(
                item.id(),
                item.workspaceId(),
                item.title() == null ? "Note" : item.title(),
                item.statement(),
                item.audiences(),
                provenanceMap(item.id()),
                item.createdAtMs(),
                item.updatedAtMs());
    }

    /** Rebuild the DTO's provenance map from typed provenance rows so the
     *  existing KB surface keeps its shape. */
    private Map<String, Object> provenanceMap(String itemId)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (KnowledgeItem.Provenance row : knowledgeStore.provenance(itemId)) {
            if ("distill-operation".equals(row.sourceKind())) {
                out.put("distillOperation", row.sourceRef());
            }
            else if ("imported".equals(row.sourceKind())) {
                out.putAll(read(row.sourceRef(), OBJECT_MAP, Map.of()));
            }
            else {
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("kind", row.sourceKind());
                source.put("ref", row.sourceRef());
                if (row.url() != null) {
                    source.put("url", row.url());
                }
                sources.add(source);
            }
        }
        if (!sources.isEmpty()) {
            out.put("sources", sources);
        }
        return out;
    }

    /** The inverse mapping: a DTO provenance map becomes typed curation rows
     *  ({@code distillOperation} pointer, everything else kept verbatim). */
    private List<KnowledgeItem.Provenance> provenanceRows(Map<String, Object> provenance)
    {
        if (provenance == null || provenance.isEmpty()) {
            return List.of();
        }
        List<KnowledgeItem.Provenance> out = new ArrayList<>();
        Map<String, Object> rest = new LinkedHashMap<>(provenance);
        Object distillOperation = rest.remove("distillOperation");
        rest.remove("sources");     // learned rows already own these
        if (distillOperation != null) {
            out.add(new KnowledgeItem.Provenance(
                    "distill-operation", String.valueOf(distillOperation),
                    null, null, null, null));
        }
        if (!rest.isEmpty()) {
            out.add(new KnowledgeItem.Provenance(
                    "imported", write(rest), null, null, null, null));
        }
        return out;
    }

    private String repoNameOf(String workspaceId)
    {
        try {
            return repositories.resolve(workspaceId).fullName();
        }
        catch (RuntimeException e) {
            return workspaces.require(workspaceId).name();
        }
    }

    /** Render only entries whose audience includes this public session kind. */
    public String renderKnowledgeForSession(String workspaceId, String audience)
    {
        String kind = required(audience, "audience").toLowerCase(Locale.ROOT);
        if (!AUDIENCES.contains(kind)) {
            throw status(400, "unknown session kind: " + audience);
        }
        if (!configuration.settings(workspaceId).kbAudiences().contains(kind)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (KBEntryDto entry : listKnowledge(workspaceId)) {
            if (!entry.audience().contains(kind)) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append("\n\n");
            }
            out.append("## ").append(entry.title()).append("\n\n")
                    .append(entry.body().strip());
        }
        return out.toString();
    }

    @Transactional
    public DistillRunDto createPreview(
            String workspaceId,
            String trigger,
            List<Map<String, Object>> sources,
            List<DistillOperationDto> operations)
    {
        workspaces.require(workspaceId);
        String triggerValue = required(trigger, "trigger");
        List<DistillOperationDto> normalized = normalizeOperations(operations);
        String status = normalized.isEmpty() ? "no-changes" : "pending";
        String id = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                INSERT INTO distill_run (
                    id, workspace_id, trigger_kind, status, sources_json,
                    operations_json, inverse_json, base_digest, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, '[]', ?, ?)
                """,
                id, workspaceId, triggerValue, status,
                write(sources == null ? List.of() : sources),
                write(normalized),
                digest(workspaceId),
                now);
        return requireRun(workspaceId, id);
    }

    /**
     * Seed candidates use README, CONTRIBUTING, and the module layout but
     * remain a pending preview; nothing is applied silently.
     */
    public DistillRunDto createSeedPreview(String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo = repositories.resolve(workspaceId);
        Path root = watchedRepos.find(repo.owner(), repo.repo())
                .map(WatchedRepo::localClonePath)
                .filter(path -> path != null && !path.isBlank())
                .map(Path::of)
                .orElseThrow(() -> status(409, "workspace clone is unavailable"));
        List<DistillOperationDto> operations = new ArrayList<>();
        addSeedFile(operations, root, "README.md", "Repository README");
        addSeedFile(operations, root, "CONTRIBUTING.md", "Contributing guide");
        try (Stream<Path> children = Files.list(root)) {
            String layout = children
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .limit(80)
                    .map(path -> (Files.isDirectory(path) ? "directory: " : "file: ")
                            + path.getFileName())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            if (!layout.isBlank()) {
                operations.add(seedOperation("Module layout", layout));
            }
        }
        catch (IOException e) {
            throw new IllegalStateException("could not read repository layout", e);
        }
        return createPreview(
                workspaceId,
                "seed",
                List.of(Map.of("repository", repo.fullName())),
                operations);
    }

    /** Run the existing attributable thread summariser and convert its output
     *  into the same mandatory-decision preview used by manual/seed runs. */
    public DistillRunDto createThreadPreview(String workspaceId)
    {
        List<Map<String, Object>> sources = new ArrayList<>(
                trunksWithNewSource(workspaceId));
        WorkspaceMemoryProposal proposal = distiller.distill(workspaceId)
                .orElse(null);
        if (proposal == null) {
            DistillRunDto run = createPreview(
                    workspaceId, "auto", sources, List.of());
            advanceWatermarks(workspaceId, sources);
            return run;
        }
        List<MemoryItemStore.NewItem> candidates =
                proposalParser.parse(workspaceId, proposal.proposedMd());
        List<DistillOperationDto> operations = new ArrayList<>();
        for (MemoryItemStore.NewItem candidate : candidates) {
            for (MemoryItemSource source : candidate.sources()) {
                Map<String, Object> value = new LinkedHashMap<>();
                if (source.threadId() != null) value.put("threadId", source.threadId());
                if (source.taskId() != null) value.put("taskId", source.taskId());
                if (source.prRef() != null) value.put("prRef", source.prRef());
                if (!value.isEmpty() && !sources.contains(value)) sources.add(value);
            }
            operations.add(new DistillOperationDto(
                    UUID.randomUUID().toString(),
                    "brain",
                    "add",
                    null,
                    null,
                    categoryFor(candidate.kind()),
                    null,
                    candidate.text(),
                    List.of(),
                    "pending",
                    candidate.text()));
        }
        DistillRunDto run = createPreview(
                workspaceId, "auto", sources, operations);
        advanceWatermarks(workspaceId, sources);
        return run;
    }

    /** True only when an active public trunk has messages past its durable
     *  distillation watermark. */
    public boolean hasActiveTrunksWithNewSource(String workspaceId)
    {
        workspaces.require(workspaceId);
        return !trunksWithNewSource(workspaceId).isEmpty();
    }

    public boolean hasPendingPreview(String workspaceId)
    {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM distill_run
                WHERE workspace_id = ? AND status = 'pending'
                """, Integer.class, workspaceId);
        return count != null && count > 0;
    }

    public long latestRunAt(String workspaceId)
    {
        Long value = jdbc.queryForObject("""
                SELECT max(created_at_ms) FROM distill_run
                WHERE workspace_id = ?
                """, Long.class, workspaceId);
        return value == null ? 0L : value;
    }

    @Transactional
    public DistillRunDto decide(
            String workspaceId,
            String runId,
            List<DistillOperationDto> decisions)
    {
        DistillRunRow row = requireRunRow(workspaceId, runId);
        if (!"pending".equals(row.status())) {
            throw status(409, "distill run is not pending");
        }
        Map<String, DistillOperationDto> supplied = new HashMap<>();
        if (decisions != null) {
            decisions.forEach(operation -> supplied.put(operation.id(), operation));
        }
        List<DistillOperationDto> merged = new ArrayList<>();
        for (DistillOperationDto persisted : row.operations()) {
            DistillOperationDto edit = supplied.get(persisted.id());
            if (edit == null) {
                merged.add(persisted);
                continue;
            }
            String decision = normalizeDecision(edit.decision());
            String category = edit.category() == null
                    ? persisted.category() : edit.category();
            String title = edit.title() == null
                    ? persisted.title() : edit.title();
            String body = edit.body() == null
                    ? persisted.body() : edit.body();
            List<String> audience = edit.audience() == null
                    || !"kb".equalsIgnoreCase(persisted.target())
                    ? persisted.audience()
                    : validateAudience(edit.audience());
            if ("accepted".equals(decision) || "edited".equals(decision)) {
                boolean changed = !Objects.equals(
                        category, persisted.category())
                        || !Objects.equals(title, persisted.title())
                        || !Objects.equals(body, persisted.body())
                        || !Objects.equals(
                                audience, persisted.audience());
                decision = changed ? "edited" : "accepted";
            }
            merged.add(new DistillOperationDto(
                    persisted.id(),
                    persisted.target(),
                    persisted.action(),
                    persisted.brainItemId(),
                    persisted.kbEntryId(),
                    category,
                    title,
                    body,
                    audience,
                    decision,
                    persisted.originalBody()));
        }
        jdbc.update(
                "UPDATE distill_run SET operations_json = ? WHERE id = ?",
                write(merged), runId);
        return requireRun(workspaceId, runId);
    }

    @Transactional
    public DistillRunDto apply(String workspaceId, String runId)
    {
        DistillRunRow row = requireRunRow(workspaceId, runId);
        if (!"pending".equals(row.status())) {
            throw status(409, "distill run is not pending");
        }
        if (!digest(workspaceId).equals(row.baseDigest())) {
            throw status(409, "memory or knowledge changed; refresh this preview");
        }
        for (DistillOperationDto operation : row.operations()) {
            if (!DECISIONS.contains(normalizeDecision(operation.decision()))) {
                throw status(409, "every distill operation requires Accept, Edit, or Skip");
            }
        }
        List<DistillOperationDto> accepted = row.operations().stream()
                .filter(operation -> !"skipped".equals(normalizeDecision(operation.decision())))
                .toList();
        if (accepted.isEmpty()) {
            jdbc.update("""
                    UPDATE distill_run
                    SET status = 'no-changes', applied_at_ms = ?
                    WHERE id = ?
                    """, Instant.now().toEpochMilli(), runId);
            return requireRun(workspaceId, runId);
        }
        enforceProjectedBudget(workspaceId, accepted);
        List<InverseOperation> inverse = new ArrayList<>();
        for (DistillOperationDto operation : accepted) {
            applyOperation(workspaceId, operation, inverse);
        }
        syncMirror(workspaceId);
        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                UPDATE distill_run
                SET status = 'applied',
                    inverse_json = ?,
                    applied_digest = ?,
                    applied_at_ms = ?
                WHERE id = ?
                """, write(inverse), digest(workspaceId), now, runId);
        if ("seed".equals(row.trigger())) {
            // The seed-complete milestone means an accepted seed run, not
            // merely a cloned workspace with content.
            jdbc.update("""
                    UPDATE workspace_onboarding
                    SET memory_seed_complete = 1, updated_at_ms = ?
                    WHERE workspace_id = ?
                    """, now, workspaceId);
        }
        return requireRun(workspaceId, runId);
    }

    @Transactional
    public DistillRunDto revert(String workspaceId, String runId)
    {
        DistillRunRow row = requireRunRow(workspaceId, runId);
        if (!"applied".equals(row.status())) {
            throw status(409, "distill run is not applied");
        }
        if (!digest(workspaceId).equals(row.appliedDigest())) {
            throw status(409, "memory or knowledge has changed; revert would overwrite edits");
        }
        List<InverseOperation> inverse = new ArrayList<>(row.inverse());
        for (int i = inverse.size() - 1; i >= 0; i--) {
            revertOperation(workspaceId, inverse.get(i));
        }
        syncMirror(workspaceId);
        jdbc.update("""
                UPDATE distill_run
                SET status = 'reverted', reverted_at_ms = ?
                WHERE id = ?
                """, Instant.now().toEpochMilli(), runId);
        return requireRun(workspaceId, runId);
    }

    public List<DistillRunDto> listRuns(String workspaceId)
    {
        workspaces.require(workspaceId);
        return jdbc.query("""
                SELECT * FROM distill_run
                WHERE workspace_id = ?
                ORDER BY created_at_ms DESC
                LIMIT 50
                """, (rs, ignored) -> toDto(new DistillRunRow(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("trigger_kind"),
                rs.getString("status"),
                read(rs.getString("sources_json"), MAP_LIST, List.of()),
                read(rs.getString("operations_json"), OPERATIONS, List.of()),
                read(rs.getString("inverse_json"), INVERSES, List.of()),
                rs.getString("base_digest"),
                rs.getString("applied_digest"),
                rs.getLong("created_at_ms"),
                nullableLong(rs.getObject("applied_at_ms")),
                nullableLong(rs.getObject("reverted_at_ms")))),
                workspaceId);
    }

    public DistillRunDto requireRun(String workspaceId, String runId)
    {
        return toDto(requireRunRow(workspaceId, runId));
    }

    /**
     * One-time compatibility import. Existing markdown is parsed only when
     * no structured rows exist; unmatched prose becomes a Gotchas block.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void importLegacyMemory()
    {
        for (Workspace workspace : workspaces.list()) {
            if (workspace.memoryMd() == null || workspace.memoryMd().isBlank()) {
                continue;
            }
            if (!memoryStore.findByScope(
                    MemoryItemScopeKind.WORKSPACE, workspace.id()).isEmpty()) {
                continue;
            }
            for (BlockDraft draft : parseMarkdown(workspace.memoryMd())) {
                insertApplied(
                        workspace.id(),
                        draft.kind(),
                        draft.body(),
                        MemoryItemOrigin.USER_TYPED,
                        MemoryItemSource.pr("legacy-memory-md"));
            }
            jdbc.update("""
                    UPDATE workspace_onboarding
                    SET memory_imported = 1, updated_at_ms = ?
                    WHERE workspace_id = ?
                    """, Instant.now().toEpochMilli(), workspace.id());
        }
    }

    private void applyOperation(
            String workspaceId,
            DistillOperationDto operation,
            List<InverseOperation> inverse)
    {
        String target = required(operation.target(), "operation target").toLowerCase(Locale.ROOT);
        String action = required(operation.action(), "operation action").toLowerCase(Locale.ROOT);
        if ("brain".equals(target)) {
            applyBrainOperation(workspaceId, action, operation, inverse);
            return;
        }
        if ("kb".equals(target)) {
            applyKnowledgeOperation(workspaceId, action, operation, inverse);
            return;
        }
        throw status(400, "distill target must be brain or kb");
    }

    private void applyBrainOperation(
            String workspaceId,
            String action,
            DistillOperationDto operation,
            List<InverseOperation> inverse)
    {
        if ("add".equals(action)) {
            MemoryItem added = insertApplied(
                    workspaceId,
                    kindForCategory(operation.category()),
                    required(operation.body(), "brain block body"),
                    MemoryItemOrigin.DISTILL,
                    MemoryItemSource.pr("distill:" + operation.id()));
            inverse.add(new InverseOperation(
                    "delete-brain", null, added.id(), null, null));
            return;
        }
        MemoryItem old = requireLiveBrain(workspaceId, operation.brainItemId());
        if ("delete".equals(action)) {
            memoryStore.markResolved(old.id(), Instant.now().toEpochMilli());
            inverse.add(new InverseOperation(
                    "restore-brain", old.id(), null, null, null));
            return;
        }
        if ("replace".equals(action)) {
            MemoryItem added = insertApplied(
                    workspaceId,
                    kindForCategory(operation.category()),
                    required(operation.body(), "brain block body"),
                    MemoryItemOrigin.DISTILL,
                    MemoryItemSource.pr("distill:" + operation.id()));
            memoryStore.markResolved(old.id(), Instant.now().toEpochMilli());
            inverse.add(new InverseOperation(
                    "restore-brain-replacement", old.id(), added.id(), null, null));
            return;
        }
        throw status(400, "unsupported brain operation: " + action);
    }

    private void applyKnowledgeOperation(
            String workspaceId,
            String action,
            DistillOperationDto operation,
            List<InverseOperation> inverse)
    {
        if ("add".equals(action)) {
            KBEntryDto added = saveKnowledge(
                    workspaceId,
                    operation.kbEntryId(),
                    operation.title(),
                    operation.body(),
                    operation.audience(),
                    Map.of("distillOperation", operation.id()));
            inverse.add(new InverseOperation(
                    "delete-kb", null, null, added.id(), null));
            return;
        }
        KBEntryDto old = getKnowledge(workspaceId, operation.kbEntryId());
        if ("delete".equals(action)) {
            deleteKnowledge(workspaceId, old.id());
            inverse.add(new InverseOperation(
                    "restore-kb", null, null, null, old));
            return;
        }
        if ("replace".equals(action)) {
            saveKnowledge(
                    workspaceId,
                    old.id(),
                    operation.title(),
                    operation.body(),
                    operation.audience(),
                    Map.of("distillOperation", operation.id()));
            inverse.add(new InverseOperation(
                    "restore-kb", null, null, null, old));
            return;
        }
        throw status(400, "unsupported knowledge operation: " + action);
    }

    private void revertOperation(String workspaceId, InverseOperation inverse)
    {
        switch (inverse.action()) {
            case "delete-brain" -> jdbc.update(
                    "DELETE FROM memory_item WHERE id = ? AND scope_id = ?",
                    inverse.newBrainId(), workspaceId);
            case "restore-brain" -> jdbc.update("""
                    UPDATE memory_item SET resolved_at_ms = NULL
                    WHERE id = ? AND scope_kind = 'WORKSPACE' AND scope_id = ?
                    """, inverse.oldBrainId(), workspaceId);
            case "restore-brain-replacement" -> {
                jdbc.update(
                        "DELETE FROM memory_item WHERE id = ? AND scope_id = ?",
                        inverse.newBrainId(), workspaceId);
                jdbc.update("""
                        UPDATE memory_item SET resolved_at_ms = NULL
                        WHERE id = ? AND scope_kind = 'WORKSPACE' AND scope_id = ?
                        """, inverse.oldBrainId(), workspaceId);
            }
            case "delete-kb" -> knowledgeStore.findById(inverse.kbEntryId())
                    .filter(item -> workspaceId.equals(item.workspaceId()))
                    .ifPresent(item -> knowledgeStore.delete(item.id()));
            case "restore-kb" -> upsertExactKnowledge(inverse.kbBefore());
            default -> throw new IllegalStateException(
                    "unknown distill inverse " + inverse.action());
        }
    }

    /** Exact restore for revert: bring back the entry as captured before the
     *  applied operation, keeping any learned evidence links intact. */
    private void upsertExactKnowledge(KBEntryDto entry)
    {
        KnowledgeItem existing = knowledgeStore.findById(entry.id()).orElse(null);
        if (existing != null) {
            knowledgeStore.updateContent(entry.id(), entry.title(), entry.body(),
                    existing.rationale(), entry.audience(), entry.updatedAt());
            knowledgeStore.replaceCurationProvenance(
                    entry.id(), provenanceRows(entry.provenance()));
            return;
        }
        knowledgeStore.insert(
                new KnowledgeItem(
                        entry.id(), entry.workspaceId(), repoNameOf(entry.workspaceId()),
                        "doc-note", entry.title(), entry.body(), null, entry.audience(),
                        "high", KnowledgeItem.LIFECYCLE_ACTIVE, null, null,
                        entry.provenance() != null
                                && entry.provenance().containsKey("distillOperation")
                                ? "distill" : "user",
                        null, "{}", entry.createdAt(), entry.updatedAt()),
                provenanceRows(entry.provenance()),
                List.of());
    }

    private void enforceProjectedBudget(
            String workspaceId, List<DistillOperationDto> operations)
    {
        List<BlockDraft> projected = memoryItems.listLive(
                        MemoryItemScopeKind.WORKSPACE, workspaceId)
                .stream()
                .map(item -> new BlockDraft(item.kind(), item.text(), item.id()))
                .collect(Collectors.toCollection(ArrayList::new));
        for (DistillOperationDto operation : operations) {
            if (!"brain".equalsIgnoreCase(operation.target())) {
                continue;
            }
            String action = operation.action().toLowerCase(Locale.ROOT);
            int index = indexOf(projected, operation.brainItemId());
            if ("delete".equals(action)) {
                if (index < 0) {
                    throw status(409, "brain block changed since preview");
                }
                projected.remove(index);
            }
            else if ("replace".equals(action)) {
                if (index < 0) {
                    throw status(409, "brain block changed since preview");
                }
                projected.set(index, new BlockDraft(
                        kindForCategory(operation.category()),
                        required(operation.body(), "brain block body"),
                        null));
            }
            else if ("add".equals(action)) {
                projected.add(new BlockDraft(
                        kindForCategory(operation.category()),
                        required(operation.body(), "brain block body"),
                        null));
            }
        }
        enforceBudget(workspaceId, renderDrafts(projected));
    }

    private void enforceBudget(String workspaceId, String markdown)
    {
        int budget = configuration.settings(workspaceId).brainBudgetChars();
        if (markdown.length() > budget) {
            throw status(422, "brain character budget exceeded: "
                    + markdown.length() + "/" + budget);
        }
    }

    private MemoryItem requireLiveBrain(String workspaceId, Long itemId)
    {
        if (itemId == null) {
            throw status(400, "brainItemId is required");
        }
        return memoryItems.listLive(MemoryItemScopeKind.WORKSPACE, workspaceId)
                .stream()
                .filter(item -> item.id() == itemId)
                .findFirst()
                .orElseThrow(() -> status(409, "brain block changed since preview"));
    }

    private MemoryItem insertApplied(
            String workspaceId,
            MemoryItemKind kind,
            String body,
            MemoryItemOrigin origin,
            MemoryItemSource source)
    {
        MemoryItem pending = memoryItems.propose(new MemoryItemStore.NewItem(
                MemoryItemScopeKind.WORKSPACE,
                workspaceId,
                kind,
                required(body, "brain block body"),
                List.of(source),
                MemoryItemConfidence.HIGH,
                List.of(),
                origin));
        return memoryItems.applyItem(pending.id());
    }

    private String renderBrain(String workspaceId)
    {
        return renderDrafts(memoryItems.listLive(
                        MemoryItemScopeKind.WORKSPACE, workspaceId)
                .stream()
                .map(item -> new BlockDraft(item.kind(), item.text(), item.id()))
                .toList());
    }

    private static String renderDrafts(List<BlockDraft> blocks)
    {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        grouped.put("Conventions", new ArrayList<>());
        grouped.put("Decisions", new ArrayList<>());
        grouped.put("Gotchas", new ArrayList<>());
        for (BlockDraft block : blocks) {
            grouped.get(categoryFor(block.kind())).add(block.body().strip());
        }
        StringBuilder out = new StringBuilder();
        grouped.forEach((heading, rows) -> {
            if (rows.isEmpty()) {
                return;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append("## ").append(heading).append("\n\n");
            rows.forEach(row -> out.append("- ").append(row).append('\n'));
        });
        return out.toString();
    }

    static List<BlockDraft> parseMarkdown(String markdown)
    {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<BlockDraft> out = new ArrayList<>();
        MemoryItemKind kind = MemoryItemKind.RECURRING_PATTERN;
        StringBuilder paragraph = new StringBuilder();
        for (String raw : markdown.split("\\R", -1)) {
            String line = raw.stripTrailing();
            if (line.startsWith("## ")) {
                flushParagraph(out, kind, paragraph);
                kind = kindForCategory(line.substring(3));
            }
            else if (line.startsWith("- ") || line.startsWith("* ")) {
                flushParagraph(out, kind, paragraph);
                if (!line.substring(2).isBlank()) {
                    out.add(new BlockDraft(kind, line.substring(2).strip(), null));
                }
            }
            else if (line.isBlank()) {
                flushParagraph(out, kind, paragraph);
            }
            else {
                if (!paragraph.isEmpty()) {
                    paragraph.append('\n');
                }
                paragraph.append(line);
            }
        }
        flushParagraph(out, kind, paragraph);
        return List.copyOf(out);
    }

    private static void flushParagraph(
            List<BlockDraft> out, MemoryItemKind kind, StringBuilder paragraph)
    {
        if (!paragraph.isEmpty()) {
            out.add(new BlockDraft(kind, paragraph.toString().strip(), null));
            paragraph.setLength(0);
        }
    }

    private void syncMirror(String workspaceId)
    {
        workspaces.setMemory(workspaceId, renderBrain(workspaceId));
    }

    private String digest(String workspaceId)
    {
        StringBuilder canonical = new StringBuilder(renderBrain(workspaceId));
        listKnowledge(workspaceId).stream()
                .sorted(Comparator.comparing(KBEntryDto::id))
                .forEach(entry -> canonical.append('\u0000')
                        .append(entry.id()).append('\u0000')
                        .append(entry.title()).append('\u0000')
                        .append(entry.body()).append('\u0000')
                        .append(String.join(",", entry.audience())));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private DistillRunRow requireRunRow(String workspaceId, String runId)
    {
        return listRunRows(workspaceId, runId).stream()
                .findFirst()
                .orElseThrow(() -> status(404, "distill run not found: " + runId));
    }

    private List<Map<String, Object>> trunksWithNewSource(String workspaceId)
    {
        return jdbc.query("""
                SELECT thread.id, max(message.seq) AS latest_seq
                FROM threads thread
                JOIN thread_messages message ON message.thread_id = thread.id
                LEFT JOIN distill_watermark watermark
                  ON watermark.workspace_id = thread.workspace_id
                 AND watermark.thread_id = thread.id
                WHERE thread.workspace_id = ?
                  AND thread.status NOT IN ('COMPLETED', 'ARCHIVED', 'ERRORED')
                  AND thread.kind <> 'BRAIN_AGENT'
                GROUP BY thread.id, watermark.last_seq
                HAVING max(message.seq) > coalesce(watermark.last_seq, 0)
                ORDER BY thread.updated_at_ms DESC
                """,
                (rs, ignored) -> {
                    Map<String, Object> source = new LinkedHashMap<>();
                    source.put("threadId", rs.getString("id"));
                    source.put("lastSeq", rs.getLong("latest_seq"));
                    return source;
                },
                workspaceId);
    }

    private void advanceWatermarks(
            String workspaceId, List<Map<String, Object>> sources)
    {
        long now = Instant.now().toEpochMilli();
        for (Map<String, Object> source : sources) {
            Object threadId = source.get("threadId");
            Object lastSeq = source.get("lastSeq");
            if (!(threadId instanceof String id) || !(lastSeq instanceof Number seq)) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO distill_watermark (
                        workspace_id, thread_id, last_seq, updated_at_ms)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(workspace_id, thread_id) DO UPDATE SET
                        last_seq = excluded.last_seq,
                        updated_at_ms = excluded.updated_at_ms
                    """, workspaceId, id, seq.longValue(), now);
        }
    }

    private List<DistillRunRow> listRunRows(String workspaceId, String runId)
    {
        return jdbc.query("""
                SELECT * FROM distill_run
                WHERE workspace_id = ? AND id = ?
                """, (rs, ignored) -> new DistillRunRow(
                rs.getString("id"),
                rs.getString("workspace_id"),
                rs.getString("trigger_kind"),
                rs.getString("status"),
                read(rs.getString("sources_json"), MAP_LIST, List.of()),
                read(rs.getString("operations_json"), OPERATIONS, List.of()),
                read(rs.getString("inverse_json"), INVERSES, List.of()),
                rs.getString("base_digest"),
                rs.getString("applied_digest"),
                rs.getLong("created_at_ms"),
                nullableLong(rs.getObject("applied_at_ms")),
                nullableLong(rs.getObject("reverted_at_ms"))),
                workspaceId, runId);
    }

    private static DistillRunDto toDto(DistillRunRow row)
    {
        return new DistillRunDto(
                row.id(), row.workspaceId(), row.trigger(), row.status(),
                row.sources(), row.operations(), row.createdAt(),
                row.appliedAt(), row.revertedAt());
    }

    private static List<DistillOperationDto> normalizeOperations(
            List<DistillOperationDto> operations)
    {
        if (operations == null) {
            return List.of();
        }
        List<DistillOperationDto> out = new ArrayList<>();
        for (DistillOperationDto operation : operations) {
            if (operation == null) {
                continue;
            }
            out.add(new DistillOperationDto(
                    operation.id() == null || operation.id().isBlank()
                            ? UUID.randomUUID().toString()
                            : operation.id(),
                    required(operation.target(), "operation target")
                            .toLowerCase(Locale.ROOT),
                    required(operation.action(), "operation action")
                            .toLowerCase(Locale.ROOT),
                    operation.brainItemId(),
                    operation.kbEntryId(),
                    operation.category(),
                    operation.title(),
                    operation.body(),
                    operation.audience() == null ? List.of() : operation.audience(),
                    operation.decision() == null ? "pending"
                            : normalizeDecision(operation.decision()),
                    operation.originalBody() == null
                            ? operation.body()
                            : operation.originalBody()));
        }
        return List.copyOf(out);
    }

    private static String normalizeDecision(String decision)
    {
        if (decision == null || decision.isBlank()) {
            return "pending";
        }
        return switch (decision.strip().toLowerCase(Locale.ROOT)) {
            case "accept", "accepted" -> "accepted";
            case "edit", "edited" -> "edited";
            case "skip", "skipped" -> "skipped";
            default -> "pending";
        };
    }

    private static List<String> validateAudience(List<String> audience)
    {
        List<String> value = audience == null ? List.of() : audience.stream()
                .map(item -> item.toLowerCase(Locale.ROOT).strip())
                .distinct()
                .toList();
        if (value.isEmpty() || !AUDIENCES.containsAll(value)) {
            throw status(400, "audience must contain plan/dev/review/ci-fix values");
        }
        return value;
    }

    private static MemoryItemKind kindForCategory(String category)
    {
        String value = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (value.contains("convention")) {
            return MemoryItemKind.CONVENTION;
        }
        if (value.contains("decision")) {
            return MemoryItemKind.DECISION;
        }
        return MemoryItemKind.RECURRING_PATTERN;
    }

    private static String categoryFor(MemoryItemKind kind)
    {
        return switch (kind) {
            case CONVENTION -> "Conventions";
            case DECISION -> "Decisions";
            default -> "Gotchas";
        };
    }

    private static String blockKey(MemoryItemKind kind, String text)
    {
        return kind.name() + '\u0000' + text.strip();
    }

    private static int indexOf(List<BlockDraft> blocks, Long itemId)
    {
        if (itemId == null) {
            return -1;
        }
        for (int i = 0; i < blocks.size(); i++) {
            if (itemId.equals(blocks.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    private void addSeedFile(
            List<DistillOperationDto> operations,
            Path root,
            String fileName,
            String title)
    {
        Path path = root.resolve(fileName);
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String body = Files.readString(path);
            if (!body.isBlank()) {
                operations.add(seedOperation(title, body));
            }
        }
        catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }

    private static DistillOperationDto seedOperation(String title, String body)
    {
        return new DistillOperationDto(
                UUID.randomUUID().toString(),
                "kb",
                "add",
                null,
                null,
                null,
                title,
                body,
                List.of("plan", "dev", "review", "ci-fix"),
                "pending",
                body);
    }

    private String write(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode workspace knowledge JSON", e);
        }
    }

    private <T> T read(String value, TypeReference<T> type, T fallback)
    {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return mapper.readValue(value, type);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid workspace knowledge JSON", e);
        }
    }

    private static String required(String value, String field)
    {
        if (value == null || value.isBlank()) {
            throw status(400, field + " is required");
        }
        return value.strip();
    }

    private static Long nullableLong(Object value)
    {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }

    static record BlockDraft(MemoryItemKind kind, String body, Long id) {}

    private record InverseOperation(
            String action,
            Long oldBrainId,
            Long newBrainId,
            String kbEntryId,
            KBEntryDto kbBefore) {}

    private record DistillRunRow(
            String id,
            String workspaceId,
            String trigger,
            String status,
            List<Map<String, Object>> sources,
            List<DistillOperationDto> operations,
            List<InverseOperation> inverse,
            String baseDigest,
            String appliedDigest,
            long createdAt,
            Long appliedAt,
            Long revertedAt) {}
}
