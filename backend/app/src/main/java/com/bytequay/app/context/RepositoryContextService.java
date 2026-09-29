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

import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.context.ActiveContextPreparation;
import com.bytequay.core.context.ContextClaim;
import com.bytequay.core.context.ContextDocumentRead;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextHistoryRead;
import com.bytequay.core.context.ContextKnowledgeDraft;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.ContextSourceRead;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static com.bytequay.core.context.ContextEntryKind.KNOWLEDGE;
import static com.bytequay.core.context.ContextEntryKind.MEMORY;
import static com.bytequay.core.context.ContextEntryOrigin.GENERATED;
import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;

/** Local notebook editing with optimistic revisions and explicit, server-captured lesson provenance. */
@Service
public class RepositoryContextService
{
    public static final String SETTINGS_PREFIX = "workspace.repositoryContext.";
    static final String TIMES_PREFIX = "workspace.repositoryContextTimes.";

    private final AppSettingsStore settings;
    private final ObjectMapper mapper;
    private final WorkspaceStore workspaces;
    private final AgentReviewStore reviews;
    private final Clock clock;

    public RepositoryContextService(AppSettingsStore settings, ObjectMapper mapper, WorkspaceStore workspaces, AgentReviewStore reviews, Clock clock)
    {
        this.settings = requireNonNull(settings, "settings is null");
        this.mapper = requireNonNull(mapper, "mapper is null").copy()
                .addMixIn(ContextMatch.class, MatchJson.class)
                .addMixIn(ContextReference.class, ReferenceJson.class)
                .addMixIn(ContextSourceRead.class, SourceReadJson.class)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /**
     * Reads retained local content without source hydration or writes.
     *
     * @param workspaceId an existing retained workspace
     * @return current notebook, or an unsaved revision-zero notebook with review use enabled
     * @throws ContextNotFoundException when the workspace does not exist
     * @throws ContextStorageException when saved JSON is invalid; corrupt data is never reset
     */
    public RepositoryNotebook getNotebook(String workspaceId)
    {
        requireWorkspace(workspaceId);
        return settings.get(key(workspaceId)).map(this::decode).orElseGet(RepositoryNotebook::empty);
    }

    /** When retained note versions and pending drafts appeared; content written before stamping began has no time. */
    public ContextTimes getTimes(String workspaceId)
    {
        requireWorkspace(workspaceId);
        return readTimes(workspaceId);
    }

    /**
     * Applies only supplied preference and source fields in one revision-checked mutation.
     *
     * @param workspaceId owning retained workspace
     * @param expectedRevision content revision displayed by the editor
     * @param edit user fields; source provenance is assigned here, never accepted from the renderer
     * @return updated notebook; unchanged fields retain their content
     * @throws ContextConflictException when another edit already changed the revision
     * @throws IllegalArgumentException when a source is unsafe or limits are exceeded
     */
    public RepositoryNotebook configure(String workspaceId, long expectedRevision, EditContextConfiguration edit)
    {
        requireNonNull(edit, "edit is null");
        return mutate(workspaceId, expectedRevision, current -> {
            List<RegisteredContextSource> sources = edit.sources().<List<RegisteredContextSource>>map(values -> values.stream().map(value -> {
                RegisteredContextSource updated = new RegisteredContextSource(value.id(), value.kind(), value.url(), USER, Optional.empty());
                return current.sources().stream()
                        .filter(source -> source.id().equals(value.id()) && source.kind() == value.kind() && source.url().equals(value.url()))
                        .findFirst().orElse(updated);
            }).collect(toImmutableList())).orElse(current.sources());
            return content(current, edit.useInReviews().orElse(current.useInReviews()), edit.useInDevelopment().orElse(current.useInDevelopment()), sources,
                    current.scopes(), current.entries(), current.sourceEvidence());
        });
    }

    /**
     * Appends only explicitly accepted, repository-derived configuration and its retained evidence.
     *
     * @param workspaceId owning retained workspace
     * @param expectedRevision notebook revision displayed with the discovered proposals
     * @param scopes new path-stable scopes with server-validated discovery evidence references
     * @param sources new generated documentation registrations with server-validated discovery evidence references
     * @param evidence exact captured repository excerpts rederived by the caller outside mutation locks
     * @return notebook with one content revision increment and all prior user content preserved
     * @throws ContextConflictException when the notebook changed after discovery
     * @throws IllegalArgumentException for active preparation, duplicate identities, invalid provenance or notebook limits
     *
     * No Git, remote fetch or model work runs here. Existing configuration is never replaced or re-enabled.
     * The caller validates repository routing, frozen baseline and proposal identity before this bounded mutation.
     */
    RepositoryNotebook registerDiscovered(String workspaceId, long expectedRevision, List<ContextScope> scopes,
            List<RegisteredContextSource> sources, List<ContextSourceEvidence> evidence)
    {
        List<ContextScope> additions = ImmutableList.copyOf(scopes);
        List<RegisteredContextSource> registrations = ImmutableList.copyOf(sources);
        List<ContextSourceEvidence> captures = ImmutableList.copyOf(evidence);
        checkArgument(!additions.isEmpty() || !registrations.isEmpty(), "Select discovered scopes or documentation sources");
        ContextLimits.unique(additions.stream().map(ContextScope::id).toList(), "discovered scopes", 100);
        ContextLimits.unique(registrations.stream().map(RegisteredContextSource::id).toList(), "discovered sources", 100);
        ContextLimits.unique(captures.stream().map(ContextSourceEvidence::id).toList(), "discovery evidence", 800);
        additions.forEach(scope -> checkArgument(scope.id().startsWith("path:") && scope.preparation().isEmpty() && scope.discoveryEvidenceRef().isPresent(),
                "Discovered scope needs a stable path and captured evidence, without preparation metadata"));
        registrations.forEach(source -> checkArgument(source.origin() == GENERATED && source.discoveryEvidenceRef().isPresent(),
                "Discovered source needs generated origin and captured evidence"));
        return mutate(workspaceId, expectedRevision, current -> {
            checkArgument(current.activePreparation().isEmpty(), "Configuration discovery cannot be accepted during active preparation");
            checkArgument(additions.stream().noneMatch(scope -> current.scopes().stream().anyMatch(existing -> existing.id().equals(scope.id()))),
                    "Discovered scope identity already exists");
            checkArgument(registrations.stream().noneMatch(source -> current.sources().stream().anyMatch(existing -> existing.id().equals(source.id()))),
                    "Discovered source identity already exists");
            Map<String, ContextSourceEvidence> retained = new LinkedHashMap<>();
            current.sourceEvidence().forEach(source -> retained.put(source.id(), source));
            captures.forEach(source -> {
                ContextSourceEvidence previous = retained.putIfAbsent(source.id(), source);
                checkArgument(previous == null || previous.equals(source), "Discovery evidence identity was reused with different content");
            });
            Workspace workspace = requireWorkspace(workspaceId);
            Stream.concat(additions.stream().flatMap(scope -> scope.discoveryEvidenceRef().stream()),
                    registrations.stream().flatMap(source -> source.discoveryEvidenceRef().stream())).forEach(ref -> {
                        ContextSourceEvidence source = Optional.ofNullable(retained.get(ref))
                                .orElseThrow(() -> new IllegalArgumentException("Discovery evidence is missing"));
                        checkArgument(source.capture() == ContextSourceEvidence.Capture.BYTEQUAY_READ
                                && source.reference() instanceof ContextReference.RepositoryFile, "Discovery evidence must be a captured repository file");
                        ContextReference.RepositoryFile file = (ContextReference.RepositoryFile) source.reference();
                        checkArgument(file.repositoryId() == workspace.monitoredRepositoryId()
                                && file.repository().fullName().equalsIgnoreCase(workspace.monitoredRepository().fullName()),
                                "Discovery evidence belongs to another monitored repository");
                    });
            return content(current, current.useInReviews(), ImmutableList.<RegisteredContextSource>builder().addAll(current.sources()).addAll(registrations).build(),
                    ImmutableList.<ContextScope>builder().addAll(current.scopes()).addAll(additions).build(), current.entries(),
                    ImmutableList.copyOf(retained.values()));
        });
    }

    /**
     * Creates user knowledge or edits an existing knowledge/lesson entry.
     *
     * @param workspaceId owning retained workspace
     * @param expectedRevision displayed content revision
     * @param edit editable prose, explicit scope, and already-retained source IDs
     * @return notebook with a USER-owned entry; kind and enabled state of an existing entry are retained
     * @throws ContextConflictException for stale content
     * @throws IllegalArgumentException for changed identity, missing evidence, or byte limits
     */
    public RepositoryNotebook editEntry(String workspaceId, long expectedRevision, EditContextEntry edit)
    {
        requireNonNull(edit, "edit is null");
        return mutate(workspaceId, expectedRevision, current -> {
            Optional<ContextEntry> previous = current.entries().stream().filter(entry -> entry.id().equals(edit.id())).findFirst();
            previous.ifPresent(entry -> checkArgument(entry.scopeId().equals(edit.scopeId()), "Entry scope identity cannot change"));
            ContextEntry entry = new ContextEntry(
                    edit.id(), previous.map(ContextEntry::kind).orElse(KNOWLEDGE), edit.scopeId(), edit.title(), edit.summary(), edit.body(),
                    edit.claims(), edit.sourceIds(), USER, previous.map(ContextEntry::enabled).orElse(true), Math.addExact(current.revision(), 1));
            List<ContextEntry> entries = replaceEntry(current, entry, previous.isPresent());
            return content(current, current.useInReviews(), current.sources(), current.scopes(), entries, current.sourceEvidence());
        });
    }

    /** Queues one task-sourced note for user review; only server-captured committed files may supply its evidence. */
    public RepositoryNotebook proposeTaskDraft(String workspaceId, long expectedRevision, TaskDraftProposal proposal)
    {
        requireNonNull(proposal, "proposal is null");
        return mutate(workspaceId, expectedRevision, Optional.of(proposal.taskId()), current -> {
            Workspace workspace = requireWorkspace(workspaceId);
            checkArgument(current.activePreparation().isEmpty(), "Finish the active preparation before proposing a task note");
            checkArgument(current.scopes().stream().anyMatch(scope -> scope.id().equals(proposal.scopeId()) && scope.enabled()),
                    "Note scope is not enabled");
            checkArgument(proposal.evidence().size() >= 1 && proposal.evidence().size() <= 3, "Propose one to three captured repository sources");
            int capturedBytes = proposal.evidence().stream().mapToInt(source -> ContextLimits.bytes(source.excerpt())).sum();
            checkArgument(capturedBytes <= 24 * 1024, "Task note captures exceed 24 KiB");
            proposal.evidence().forEach(source -> {
                checkArgument(source.capture() == ContextSourceEvidence.Capture.BYTEQUAY_READ
                        && source.reference() instanceof ContextReference.RepositoryFile,
                        "Task note sources must be application-captured committed files");
                ContextReference.RepositoryFile file = (ContextReference.RepositoryFile) source.reference();
                checkArgument(file.repositoryId() == workspace.selectedRepositoryId()
                        && file.repository().equals(workspace.selectedRepository()) && file.commit().equals(proposal.baselineSha()),
                        "Task note source is outside its committed repository HEAD");
                checkArgument(source.retrievedAt().isPresent() && source.contentHash().isPresent() && !source.excerpt().isBlank(),
                        "Task note source needs a nonblank captured excerpt and content identity");
            });
            String operationPrefix = proposal.draftId().substring(0, proposal.draftId().lastIndexOf(':') + 1);
            checkArgument(current.pendingDrafts().stream().noneMatch(draft -> draft.id().startsWith(operationPrefix)
                    && !draft.id().equals(proposal.draftId())), "Task proposal key was reused with different content");
            Optional<ContextKnowledgeDraft> replay = current.pendingDrafts().stream().filter(draft -> draft.id().equals(proposal.draftId())).findFirst();
            if (replay.isPresent()) {
                checkArgument(replay.orElseThrow().baselineSha().equals(proposal.baselineSha()), "Task HEAD changed since the saved proposal");
                return current;
            }
            Optional<ContextEntry> active = proposal.entryId().flatMap(id -> current.entries().stream().filter(entry -> entry.id().equals(id)).findFirst());
            checkArgument(proposal.entryId().isPresent() == proposal.replacesVersion().isPresent(),
                    "Updating a note requires its exact entry ID and version");
            if (proposal.entryId().isPresent()) {
                checkArgument(active.isPresent(), "Proposed update target is unavailable");
                ContextEntry previous = active.orElseThrow();
                checkArgument(previous.version() == proposal.replacesVersion().orElseThrow(), "Proposed update target changed");
                checkArgument(previous.kind() == proposal.kind() && previous.scopeId().equals(proposal.scopeId())
                        && previous.enabled() && previous.origin() == GENERATED,
                        "Proposed update cannot change kind or scope or replace user-owned content");
            }
            String suffix = proposal.draftId().substring(proposal.draftId().lastIndexOf(':') + 1);
            String id = proposal.entryId().orElseGet(() -> (proposal.kind() == KNOWLEDGE ? "knowledge:" : "lesson:")
                    + proposal.scopeId() + ":task-" + suffix);
            checkArgument(current.pendingDrafts().stream().noneMatch(draft -> draft.entry().id().equals(id)),
                    "A proposal for this note already awaits review");
            checkArgument(active.isPresent() || current.entries().stream().noneMatch(entry -> entry.id().equals(id)),
                    "Note identity already exists");
            List<String> sourceIds = proposal.evidence().stream().map(ContextSourceEvidence::id).toList();
            ContextEntry entry = new ContextEntry(id, proposal.kind(), proposal.scopeId(), proposal.title(), proposal.summary(), proposal.body(),
                    ImmutableList.of(), sourceIds, GENERATED, true, Math.addExact(current.revision(), 1));
            ContextKnowledgeDraft draft = new ContextKnowledgeDraft(proposal.draftId(), entry, proposal.replacesVersion(), proposal.baselineSha(),
                    "Proposed from committed task HEAD; review the captured sources before approval.");
            Map<String, ContextSourceEvidence> retained = new LinkedHashMap<>();
            current.sourceEvidence().forEach(source -> retained.put(source.id(), source));
            proposal.evidence().forEach(source -> {
                ContextSourceEvidence previous = retained.putIfAbsent(source.id(), source);
                checkArgument(previous == null || previous.equals(source), "Task evidence identity was reused with different content");
            });
            return content(current, current.useInReviews(), current.sources(), current.scopes(), current.entries(),
                    ImmutableList.<ContextKnowledgeDraft>builder().addAll(current.pendingDrafts()).add(draft).build(), ImmutableList.copyOf(retained.values()));
        });
    }

    public record TaskDraftProposal(String draftId, String taskId, ContextEntryKind kind, String scopeId, Optional<String> entryId,
            Optional<Long> replacesVersion, String title, String summary, String body, String baselineSha, List<ContextSourceEvidence> evidence)
    {
        public TaskDraftProposal
        {
            ContextLimits.text(draftId, "draft ID", 200);
            ContextLimits.text(taskId, "proposing task", 200);
            checkArgument(draftId.matches("draft:task:[0-9a-f]{32}:[0-9a-f]{32}"), "Invalid task draft ID");
            requireNonNull(kind, "kind is null");
            ContextLimits.text(scopeId, "scope ID", 1100);
            requireNonNull(entryId, "entryId is null");
            requireNonNull(replacesVersion, "replacesVersion is null");
            ContextLimits.text(title, "title", 200);
            ContextLimits.text(summary, "summary", 1000);
            ContextLimits.text(body, "body", ContextLimits.MAX_BODY_BYTES);
            checkArgument(baselineSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid task HEAD");
            evidence = ImmutableList.copyOf(evidence);
        }
    }

    /**
     * Toggles selection without changing provenance or reviving removed content.
     *
     * @param workspaceId owning workspace
     * @param expectedRevision displayed content revision
     * @param entryId stable existing entry ID
     * @param enabled whether subsequent selections may include this entry
     * @return updated notebook; existing snapshots remain unchanged
     * @throws ContextNotFoundException for an unknown entry
     * @throws ContextConflictException for stale content
     */
    public RepositoryNotebook setEntryEnabled(String workspaceId, long expectedRevision, String entryId, boolean enabled)
    {
        return mutate(workspaceId, expectedRevision, current -> {
            ContextEntry entry = requireEntry(current, entryId);
            if (entry.enabled() == enabled) {
                return current;
            }
            ContextEntry updated = new ContextEntry(entry.id(), entry.kind(), entry.scopeId(), entry.title(), entry.summary(), entry.body(),
                    entry.claims(), entry.sourceIds(), entry.origin(), enabled, Math.addExact(current.revision(), 1));
            return content(current, current.useInReviews(), current.sources(), current.scopes(), replaceEntry(current, updated, true), current.sourceEvidence());
        });
    }

    /** Saves user edits to a pending proposal without making the note selectable by agents. */
    public RepositoryNotebook editDraft(String workspaceId, long expectedRevision, String draftId,
            String title, String summary, String body, List<ContextClaim> claims)
    {
        return mutate(workspaceId, expectedRevision, current -> {
            ContextKnowledgeDraft draft = requireDraft(current, draftId);
            ContextEntry previous = draft.entry();
            ContextEntry edited = new ContextEntry(previous.id(), previous.kind(), previous.scopeId(), title, summary, body,
                    claims, previous.sourceIds(), USER, true, Math.addExact(current.revision(), 1));
            ContextKnowledgeDraft updated = new ContextKnowledgeDraft(draft.id(), edited, draft.replacesVersion(), draft.baselineSha(), draft.explanation());
            return content(current, current.useInReviews(), current.sources(), current.scopes(), current.entries(),
                    current.pendingDrafts().stream().map(value -> value.id().equals(draftId) ? updated : value).collect(toImmutableList()), current.sourceEvidence());
        });
    }

    /** Activates exactly the displayed draft if its target has not been changed, removed, disabled, or claimed by the user. */
    public RepositoryNotebook approveDraft(String workspaceId, long expectedRevision, String draftId)
    {
        return mutate(workspaceId, expectedRevision, current -> {
            ContextKnowledgeDraft draft = requireDraft(current, draftId);
            ContextEntry proposal = draft.entry();
            checkArgument(current.scopes().stream().anyMatch(scope -> scope.id().equals(proposal.scopeId()) && scope.enabled()),
                    "Knowledge scope is disabled");
            Optional<ContextEntry> active = current.entries().stream().filter(entry -> entry.id().equals(proposal.id())).findFirst();
            checkArgument(active.map(ContextEntry::version).equals(draft.replacesVersion()), "Active note changed since this proposal was prepared");
            checkArgument(active.filter(entry -> !entry.scopeId().equals(proposal.scopeId())).isEmpty(), "Active note belongs to another scope");
            checkArgument(active.filter(entry -> entry.kind() != proposal.kind()).isEmpty(), "Active note has a different kind");
            checkArgument(active.filter(entry -> !entry.enabled() || entry.origin() == USER).isEmpty(), "Active note is user-owned or disabled");
            ContextEntry approved = new ContextEntry(proposal.id(), proposal.kind(), proposal.scopeId(), proposal.title(), proposal.summary(), proposal.body(),
                    proposal.claims(), proposal.sourceIds(), proposal.origin(), true, Math.addExact(current.revision(), 1));
            return content(current, current.useInReviews(), current.sources(), current.scopes(), replaceEntry(current, approved, active.isPresent()),
                    current.pendingDrafts().stream().filter(value -> !value.id().equals(draftId)).collect(toImmutableList()), current.sourceEvidence());
        });
    }

    /** Discards a proposal while retaining the active version of its note. */
    public RepositoryNotebook dismissDraft(String workspaceId, long expectedRevision, String draftId)
    {
        return mutate(workspaceId, expectedRevision, current -> {
            requireDraft(current, draftId);
            return content(current, current.useInReviews(), current.sources(), current.scopes(), current.entries(),
                    current.pendingDrafts().stream().filter(value -> !value.id().equals(draftId)).collect(toImmutableList()), current.sourceEvidence());
        });
    }

    /**
     * Saves an explicitly accepted lesson and copies its supporting local evidence before retention can remove it.
     *
     * @param workspaceId owning workspace; review and run must belong to it
     * @param expectedRevision displayed content revision
     * @param lesson edited prose and existing review evidence/discussion IDs
     * @return notebook containing a newly assigned USER-owned MEMORY entry and retained excerpts
     * @throws IllegalArgumentException for cross-workspace/run references, missing evidence, or byte limits
     * @throws ContextConflictException for stale content
     *
     * This local user-action method is never exposed as an autonomous model-write tool.
     */
    public RepositoryNotebook saveLesson(String workspaceId, long expectedRevision, SaveContextLesson lesson)
    {
        requireNonNull(lesson, "lesson is null");
        ContextLimits.unique(lesson.evidenceRefs(), "lesson sources", ContextLimits.MAX_SOURCES);
        checkArgument(lesson.evidenceRefs().size() + lesson.messageId().stream().count() > 0, "Select supporting review evidence or a discussion message");
        checkArgument(lesson.evidenceRefs().size() + lesson.messageId().stream().count() <= ContextLimits.MAX_SOURCES, "Too many lesson sources");
        return mutate(workspaceId, expectedRevision, current -> {
            AgentReview review = reviews.findReview(lesson.reviewId()).orElseThrow(() -> new ContextNotFoundException("Review not found"));
            checkArgument(review.workspaceId().equals(workspaceId), "Review belongs to another workspace");
            ReviewRun run = reviews.findRun(lesson.runId()).orElseThrow(() -> new ContextNotFoundException("Review run not found"));
            checkArgument(run.reviewId().equals(review.id()), "Run belongs to another review");
            List<ContextSourceEvidence> captured = new ArrayList<>();
            for (String ref : lesson.evidenceRefs()) {
                ReviewEvidence evidence = Optional.ofNullable(run.evidence().get(ref)).orElseThrow(() -> new IllegalArgumentException("Unknown evidence reference"));
                ContextReference reference = new ContextReference.Review(review.id(), run.id(), Optional.of(ref), Optional.empty());
                String provenance = "Copied stored review evidence; original source: " + evidence.source()
                        + "\nRecorded provenance: " + evidence.provenance() + "\nRecorded explanation: " + evidence.explanation();
                captured.add(capture("review:" + run.id() + ":" + ref, reference, provenance, evidence.excerpt(), run.headSha()));
            }
            lesson.messageId().ifPresent(id -> {
                ReviewMessage message = reviews.listMessages(review.id()).stream().filter(value -> value.id().equals(id)).findFirst()
                        .orElseThrow(() -> new ContextNotFoundException("Review message not found"));
                checkArgument(message.runId().equals(run.id()), "Message belongs to another run");
                ContextReference reference = new ContextReference.Review(review.id(), run.id(), Optional.empty(), Optional.of(id));
                captured.add(capture("message:" + id + ":" + message.version(), reference, "Saved local review discussion by " + message.author(), message.body(), run.headSha()));
            });
            List<String> sourceIds = captured.stream().map(ContextSourceEvidence::id).collect(toImmutableList());
            ContextEntry entry = new ContextEntry("lesson:" + UUID.randomUUID(), MEMORY, lesson.scopeId(), lesson.title(), lesson.summary(), lesson.body(),
                    ImmutableList.of(new ContextClaim(lesson.summary(), ContextClaim.Basis.USER_LESSON, sourceIds)), sourceIds, USER, true, Math.addExact(current.revision(), 1));
            // Preserve the first captured version when another lesson cites the same immutable source identity.
            Set<String> existing = current.sourceEvidence().stream().map(ContextSourceEvidence::id).collect(toSet());
            List<ContextSourceEvidence> evidence = ImmutableList.<ContextSourceEvidence>builder().addAll(current.sourceEvidence())
                    .addAll(captured.stream().filter(value -> !existing.contains(value.id())).collect(toImmutableList())).build();
            return content(current, current.useInReviews(), current.sources(), current.scopes(), replaceEntry(current, entry, false), evidence);
        });
    }

    /** Idempotent local deletion after the caller's verified workspace cleanup; shares the mutation lock to prevent orphan recreation. */
    public void deleteWorkspaceData(String workspaceId)
    {
        checkText(workspaceId, "workspaceId", 200);
        WorkspaceOperationLocks.withLock(workspaceId, () -> {
            settings.delete(key(workspaceId));
            settings.delete(ContextBuildStore.PREFIX + workspaceId);
            settings.delete(ContextDiscoveryReportStore.PREFIX + workspaceId);
            settings.delete(ContextLearningPlanStore.PREFIX + workspaceId);
            settings.delete(ContextMergedCandidateStore.PREFIX + workspaceId);
            settings.delete(ContextBuildStore.RUNS_PREFIX + workspaceId);
            settings.delete(TIMES_PREFIX + workspaceId);
        });
    }

    private RepositoryNotebook mutate(String workspaceId, long expectedRevision, UnaryOperator<RepositoryNotebook> operation)
    {
        return mutate(workspaceId, expectedRevision, Optional.empty(), operation);
    }

    private RepositoryNotebook mutate(String workspaceId, long expectedRevision, Optional<String> proposer, UnaryOperator<RepositoryNotebook> operation)
    {
        checkArgument(expectedRevision >= 0, "Negative expected revision");
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            RepositoryNotebook current = getNotebook(workspaceId);
            if (current.revision() != expectedRevision) {
                throw new ContextConflictException(current.revision());
            }
            RepositoryNotebook changed = operation.apply(current);
            if (changed.equals(current)) {
                return current;
            }
            RepositoryNotebook saved = new RepositoryNotebook(1, Math.addExact(current.revision(), 1),
                    changed.useInReviews(), changed.useInDevelopment(), changed.sources(), changed.scopes(),
                    changed.entries(), changed.pendingDrafts(), changed.sourceEvidence(), changed.activePreparation());
            String json = encode(saved);
            checkArgument(ContextLimits.bytes(json) <= ContextLimits.MAX_NOTEBOOK_BYTES, "Serialized notebook exceeds UTF-8 limit");
            settings.put(key(workspaceId), json);
            stamp(workspaceId, current, saved, proposer);
            return saved;
        });
    }

    /**
     * Records when each new note version and draft appeared. An approved version inherits its draft's proposing task;
     * a preparation's drafts belong to the task that owns it.
     */
    private void stamp(String workspaceId, RepositoryNotebook previous, RepositoryNotebook saved, Optional<String> proposer)
    {
        ContextTimes times = readTimes(workspaceId);
        Instant now = Instant.now(clock);
        Optional<String> owner = proposer.or(() -> previous.activePreparation().map(ActiveContextPreparation::ownerTaskId));
        Map<String, ContextTimes.Stamp> drafts = new LinkedHashMap<>();
        for (ContextKnowledgeDraft draft : saved.pendingDrafts()) {
            Optional<ContextTimes.Stamp> known = Optional.ofNullable(times.drafts().get(draft.id()));
            boolean created = previous.pendingDrafts().stream().noneMatch(value -> value.id().equals(draft.id()));
            known.or(() -> created ? Optional.of(new ContextTimes.Stamp(now, owner)) : Optional.empty()).ifPresent(stamp -> drafts.put(draft.id(), stamp));
        }
        Map<String, ContextTimes.Stamp> entries = new LinkedHashMap<>();
        for (ContextEntry entry : saved.entries()) {
            String key = ContextTimes.key(entry.id(), entry.version());
            Optional<ContextTimes.Stamp> known = Optional.ofNullable(times.entries().get(key));
            boolean created = previous.entries().stream().noneMatch(value -> value.id().equals(entry.id()) && value.version() == entry.version());
            Optional<String> approvedFrom = previous.pendingDrafts().stream()
                    .filter(draft -> draft.entry().id().equals(entry.id()) && saved.pendingDrafts().stream().noneMatch(value -> value.id().equals(draft.id())))
                    .findFirst().flatMap(draft -> Optional.ofNullable(times.drafts().get(draft.id()))).flatMap(ContextTimes.Stamp::taskId);
            known.or(() -> created ? Optional.of(new ContextTimes.Stamp(now, approvedFrom)) : Optional.empty()).ifPresent(stamp -> entries.put(key, stamp));
        }
        ContextTimes updated = new ContextTimes(entries, drafts);
        if (!updated.equals(times)) {
            try {
                settings.put(TIMES_PREFIX + workspaceId, mapper.writeValueAsString(updated));
            }
            catch (JsonProcessingException failure) {
                throw new ContextStorageException("Repository context times cannot be saved", failure);
            }
        }
    }

    private ContextTimes readTimes(String workspaceId)
    {
        return settings.get(TIMES_PREFIX + workspaceId).map(json -> {
            try {
                return requireNonNull(mapper.readValue(json, ContextTimes.class), "Saved times are null");
            }
            catch (JsonProcessingException | NullPointerException failure) {
                throw new ContextStorageException("Saved repository context times cannot be read", failure);
            }
        }).orElseGet(ContextTimes::empty);
    }

    private static RepositoryNotebook content(RepositoryNotebook current, boolean useInReviews, List<RegisteredContextSource> sources, List<ContextScope> scopes,
            List<ContextEntry> entries, List<ContextSourceEvidence> evidence)
    {
        return content(current, useInReviews, current.useInDevelopment(), sources, scopes, entries, evidence);
    }

    private static RepositoryNotebook content(RepositoryNotebook current, boolean useInReviews, boolean useInDevelopment,
            List<RegisteredContextSource> sources, List<ContextScope> scopes, List<ContextEntry> entries, List<ContextSourceEvidence> evidence)
    {
        return content(current, useInReviews, useInDevelopment, sources, scopes, entries, current.pendingDrafts(), evidence);
    }

    private static RepositoryNotebook content(RepositoryNotebook current, boolean useInReviews, List<RegisteredContextSource> sources, List<ContextScope> scopes,
            List<ContextEntry> entries, List<ContextKnowledgeDraft> drafts, List<ContextSourceEvidence> evidence)
    {
        return content(current, useInReviews, current.useInDevelopment(), sources, scopes, entries, drafts, evidence);
    }

    private static RepositoryNotebook content(RepositoryNotebook current, boolean useInReviews, boolean useInDevelopment,
            List<RegisteredContextSource> sources, List<ContextScope> scopes, List<ContextEntry> entries, List<ContextKnowledgeDraft> drafts,
            List<ContextSourceEvidence> evidence)
    {
        Set<String> retained = retainedSources(sources, scopes, entries, drafts);
        return new RepositoryNotebook(1, current.revision(), useInReviews, useInDevelopment, sources, scopes, entries, drafts,
                evidence.stream().filter(source -> retained.contains(source.id())).collect(toImmutableList()), current.activePreparation());
    }

    static Set<String> retainedSources(List<RegisteredContextSource> sources, List<ContextScope> scopes, List<ContextEntry> entries,
            List<ContextKnowledgeDraft> drafts)
    {
        return Stream.of(entries.stream().flatMap(entry -> entry.sourceIds().stream()),
                        drafts.stream().flatMap(draft -> draft.entry().sourceIds().stream()),
                        scopes.stream().flatMap(scope -> scope.preparation().stream()).flatMap(result -> result.checkedReads().stream())
                                .flatMap(read -> read.evidenceRef().stream()),
                        scopes.stream().flatMap(scope -> scope.discoveryEvidenceRef().stream()), sources.stream().flatMap(source -> source.discoveryEvidenceRef().stream()))
                .flatMap(stream -> stream).collect(toSet());
    }

    /** Writes fenced preparation metadata/content; the caller's SQLite unit of work also journals the review result. */
    RepositoryNotebook savePreparation(String workspaceId, RepositoryNotebook expected, RepositoryNotebook replacement)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> {
            RepositoryNotebook current = getNotebook(workspaceId);
            if (!current.equals(expected)) {
                throw new ContextConflictException(current.revision());
            }
            String json = encode(replacement);
            checkArgument(ContextLimits.bytes(json) <= ContextLimits.MAX_NOTEBOOK_BYTES, "Serialized notebook exceeds UTF-8 limit");
            settings.put(key(workspaceId), json);
            stamp(workspaceId, current, replacement, Optional.empty());
            return replacement;
        });
    }

    private static List<ContextEntry> replaceEntry(RepositoryNotebook current, ContextEntry replacement, boolean exists)
    {
        if (exists) {
            return current.entries().stream().map(entry -> entry.id().equals(replacement.id()) ? replacement : entry).collect(toImmutableList());
        }
        return ImmutableList.<ContextEntry>builder().addAll(current.entries()).add(replacement).build();
    }

    private static ContextEntry requireEntry(RepositoryNotebook notebook, String id)
    {
        return notebook.entries().stream().filter(entry -> entry.id().equals(id)).findFirst().orElseThrow(() -> new ContextNotFoundException("Notebook entry not found"));
    }

    private static ContextKnowledgeDraft requireDraft(RepositoryNotebook notebook, String id)
    {
        return notebook.pendingDrafts().stream().filter(draft -> draft.id().equals(id)).findFirst()
                .orElseThrow(() -> new ContextNotFoundException("Knowledge draft not found"));
    }

    private Workspace requireWorkspace(String workspaceId)
    {
        checkText(workspaceId, "workspaceId", 200);
        return workspaces.findById(workspaceId).orElseThrow(() -> new ContextNotFoundException("Workspace not found"));
    }

    private RepositoryNotebook decode(String json)
    {
        try {
            checkArgument(ContextLimits.bytes(json) <= ContextLimits.MAX_NOTEBOOK_BYTES, "Saved notebook exceeds UTF-8 limit");
            JsonNode parsed = mapper.readTree(json);
            checkArgument(parsed instanceof ObjectNode, "Saved notebook must be an object");
            ObjectNode saved = (ObjectNode) parsed;
            if (!saved.has("useInDevelopment")) {
                saved.put("useInDevelopment", true);
            }
            if (!saved.has("pendingDrafts")) {
                saved.putArray("pendingDrafts");
            }
            saved.remove("suppressedKnowledgeIds");
            return requireNonNull(mapper.treeToValue(saved, RepositoryNotebook.class), "Saved notebook is null");
        }
        catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
            throw new ContextStorageException("Saved repository context could not be read", failure);
        }
    }

    private String encode(RepositoryNotebook notebook)
    {
        try {
            return mapper.writeValueAsString(notebook);
        }
        catch (JsonProcessingException failure) {
            throw new ContextStorageException("Repository context could not be encoded", failure);
        }
    }

    private static ContextSourceEvidence capture(String id, ContextReference reference, String provenance, String excerpt, String head)
    {
        return new ContextSourceEvidence(id, reference, provenance + "\nReviewed head (not necessarily the source revision): " + head,
                excerpt, ContextSourceEvidence.Capture.BYTEQUAY_READ,
                Optional.of(Instant.now()), Optional.of("sha256:" + Hashing.sha256().hashString(excerpt, UTF_8)), Optional.empty(), false);
    }

    private static String key(String workspaceId)
    {
        return SETTINGS_PREFIX + workspaceId;
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ContextMatch.Repository.class, name = "REPOSITORY"),
            @JsonSubTypes.Type(value = ContextMatch.Selected.class, name = "SELECTED")})
    interface MatchJson {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ContextReference.RepositoryFile.class, name = "REPOSITORY_FILE"),
            @JsonSubTypes.Type(value = ContextReference.PullRequest.class, name = "PR"),
            @JsonSubTypes.Type(value = ContextReference.Issue.class, name = "ISSUE"),
            @JsonSubTypes.Type(value = ContextReference.Task.class, name = "TASK"),
            @JsonSubTypes.Type(value = ContextReference.Review.class, name = "REVIEW"),
            @JsonSubTypes.Type(value = ContextReference.Document.class, name = "DOCUMENT")})
    interface ReferenceJson {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ContextRepositoryRead.class, name = "REPOSITORY_FILE"),
            @JsonSubTypes.Type(value = ContextDocumentRead.class, name = "DOCUMENT"),
            @JsonSubTypes.Type(value = ContextHistoryRead.class, name = "HISTORY")})
    interface SourceReadJson {}
}
