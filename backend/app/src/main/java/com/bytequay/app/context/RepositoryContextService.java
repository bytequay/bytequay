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
import com.bytequay.core.context.ContextGrade;
import com.bytequay.core.context.ContextHistoryRead;
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
import java.util.function.BiConsumer;
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
    static final String DISPUTES_PREFIX = "workspace.repositoryContextDisputes.";

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

    /** When retained note versions appeared and which task saved them; content written before stamping began has no time. */
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
     * @param scopes new path scopes with server-validated discovery evidence references
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
        additions.forEach(scope -> checkArgument(scope.match() instanceof ContextMatch.Selected match
                        && !match.pathPrefixes().isEmpty() && match.terms().isEmpty()
                        && scope.preparation().isEmpty() && scope.discoveryEvidenceRef().isPresent(),
                "Discovered scope needs paths and captured evidence, without terms or preparation metadata"));
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
        return mutate(workspaceId, expectedRevision, Optional.empty(), afterUserChange(workspaceId, edit.id(), false), current -> {
            Optional<ContextEntry> previous = current.entries().stream().filter(entry -> entry.id().equals(edit.id())).findFirst();
            previous.ifPresent(entry -> checkArgument(entry.scopeId().equals(edit.scopeId()), "Entry scope identity cannot change"));
            ContextEntry entry = new ContextEntry(
                    edit.id(), previous.map(ContextEntry::kind).orElse(KNOWLEDGE), edit.scopeId(), edit.title(), edit.summary(), edit.body(),
                    edit.claims(), edit.sourceIds(), USER, previous.map(ContextEntry::enabled).orElse(true), Math.addExact(current.revision(), 1));
            List<ContextEntry> replaced = previous.filter(old -> !sameText(old, entry)).map(old -> keep(current, old)).orElse(current.replaced());
            return content(current, current.useInReviews(), current.useInDevelopment(), current.sources(), current.scopes(),
                    replaceEntry(current, entry, previous.isPresent()), replaced, current.sourceEvidence());
        });
    }

    /**
     * Saves one task-sourced note as the active version; only server-captured committed files may supply its evidence.
     * An update must name the exact current version of an enabled, agent-written note, whose text is kept for Restore.
     */
    public RepositoryNotebook saveTaskNote(String workspaceId, long expectedRevision, TaskNote note)
    {
        requireNonNull(note, "note is null");
        return mutate(workspaceId, expectedRevision, Optional.of(note.taskId()), current -> {
            Workspace workspace = requireWorkspace(workspaceId);
            checkArgument(current.activePreparation().isEmpty(), "Finish the active preparation before saving a task note");
            checkArgument(current.scopes().stream().anyMatch(scope -> scope.id().equals(note.scopeId()) && scope.enabled()),
                    "Note scope is not enabled");
            checkArgument(note.evidence().size() >= 1 && note.evidence().size() <= 3, "Cite one to three captured repository sources");
            int capturedBytes = note.evidence().stream().mapToInt(source -> ContextLimits.bytes(source.excerpt())).sum();
            checkArgument(capturedBytes <= 24 * 1024, "Task note captures exceed 24 KiB");
            note.evidence().forEach(source -> {
                checkArgument(source.capture() == ContextSourceEvidence.Capture.BYTEQUAY_READ
                        && source.reference() instanceof ContextReference.RepositoryFile,
                        "Task note sources must be application-captured committed files");
                ContextReference.RepositoryFile file = (ContextReference.RepositoryFile) source.reference();
                checkArgument(file.repositoryId() == workspace.selectedRepositoryId()
                        && file.repository().equals(workspace.selectedRepository()) && file.commit().equals(note.baselineSha()),
                        "Task note source is outside its committed repository HEAD");
                checkArgument(source.retrievedAt().isPresent() && source.contentHash().isPresent() && !source.excerpt().isBlank(),
                        "Task note source needs a nonblank captured excerpt and content identity");
            });
            Optional<ContextEntry> active = note.entryId().flatMap(id -> current.entries().stream().filter(entry -> entry.id().equals(id)).findFirst());
            checkArgument(note.entryId().isPresent() == note.replacesVersion().isPresent(),
                    "Updating a note requires its exact entry ID and version");
            if (note.entryId().isPresent()) {
                checkArgument(active.isPresent(), "The note to update is unavailable");
                ContextEntry previous = active.orElseThrow();
                checkArgument(previous.version() == note.replacesVersion().orElseThrow(), "The note changed since you read it; read it again");
                checkArgument(previous.kind() == note.kind() && previous.scopeId().equals(note.scopeId())
                        && previous.enabled() && previous.origin() == GENERATED,
                        "An update cannot change kind or scope, or replace user-owned or disabled content");
            }
            String id = note.entryId().orElseGet(() -> taskNoteId(note));
            checkArgument(active.isPresent() || current.entries().stream().noneMatch(entry -> entry.id().equals(id)),
                    "Note identity already exists");
            List<String> sourceIds = note.evidence().stream().map(ContextSourceEvidence::id).toList();
            ContextEntry entry = new ContextEntry(id, note.kind(), note.scopeId(), note.title(), note.summary(), note.body(),
                    ImmutableList.of(), sourceIds, GENERATED, true, Math.addExact(current.revision(), 1));
            Map<String, ContextSourceEvidence> retained = new LinkedHashMap<>();
            current.sourceEvidence().forEach(source -> retained.put(source.id(), source));
            note.evidence().forEach(source -> {
                ContextSourceEvidence previous = retained.putIfAbsent(source.id(), source);
                checkArgument(previous == null || previous.equals(source), "Task evidence identity was reused with different content");
            });
            return content(current, current.useInReviews(), current.useInDevelopment(), current.sources(), current.scopes(),
                    replaceEntry(current, entry, active.isPresent()), active.map(old -> keep(current, old)).orElse(current.replaced()),
                    ImmutableList.copyOf(retained.values()));
        });
    }

    /** A new task note's identity comes from its operation, so a retried save names the same note. */
    static String taskNoteId(TaskNote note)
    {
        return (note.kind() == KNOWLEDGE ? "knowledge:" : "lesson:") + note.scopeId() + ":task-"
                + note.operationId().substring(note.operationId().lastIndexOf(':') + 1);
    }

    public record TaskNote(String operationId, String taskId, ContextEntryKind kind, String scopeId, Optional<String> entryId,
            Optional<Long> replacesVersion, String title, String summary, String body, String baselineSha, List<ContextSourceEvidence> evidence)
    {
        public TaskNote
        {
            ContextLimits.text(operationId, "operation ID", 200);
            ContextLimits.text(taskId, "saving task", 200);
            checkArgument(operationId.matches("task:[0-9a-f]{32}:[0-9a-f]{32}"), "Invalid task note operation ID");
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

    /**
     * Makes a note's replaced text current again as a new version, keeping the text it replaces so Restore can be undone.
     *
     * @throws ContextNotFoundException when the note or its replaced version does not exist
     * @throws ContextConflictException for stale content
     */
    public RepositoryNotebook restoreEntry(String workspaceId, long expectedRevision, String entryId)
    {
        return mutate(workspaceId, expectedRevision, Optional.empty(), afterUserChange(workspaceId, entryId, true), current -> {
            ContextEntry entry = requireEntry(current, entryId);
            ContextEntry old = current.replaced().stream().filter(value -> value.id().equals(entryId)).findFirst()
                    .orElseThrow(() -> new ContextNotFoundException("This note has no earlier version to restore"));
            ContextEntry restored = new ContextEntry(old.id(), old.kind(), old.scopeId(), old.title(), old.summary(), old.body(),
                    old.claims(), old.sourceIds(), old.origin(), entry.enabled(), Math.addExact(current.revision(), 1));
            return content(current, current.useInReviews(), current.useInDevelopment(), current.sources(), current.scopes(),
                    replaceEntry(current, restored, true), keep(current, entry), current.sourceEvidence());
        });
    }

    /** Saves a note's current text and owner again as a new version, which closes its open reports; the text is unchanged. */
    public RepositoryNotebook keepEntry(String workspaceId, long expectedRevision, String entryId)
    {
        return mutate(workspaceId, expectedRevision, Optional.empty(), afterUserChange(workspaceId, entryId, true), current -> {
            ContextEntry entry = requireEntry(current, entryId);
            ContextEntry kept = new ContextEntry(entry.id(), entry.kind(), entry.scopeId(), entry.title(), entry.summary(), entry.body(),
                    entry.claims(), entry.sourceIds(), entry.origin(), entry.enabled(), Math.addExact(current.revision(), 1));
            return content(current, current.useInReviews(), current.sources(), current.scopes(), replaceEntry(current, kept, true), current.sourceEvidence());
        });
    }

    /**
     * Disables the enabled notes one run added that nobody changed since: of the notes it names, those whose current version
     * its task saved and that have no replaced version. A batch asks several questions in one task, so the run names its
     * notes. Updates the run made to existing notes stay; each can be restored.
     */
    public RepositoryNotebook disableRunNotes(String workspaceId, long expectedRevision, String taskId, List<String> noteIds)
    {
        checkText(taskId, "taskId", 200);
        checkArgument(noteIds.size() <= 1000, "A run names at most 1000 notes");
        return mutate(workspaceId, expectedRevision, current -> {
            ContextTimes times = readTimes(workspaceId);
            Set<String> changed = current.replaced().stream().map(ContextEntry::id).collect(toSet());
            List<ContextEntry> entries = current.entries().stream().map(entry -> entry.enabled() && !changed.contains(entry.id()) && noteIds.contains(entry.id())
                    && Optional.ofNullable(times.entries().get(ContextTimes.key(entry.id(), entry.version()))).flatMap(ContextTimes.Stamp::taskId)
                            .filter(taskId::equals).isPresent()
                    ? new ContextEntry(entry.id(), entry.kind(), entry.scopeId(), entry.title(), entry.summary(), entry.body(), entry.claims(),
                            entry.sourceIds(), entry.origin(), false, Math.addExact(current.revision(), 1))
                    : entry).collect(toImmutableList());
            return content(current, current.useInReviews(), current.sources(), current.scopes(), entries, current.sourceEvidence());
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
            settings.delete(DISPUTES_PREFIX + workspaceId);
            settings.delete(ContextPageCaptures.PREFIX + workspaceId);
            settings.delete(ContextWebLookups.PREFIX + workspaceId);
            settings.delete(ContextSpecCheckStore.PREFIX + workspaceId);
        });
    }

    /** Which notes agents disagree about and how each note's last dispute was settled; a local read only. */
    public ContextDisputes getDisputes(String workspaceId)
    {
        requireWorkspace(workspaceId);
        return readDisputes(workspaceId);
    }

    /**
     * Marks an enabled note contested from the given version on. A note that is already contested keeps its first statement
     * through further agent corrections until a settling save or the user's Keep, Restore, Edit or Disable.
     */
    public void contest(String workspaceId, String entryId, long version, String statement)
    {
        ContextDisputes.Contest contest = new ContextDisputes.Contest(statement, version);
        WorkspaceOperationLocks.withLock(workspaceId, () -> {
            ContextDisputes current = readDisputes(workspaceId);
            boolean enabled = getNotebook(workspaceId).entries().stream().anyMatch(entry -> entry.id().equals(entryId) && entry.enabled());
            if (enabled && current.contest(entryId).isEmpty()) {
                writeDisputes(workspaceId, new ContextDisputes(with(current.contested(), entryId, contest), current.settlements()));
            }
            return null;
        });
    }

    /**
     * Saves an investigation's outcome on a contested agent-written note and settles it with the root cause. New text becomes the
     * next version and the replaced text stays restorable; KEEP_PREVIOUS restores the replaced text; KEEP_CURRENT changes nothing.
     * The settled statement must be in the text that holds after the outcome.
     */
    public RepositoryNotebook settleEntry(String workspaceId, long expectedRevision, String taskId, Settle settle)
    {
        requireNonNull(settle, "settle is null");
        checkText(taskId, "taskId", 200);
        if (settle.outcome() == Settle.Outcome.KEEP_CURRENT) {
            return WorkspaceOperationLocks.withLock(workspaceId, () -> {
                RepositoryNotebook current = getNotebook(workspaceId);
                if (current.revision() != expectedRevision) {
                    throw new ContextConflictException(current.revision());
                }
                ContextEntry kept = settleable(workspaceId, current, settle);
                checkArgument(ContextGradeService.text(kept).contains(settle.statement()), "The settled statement is not in the note's current text");
                settle(workspaceId, settle.entryId(), settlement(settle, kept.version()));
                return current;
            });
        }
        return mutate(workspaceId, expectedRevision, Optional.of(taskId), (previous, saved) -> settle(workspaceId, settle.entryId(), settlement(settle,
                saved.entries().stream().filter(entry -> entry.id().equals(settle.entryId())).findFirst().orElseThrow().version())), current -> {
                    ContextEntry entry = settleable(workspaceId, current, settle);
                    long version = Math.addExact(current.revision(), 1);
                    List<ContextSourceEvidence> evidence = current.sourceEvidence();
                    ContextEntry settled;
                    if (settle.outcome() == Settle.Outcome.KEEP_PREVIOUS) {
                        ContextEntry old = current.replaced().stream().filter(value -> value.id().equals(entry.id())).findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("The note has no previous text to keep"));
                        settled = new ContextEntry(old.id(), old.kind(), old.scopeId(), old.title(), old.summary(), old.body(), old.claims(), old.sourceIds(),
                                old.origin(), true, version);
                    }
                    else {
                        Settle.Text text = settle.text().orElseThrow();
                        settled = new ContextEntry(entry.id(), entry.kind(), entry.scopeId(), text.title(), text.summary(), text.body(), ImmutableList.of(),
                                settle.evidence().stream().map(ContextSourceEvidence::id).toList(), GENERATED, true, version);
                        Map<String, ContextSourceEvidence> retained = new LinkedHashMap<>();
                        current.sourceEvidence().forEach(source -> retained.put(source.id(), source));
                        settle.evidence().forEach(source -> {
                            ContextSourceEvidence previous = retained.putIfAbsent(source.id(), source);
                            checkArgument(previous == null || previous.equals(source), "Investigation evidence identity was reused with different content");
                        });
                        evidence = ImmutableList.copyOf(retained.values());
                    }
                    checkArgument(ContextGradeService.text(settled).contains(settle.statement()), "The settled statement must be in the note's settled text");
                    return content(current, current.useInReviews(), current.useInDevelopment(), current.sources(), current.scopes(),
                            replaceEntry(current, settled, true), keep(current, entry), evidence);
                });
    }

    private ContextEntry settleable(String workspaceId, RepositoryNotebook notebook, Settle settle)
    {
        ContextEntry entry = requireEntry(notebook, settle.entryId());
        checkArgument(entry.version() == settle.version() && entry.enabled() && entry.origin() == GENERATED,
                "The note changed since the investigation started; investigate it again");
        checkArgument(readDisputes(workspaceId).contest(settle.entryId()).isPresent(), "The dispute was settled while the investigation ran");
        return entry;
    }

    private static ContextDisputes.Settlement settlement(Settle settle, long version)
    {
        return new ContextDisputes.Settlement(version, ContextDisputes.By.INVESTIGATION, settle.statement(), Optional.of(settle.rootCause()));
    }

    /** One investigation outcome; new text cites repository lines the run read at the monitored branch head. */
    public record Settle(String entryId, long version, Outcome outcome, Optional<Text> text, List<ContextSourceEvidence> evidence, String statement, String rootCause)
    {
        public Settle
        {
            ContextLimits.text(entryId, "settled note", 1200);
            checkArgument(version > 0, "Invalid settled version");
            requireNonNull(outcome, "outcome is null");
            requireNonNull(text, "text is null");
            evidence = ImmutableList.copyOf(evidence);
            boolean rewrites = outcome != Outcome.KEEP_CURRENT && outcome != Outcome.KEEP_PREVIOUS;
            checkArgument(rewrites == text.isPresent() && rewrites == !evidence.isEmpty(),
                    "REWRITE, NARROW and QUESTION save new text with cited lines; KEEP_CURRENT and KEEP_PREVIOUS take neither");
            checkArgument(evidence.size() <= 3, "Cite one to three repository line ranges");
            evidence.forEach(source -> checkArgument(source.capture() == ContextSourceEvidence.Capture.BYTEQUAY_READ
                    && source.reference() instanceof ContextReference.RepositoryFile && !source.excerpt().isBlank(),
                    "Settled text cites only lines the run read from the repository"));
            ContextLimits.text(statement, "settled statement", ContextGrade.MAX_QUOTE_BYTES);
            ContextLimits.text(rootCause, "root cause", ContextDisputes.Settlement.MAX_ROOT_CAUSE_BYTES);
        }

        public enum Outcome
        {
            REWRITE, KEEP_CURRENT, KEEP_PREVIOUS, NARROW, QUESTION
        }

        public record Text(String title, String summary, String body)
        {
            public Text
            {
                ContextLimits.text(title, "title", 200);
                ContextLimits.text(summary, "summary", 1000);
                ContextLimits.text(body, "body", ContextLimits.MAX_BODY_BYTES);
            }
        }
    }

    /** Records an agent's settling save of the version it produced and ends the note's contest. */
    public void settle(String workspaceId, String entryId, ContextDisputes.Settlement settlement)
    {
        requireNonNull(settlement, "settlement is null");
        WorkspaceOperationLocks.withLock(workspaceId, () -> {
            ContextDisputes current = readDisputes(workspaceId);
            writeDisputes(workspaceId, new ContextDisputes(without(current.contested(), entryId), with(current.settlements(), entryId, settlement)));
            return null;
        });
    }

    /**
     * The user's Edit ends the note's contest. Keep and Restore also record the user's choice as a settlement of the whole
     * version, so an agent that later disputes any statement of it on unchanged lines contests the note again.
     */
    private BiConsumer<RepositoryNotebook, RepositoryNotebook> afterUserChange(String workspaceId, String entryId, boolean choice)
    {
        return (previous, saved) -> {
            ContextDisputes current = readDisputes(workspaceId);
            Optional<ContextDisputes.Contest> contest = current.contest(entryId);
            if (contest.isEmpty()) {
                return;
            }
            Map<String, ContextDisputes.Settlement> settlements = current.settlements();
            Optional<Long> version = saved.entries().stream().filter(entry -> entry.id().equals(entryId)).map(ContextEntry::version).findFirst();
            if (choice && version.isPresent()) {
                settlements = with(settlements, entryId, new ContextDisputes.Settlement(version.orElseThrow(), ContextDisputes.By.USER,
                        contest.orElseThrow().statement(), Optional.empty()));
            }
            writeDisputes(workspaceId, new ContextDisputes(without(current.contested(), entryId), settlements));
        };
    }

    /** Only an enabled note can be contested; disabling a note, alone or with its run's notes, ends its contest. */
    private void endInactiveContests(String workspaceId, RepositoryNotebook saved)
    {
        ContextDisputes current = readDisputes(workspaceId);
        Set<String> enabled = saved.entries().stream().filter(ContextEntry::enabled).map(ContextEntry::id).collect(toSet());
        if (!enabled.containsAll(current.contested().keySet())) {
            Map<String, ContextDisputes.Contest> contested = new LinkedHashMap<>(current.contested());
            contested.keySet().retainAll(enabled);
            writeDisputes(workspaceId, new ContextDisputes(contested, current.settlements()));
        }
    }

    private ContextDisputes readDisputes(String workspaceId)
    {
        return settings.get(DISPUTES_PREFIX + workspaceId).map(json -> {
            try {
                return requireNonNull(mapper.readValue(json, ContextDisputes.class), "Saved disputes are null");
            }
            catch (JsonProcessingException | NullPointerException failure) {
                throw new ContextStorageException("Saved repository context disputes cannot be read", failure);
            }
        }).orElseGet(ContextDisputes::empty);
    }

    private void writeDisputes(String workspaceId, ContextDisputes disputes)
    {
        try {
            settings.put(DISPUTES_PREFIX + workspaceId, mapper.writeValueAsString(disputes));
        }
        catch (JsonProcessingException failure) {
            throw new ContextStorageException("Repository context disputes cannot be saved", failure);
        }
    }

    private static <T> Map<String, T> with(Map<String, T> values, String key, T value)
    {
        Map<String, T> updated = new LinkedHashMap<>(values);
        updated.put(key, value);
        return updated;
    }

    private static <T> Map<String, T> without(Map<String, T> values, String key)
    {
        Map<String, T> updated = new LinkedHashMap<>(values);
        updated.remove(key);
        return updated;
    }

    private RepositoryNotebook mutate(String workspaceId, long expectedRevision, UnaryOperator<RepositoryNotebook> operation)
    {
        return mutate(workspaceId, expectedRevision, Optional.empty(), operation);
    }

    private RepositoryNotebook mutate(String workspaceId, long expectedRevision, Optional<String> proposer, UnaryOperator<RepositoryNotebook> operation)
    {
        return mutate(workspaceId, expectedRevision, proposer, (previous, saved) -> {}, operation);
    }

    /** {@code afterSave} runs under the same workspace lock once the changed notebook is saved. */
    private RepositoryNotebook mutate(String workspaceId, long expectedRevision, Optional<String> proposer,
            BiConsumer<RepositoryNotebook, RepositoryNotebook> afterSave, UnaryOperator<RepositoryNotebook> operation)
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
                    changed.entries(), changed.replaced(), changed.sourceEvidence(), changed.activePreparation());
            String json = encode(saved);
            checkArgument(ContextLimits.bytes(json) <= ContextLimits.MAX_STORED_NOTEBOOK_BYTES, "Serialized notebook exceeds UTF-8 limit");
            settings.put(key(workspaceId), json);
            stamp(workspaceId, current, saved, proposer);
            afterSave.accept(current, saved);
            endInactiveContests(workspaceId, saved);
            return saved;
        });
    }

    /** Records when each new retained note version appeared and which task, if any, saved it. */
    private void stamp(String workspaceId, RepositoryNotebook previous, RepositoryNotebook saved, Optional<String> owner)
    {
        ContextTimes times = readTimes(workspaceId);
        Instant now = Instant.now(clock);
        Map<String, ContextTimes.Stamp> entries = new LinkedHashMap<>();
        for (ContextEntry entry : Stream.concat(saved.entries().stream(), saved.replaced().stream()).toList()) {
            String key = ContextTimes.key(entry.id(), entry.version());
            Optional<ContextTimes.Stamp> known = Optional.ofNullable(times.entries().get(key));
            boolean created = Stream.concat(previous.entries().stream(), previous.replaced().stream())
                    .noneMatch(value -> value.id().equals(entry.id()) && value.version() == entry.version());
            known.or(() -> created ? Optional.of(new ContextTimes.Stamp(now, owner)) : Optional.empty()).ifPresent(stamp -> entries.put(key, stamp));
        }
        ContextTimes updated = new ContextTimes(entries);
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
                JsonNode parsed = mapper.readTree(json);
                checkArgument(parsed instanceof ObjectNode, "Saved times must be an object");
                // Earlier builds also stamped approval drafts, which no longer exist.
                ((ObjectNode) parsed).remove("drafts");
                return requireNonNull(mapper.treeToValue(parsed, ContextTimes.class), "Saved times are null");
            }
            catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
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
        return content(current, useInReviews, useInDevelopment, sources, scopes, entries, current.replaced(), evidence);
    }

    private static RepositoryNotebook content(RepositoryNotebook current, boolean useInReviews, boolean useInDevelopment,
            List<RegisteredContextSource> sources, List<ContextScope> scopes, List<ContextEntry> entries, List<ContextEntry> replaced,
            List<ContextSourceEvidence> evidence)
    {
        Set<String> retained = retainedSources(sources, scopes, entries, replaced);
        return new RepositoryNotebook(1, current.revision(), useInReviews, useInDevelopment, sources, scopes, entries, replaced,
                evidence.stream().filter(source -> retained.contains(source.id())).collect(toImmutableList()), current.activePreparation());
    }

    /** Replaced versions whose text {@code old} now replaces: one level per note, so older text of the same note is dropped. */
    static List<ContextEntry> keep(RepositoryNotebook current, ContextEntry old)
    {
        return Stream.concat(current.replaced().stream().filter(value -> !value.id().equals(old.id())), Stream.of(old)).collect(toImmutableList());
    }

    static boolean sameText(ContextEntry left, ContextEntry right)
    {
        return left.title().equals(right.title()) && left.summary().equals(right.summary()) && left.body().equals(right.body())
                && left.claims().equals(right.claims()) && left.sourceIds().equals(right.sourceIds());
    }

    static Set<String> retainedSources(List<RegisteredContextSource> sources, List<ContextScope> scopes, List<ContextEntry> entries,
            List<ContextEntry> replaced)
    {
        return Stream.of(entries.stream().flatMap(entry -> entry.sourceIds().stream()),
                        replaced.stream().flatMap(entry -> entry.sourceIds().stream()),
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
            checkArgument(ContextLimits.bytes(json) <= ContextLimits.MAX_STORED_NOTEBOOK_BYTES, "Serialized notebook exceeds UTF-8 limit");
            settings.put(key(workspaceId), json);
            stamp(workspaceId, current, replacement, current.activePreparation().map(ActiveContextPreparation::ownerTaskId));
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

    private Workspace requireWorkspace(String workspaceId)
    {
        checkText(workspaceId, "workspaceId", 200);
        return workspaces.findById(workspaceId).orElseThrow(() -> new ContextNotFoundException("Workspace not found"));
    }

    private RepositoryNotebook decode(String json)
    {
        try {
            checkArgument(ContextLimits.bytes(json) <= ContextLimits.MAX_STORED_NOTEBOOK_BYTES, "Saved notebook exceeds UTF-8 limit");
            JsonNode parsed = mapper.readTree(json);
            checkArgument(parsed instanceof ObjectNode, "Saved notebook must be an object");
            ObjectNode saved = (ObjectNode) parsed;
            if (!saved.has("useInDevelopment")) {
                saved.put("useInDevelopment", true);
            }
            if (!saved.has("replaced")) {
                saved.putArray("replaced");
            }
            // Earlier builds kept notes awaiting approval; agent notes now save directly, so those drafts are dropped.
            saved.remove("pendingDrafts");
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
