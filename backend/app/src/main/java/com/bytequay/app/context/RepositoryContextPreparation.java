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
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ActiveContextPreparation;
import com.bytequay.core.context.ContextClaim;
import com.bytequay.core.context.ContextDocumentRead;
import com.bytequay.core.context.ContextEntry;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextHistoryRead;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextKnowledgeDraft;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextPacket;
import com.bytequay.core.context.ContextPreparationResult;
import com.bytequay.core.context.ContextPreparationResult.Outcome;
import com.bytequay.core.context.ContextRepositoryRead;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.ContextSourceRead;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.bytequay.app.context.RepositoryContextReader.repositoryId;
import static com.bytequay.core.context.ContextEntryKind.KNOWLEDGE;
import static com.bytequay.core.context.ContextEntryOrigin.GENERATED;
import static com.bytequay.core.context.ContextEntryOrigin.USER;
import static com.bytequay.core.context.ContextPreparationResult.Outcome.EMPTY;
import static com.bytequay.core.context.ContextPreparationResult.Outcome.FAILED;
import static com.bytequay.core.context.ContextPreparationResult.Outcome.READY;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

/** Shared extraction for reviews and explicit learning tasks. Callers own provider authorization and atomic operation receipts. */
@Service
public class RepositoryContextPreparation
{
    private final RepositoryContextService notebooks;
    private final ContextInputReader inputs;
    private final WorkspaceRepositoryService workspaces;

    public RepositoryContextPreparation(RepositoryContextService notebooks, ContextInputReader inputs, WorkspaceRepositoryService workspaces)
    {
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.inputs = requireNonNull(inputs, "inputs is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    /**
     * Captures existing scope inputs at the run's immutable monitored baseline, outside every mutation lock and transaction.
     * Missing/incomplete observations remain gaps; this performs no model call or documentation fetch.
     *
     * @param execution trusted active investigation identity, never deserialized from tool arguments
     * @param scopeIds existing configured scope identities selected by the caller
     * @return notebook revision and observed input inventories used for admission
     */
    public Plan plan(Execution execution, List<String> scopeIds)
    {
        return plan(execution, scopeIds, ImmutableList.of());
    }

    /**
     * Adds explicitly selected history observations to preparation inputs. Read IDs must belong to this frozen run;
     * equal source content reuses knowledge, while a changed observation invalidates the selected scopes.
     * No history fetch occurs during admission. Omitted history IDs retain existing observed inputs without refreshing them.
     *
     * @param execution trusted active investigation identity
     * @param scopeIds configured scopes to prepare
     * @param historyReadIds at most ten captured merged-PR or closed-issue read IDs, selected before BEGIN
     * @return bounded input inventories including the selected historical observations
     * @throws IllegalArgumentException when a read is missing, is not history, duplicates a source, or belongs to another repository
     */
    public Plan plan(Execution execution, List<String> scopeIds, List<String> historyReadIds)
    {
        ContextLimits.unique(scopeIds, "preparation scopes", 100);
        ContextLimits.unique(historyReadIds, "selected history reads", 10);
        checkArgument(!scopeIds.isEmpty(), "Select at least one preparation scope");
        RepositoryNotebook notebook = notebooks.getNotebook(execution.workspaceId());
        List<ContextScope> scopes = scopeIds.stream().map(id -> scope(notebook, id)).collect(toImmutableList());
        WorkspaceRepositoryContext workspace = workspace(execution);
        List<ContextHistoryRead> history = historyReadIds.stream().map(id -> execution.sourceReads().stream()
                .filter(read -> read.id().equals(id)).findFirst().filter(ContextHistoryRead.class::isInstance).map(ContextHistoryRead.class::cast)
                .orElseThrow(() -> new IllegalArgumentException("Unknown captured history read: " + id))).collect(toImmutableList());
        ContextLimits.unique(history.stream().map(ContextHistoryRead::sourceKey).toList(), "selected history sources", 10);
        history.forEach(read -> checkArgument(read.repositoryId() == execution.repositoryId()
                && read.repository().fullName().equalsIgnoreCase(execution.repository()), "History source belongs to another repository"));
        List<ContextInputFingerprint> captured = inputs.capture(workspace, Optional.of(execution.baselineSha()), notebook, scopes);
        return new Plan(notebook, captured.stream().map(input -> {
            Map<String, String> selected = new LinkedHashMap<>(input.inputs());
            history.forEach(read -> selected.put(read.sourceKey(), ContextInputReader.historyIdentity(read)));
            if (selected.size() > 128 || selected.entrySet().stream().mapToInt(entry -> ContextLimits.bytes(entry.getKey())
                    + ContextLimits.bytes(entry.getValue())).sum() > ContextInputFingerprint.MAXIMUM_INPUT_BYTES) {
                List<String> gaps = ImmutableList.<String>builder().addAll(new TopKSelector(9).select(input.gaps()))
                        .add("Selected history exceeds the per-scope input budget; select fewer sources or a narrower scope.").build();
                return ContextInputFingerprint.create(input.scopeId(), input.inputs(), gaps);
            }
            return ContextInputFingerprint.create(input.scopeId(), selected, input.gaps());
        }).collect(toImmutableList()));
    }

    /**
     * Admits one owner, reuses completed equal inputs, or immediately reports another owner. The caller revalidates
     * accepted-turn authority inside its workspace lock/SQLite transaction and journals an accepted result there.
     * Metadata alone does not increment the notebook content revision. Exact proposed identities are checked for
     * user ownership, disabled content and removal suppression when a report is submitted.
     */
    public Admission begin(Execution execution, String requestKey, String requestDigest, long expectedRevision, Plan plan)
    {
        return begin(execution, requestKey, requestDigest, expectedRevision, plan, true);
    }

    /** An explicitly approved new learning question must not reuse a different question's scope result. */
    public Admission begin(Execution execution, String requestKey, String requestDigest, long expectedRevision, Plan plan, boolean reuseCompleted)
    {
        return WorkspaceOperationLocks.withLock(execution.workspaceId(), () -> {
            RepositoryNotebook current = notebooks.getNotebook(execution.workspaceId());
            Optional<ActiveContextPreparation> active = current.activePreparation();
            if (active.filter(value -> owner(value, execution) && value.requestKey().equals(requestKey)).isPresent()) {
                ActiveContextPreparation previous = active.orElseThrow();
                checkArgument(previous.requestDigest().equals(requestDigest), "Preparation request key was reused with different content");
                return new Admission(Status.OWNED, current.revision(), active, ImmutableList.of(), ImmutableList.of(), packet(current, ImmutableList.of()));
            }
            if (current.revision() != expectedRevision || plan.notebook().revision() != current.revision()
                    || !current.scopes().equals(plan.notebook().scopes()) || !current.sourceEvidence().equals(plan.notebook().sourceEvidence())) {
                throw new ContextConflictException(current.revision());
            }
            // Completed execution metadata can change without a content revision. Reuse is always decided from the latest notebook.
            ImmutableList.Builder<ContextPreparationResult> reused = ImmutableList.builder();
            ImmutableList.Builder<ContextInputFingerprint> needed = ImmutableList.builder();
            ImmutableList.Builder<String> gaps = ImmutableList.builder();
            for (ContextInputFingerprint input : plan.inputs()) {
                ContextScope scope = scope(current, input.scopeId());
                if (!scope.enabled()) {
                    gaps.add(scope.id() + ": scope is disabled.");
                }
                else if (input.digest().isEmpty()) {
                    gaps.add(scope.id() + ": input observation is incomplete; no generation owner was admitted.");
                }
                else if (reuseCompleted && reusable(current, scope, input, execution)) {
                    reused.add(scope.preparation().orElseThrow());
                }
                else {
                    needed.add(input);
                }
            }
            List<ContextInputFingerprint> missing = needed.build();
            List<ContextPreparationResult> saved = reused.build();
            if (missing.isEmpty()) {
                List<String> limitations = gaps.build();
                return new Admission(limitations.isEmpty() ? Status.REUSED : Status.DEFERRED, current.revision(), Optional.empty(), saved, limitations, packet(current, saved));
            }
            if (active.isPresent()) {
                ActiveContextPreparation previous = active.orElseThrow();
                boolean matching = previous.baseContentRevision() == current.revision()
                        && missing.stream().allMatch(input -> previous.inputs().contains(input));
                gaps.add(matching ? "Matching knowledge is still being prepared by the owning task; retry later."
                        : "Another preparation owns this workspace; these inputs are deferred until it settles.");
                return new Admission(matching ? Status.JOINED : Status.DEFERRED, current.revision(), active, saved, gaps.build(), packet(current, saved));
            }
            ActiveContextPreparation admitted = new ActiveContextPreparation(UUID.randomUUID().toString(), requestKey, requestDigest,
                    execution.taskId(), execution.turnId(), execution.turnKey(), execution.reviewId(), execution.runId(), current.revision(), missing);
            RepositoryNotebook changed = copy(current, current.revision(), current.scopes(), current.entries(), current.pendingDrafts(),
                    current.sourceEvidence(), Optional.of(admitted));
            notebooks.savePreparation(execution.workspaceId(), current, changed);
            return new Admission(Status.OWNED, current.revision(), Optional.of(admitted), saved, gaps.build(), packet(current, saved));
        });
    }

    /**
     * Validates a proposed batch against retained baseline, document and selected history receipts and prepares its exact accepted text.
     * Re-captures input identities against the prospective sources, including successful blank reads for EMPTY,
     * so saving new source links does not immediately invalidate a new note. Run outside all mutation locks/transactions.
     * This cannot certify the semantic truth or completeness of the model's conclusions.
     */
    public Completion prepareCompletion(Execution execution, String attemptId, GeneratedReport report)
    {
        RepositoryNotebook current = notebooks.getNotebook(execution.workspaceId());
        ActiveContextPreparation active = owned(current, execution, attemptId);
        Set<String> expected = active.inputs().stream().map(ContextInputFingerprint::scopeId).collect(ImmutableSet.toImmutableSet());
        checkArgument(report.scopeResults().stream().map(ScopeReport::scopeId).collect(ImmutableSet.toImmutableSet()).equals(expected),
                "Report must cover every admitted scope exactly once");
        Map<String, GeneratedEntry> proposals = report.entries().stream().collect(toImmutableMap(GeneratedEntry::id, value -> value));
        Set<String> readyIds = report.scopeResults().stream().filter(result -> result.outcome() == READY).flatMap(result -> result.noteIds().stream())
                .collect(ImmutableSet.toImmutableSet());
        checkArgument(proposals.keySet().equals(readyIds), "Entries must exactly match the ready scope results");
        Map<String, ContextSourceRead> observed = execution.sourceReads().stream().collect(toImmutableMap(ContextSourceRead::id, value -> value));
        Map<String, ContextSourceEvidence> evidence = new LinkedHashMap<>();
        current.sourceEvidence().forEach(source -> evidence.put(source.id(), source));
        List<ContextKnowledgeDraft> drafts = new ArrayList<>(current.pendingDrafts());
        Map<String, ContextPreparationResult> results = new LinkedHashMap<>();
        long revision = readyIds.isEmpty() ? current.revision() : Math.addExact(current.revision(), 1);
        for (ScopeReport result : report.scopeResults()) {
            ContextScope scope = scope(current, result.scopeId());
            checkArgument(scope.enabled(), "Scope is disabled");
            List<ContextSourceRead> reads = result.checkedSourceIds().stream().map(id -> {
                ContextSourceRead read = Optional.ofNullable(observed.get(id)).orElseThrow(() -> new IllegalArgumentException("Unknown captured source read: " + id));
                return retain(execution, current, read, evidence);
            }).collect(toImmutableList());
            ContextInputFingerprint initial = active.inputs().stream().filter(input -> input.scopeId().equals(scope.id())).findFirst().orElseThrow();
            reads.stream().filter(ContextHistoryRead.class::isInstance).map(ContextHistoryRead.class::cast).forEach(read ->
                    checkArgument(ContextInputReader.historyIdentity(read).equals(initial.inputs().get(read.sourceKey())),
                            "History must be selected before preparation begins with the same observed content"));
            if (result.outcome() == READY && reads.stream().anyMatch(ContextHistoryRead.class::isInstance)) {
                checkArgument(reads.stream().anyMatch(read -> read instanceof ContextRepositoryRead && read.evidenceRef().isPresent()),
                        "History-based knowledge requires captured nonblank monitored-baseline evidence");
            }
            ContextPreparationResult completed = new ContextPreparationResult(active.attemptId(), initial, result.outcome(), result.noteIds(), reads, result.explanation());
            results.put(scope.id(), completed);
            if (result.outcome() == READY) {
                Set<String> captured = reads.stream().flatMap(read -> read.evidenceRef().stream()).collect(ImmutableSet.toImmutableSet());
                for (String id : result.noteIds()) {
                    GeneratedEntry entry = proposals.get(id);
                    checkArgument(entry.scopeId().equals(scope.id()), "Generated entry belongs to another scope");
                    checkArgument(current.pendingDrafts().stream().noneMatch(draft -> draft.entry().id().equals(id)),
                            "Knowledge note already has a pending proposal");
                    Optional<ContextEntry> prior = current.entries().stream().filter(note -> note.id().equals(id)).findFirst();
                    checkArgument(prior.filter(note -> !note.scopeId().equals(scope.id()) || note.kind() != entry.kind()).isEmpty(),
                            "Generated note cannot change an existing note's scope");
                    checkArgument(prior.filter(note -> !note.enabled() || note.origin() == USER).isEmpty(),
                            "Generated note cannot replace user-owned or disabled content");
                    checkArgument(captured.containsAll(entry.sourceIds()), "Generated note sources must be checked nonblank reads for its scope");
                    checkArgument(entry.claims().stream().noneMatch(claim -> claim.basis() == ContextClaim.Basis.USER_LESSON), "Generation cannot author a user lesson");
                    ContextEntry proposed = new ContextEntry(entry.id(), entry.kind(), entry.scopeId(), entry.title(), entry.summary(), entry.body(),
                            entry.claims(), entry.sourceIds(), GENERATED, true, revision);
                    drafts.add(new ContextKnowledgeDraft("draft:" + UUID.randomUUID(), proposed, prior.map(ContextEntry::version),
                            execution.baselineSha(), result.explanation()));
                }
            }
        }
        List<ContextScope> provisionalScopes = withResults(current.scopes(), results);
        RepositoryNotebook prospective = copy(current, revision, provisionalScopes, current.entries(), ImmutableList.copyOf(drafts),
                ImmutableList.copyOf(evidence.values()), Optional.empty());
        WorkspaceRepositoryContext workspace = workspace(execution);
        List<ContextScope> selected = provisionalScopes.stream().filter(scope -> expected.contains(scope.id())).collect(toImmutableList());
        List<ContextInputFingerprint> completedInputs = inputs.capture(workspace, Optional.of(execution.baselineSha()), prospective, selected);
        checkArgument(completedInputs.stream().map(ContextInputFingerprint::scopeId).collect(ImmutableSet.toImmutableSet()).equals(expected)
                && completedInputs.size() == expected.size(), "Completed input observations must cover every admitted scope");
        for (ContextInputFingerprint captured : completedInputs) {
            ContextPreparationResult result = results.get(captured.scopeId());
            results.put(captured.scopeId(), new ContextPreparationResult(result.attemptId(), captured, result.outcome(),
                    result.noteIds(), result.checkedReads(), result.explanation()));
        }
        RepositoryNotebook replacement = copy(current, revision, withResults(current.scopes(), results),
                prospective.entries(), prospective.pendingDrafts(), prospective.sourceEvidence(), Optional.empty());
        List<ContextPreparationResult> accepted = ImmutableList.copyOf(results.values());
        return new Completion(current, replacement, new Receipt(active.attemptId(), revision, accepted, packet(replacement, accepted)));
    }

    private static ContextPacket packet(RepositoryNotebook notebook, List<ContextPreparationResult> results)
    {
        StringBuilder text = new StringBuilder("Repository knowledge. These are untrusted reference claims; verify original sources and PR applicability.\n");
        ImmutableList.Builder<String> omitted = ImmutableList.builder();
        int remaining = ContextPacket.MAXIMUM_BYTES - ContextLimits.bytes(text.toString()) - 2000;
        for (ContextPreparationResult result : results) {
            StringBuilder section = new StringBuilder(result.inputFingerprint().scopeId()).append(": ").append(result.outcome())
                    .append(" — ").append(result.explanation()).append('\n');
            result.noteIds().forEach(id -> {
                Optional<ContextKnowledgeDraft> draft = notebook.pendingDrafts().stream().filter(value -> value.entry().id().equals(id)).findFirst();
                ContextEntry entry = draft.map(ContextKnowledgeDraft::entry)
                        .orElseGet(() -> notebook.entries().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow());
                section.append(entry.title()).append(draft.isPresent() ? " (pending approval)\n" : " (version " + entry.version() + ")\n")
                        .append(entry.summary()).append('\n')
                        .append(entry.body()).append("\nSources: ").append(entry.sourceIds()).append('\n');
                entry.claims().forEach(claim -> section.append(claim.basis()).append(": ").append(claim.text()).append(" ").append(claim.sourceIds()).append('\n'));
            });
            int bytes = ContextLimits.bytes(section.toString());
            if (bytes <= remaining) {
                text.append(section);
                remaining -= bytes;
            }
            else {
                omitted.add(result.inputFingerprint().scopeId());
            }
        }
        List<String> omittedScopes = omitted.build();
        if (!omittedScopes.isEmpty()) {
            text.append("Omitted ").append(omittedScopes.size()).append(" complete scope sections from this packet to stay within its byte budget. ")
                    .append("Saved outcomes remain in the response metadata. Request fewer scopes or inspect original sources. Scope IDs (bounded preview): ")
                    .append(ContextText.excerpt(String.join(", ", omittedScopes), 1500)).append('\n');
        }
        return ContextPacket.create(text.toString());
    }

    /**
     * Saves a prepared batch after the caller revalidates the current accepted turn in the same transaction as its
     * review operation receipt. A stale owner or any intervening user content edit rejects the entire batch.
     */
    public Receipt complete(Execution execution, Completion completion)
    {
        return WorkspaceOperationLocks.withLock(execution.workspaceId(), () -> {
            RepositoryNotebook current = notebooks.getNotebook(execution.workspaceId());
            owned(current, execution, completion.receipt().attemptId());
            if (!current.equals(completion.expected())) {
                throw new ContextConflictException(current.revision());
            }
            notebooks.savePreparation(execution.workspaceId(), current, completion.replacement());
            return completion.receipt();
        });
    }

    /** Records an exact settled owner as failed, preserving content and never admitting or starting replacement work. */
    public void fail(String workspaceId, ActiveContextPreparation expected, String reason)
    {
        ContextLimits.text(reason, "preparation failure", 2000);
        WorkspaceOperationLocks.withLock(workspaceId, () -> {
            RepositoryNotebook current = notebooks.getNotebook(workspaceId);
            if (!current.activePreparation().equals(Optional.of(expected))) {
                return null;
            }
            Map<String, ContextPreparationResult> results = expected.inputs().stream().collect(toImmutableMap(ContextInputFingerprint::scopeId,
                    input -> new ContextPreparationResult(expected.attemptId(), input, FAILED, ImmutableList.of(), ImmutableList.of(), reason)));
            notebooks.savePreparation(workspaceId, current, copy(current, current.revision(), withResults(current.scopes(), results),
                    current.entries(), current.pendingDrafts(), current.sourceEvidence(), Optional.empty()));
            return null;
        });
    }

    private WorkspaceRepositoryContext workspace(Execution execution)
    {
        WorkspaceRepositoryContext workspace = workspaces.requireReadableRepository(execution.workspaceId());
        checkArgument(workspace.workspaceId().equals(execution.workspaceId()) && repositoryId(workspace) == execution.repositoryId()
                && workspace.monitoredRepository().fullName().equalsIgnoreCase(execution.repository()), "Preparation repository routing changed");
        return workspace;
    }

    private static ActiveContextPreparation owned(RepositoryNotebook notebook, Execution execution, String attemptId)
    {
        ActiveContextPreparation active = notebook.activePreparation().filter(value -> value.attemptId().equals(attemptId) && owner(value, execution))
                .orElseThrow(() -> new IllegalArgumentException("Preparation is not owned by this accepted turn"));
        if (notebook.revision() != active.baseContentRevision()) {
            throw new ContextConflictException(notebook.revision());
        }
        return active;
    }

    private static boolean owner(ActiveContextPreparation active, Execution execution)
    {
        return active.reviewId().equals(execution.reviewId()) && active.runId().equals(execution.runId())
                && active.ownerTaskId().equals(execution.taskId()) && active.ownerTurnId().equals(execution.turnId())
                && active.ownerTurnKey().equals(execution.turnKey());
    }

    private static boolean reusable(RepositoryNotebook notebook, ContextScope scope, ContextInputFingerprint input, Execution execution)
    {
        return scope.preparation().filter(result -> result.inputFingerprint().equals(input))
                .filter(result -> execution.sourceReads().stream().filter(ContextDocumentRead.class::isInstance).map(ContextDocumentRead.class::cast)
                        .allMatch(fresh -> result.checkedReads().stream().filter(ContextDocumentRead.class::isInstance).map(ContextDocumentRead.class::cast)
                                .anyMatch(saved -> fresh.reference().equals(saved.reference()) && fresh.registration().equals(saved.registration())
                                        && fresh.contentHash().equals(saved.contentHash()) && fresh.truncated() == saved.truncated()
                                        && fresh.refreshFailed() == saved.refreshFailed())))
                .filter(result -> result.outcome() == EMPTY || (result.outcome() == READY && result.noteIds().stream()
                        .allMatch(id -> notebook.entries().stream().anyMatch(entry -> entry.id().equals(id) && entry.enabled())
                                && notebook.pendingDrafts().stream().noneMatch(draft -> draft.entry().id().equals(id))))).isPresent();
    }

    private static ContextScope scope(RepositoryNotebook notebook, String id)
    {
        return notebook.scopes().stream().filter(scope -> scope.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown preparation scope: " + id));
    }

    private static List<ContextScope> withResults(List<ContextScope> scopes, Map<String, ContextPreparationResult> results)
    {
        return scopes.stream().map(scope -> new ContextScope(scope.id(), scope.title(), scope.match(), scope.enabled(),
                Optional.ofNullable(results.get(scope.id())).or(scope::preparation), scope.discoveryEvidenceRef())).collect(toImmutableList());
    }

    private static RepositoryNotebook copy(RepositoryNotebook current, long revision, List<ContextScope> scopes, List<ContextEntry> entries,
            List<ContextKnowledgeDraft> drafts, List<ContextSourceEvidence> evidence, Optional<ActiveContextPreparation> active)
    {
        Set<String> retained = RepositoryContextService.retainedSources(current.sources(), scopes, entries, drafts);
        return new RepositoryNotebook(1, revision, current.useInReviews(), current.useInDevelopment(), current.sources(), scopes, entries, drafts,
                evidence.stream().filter(source -> retained.contains(source.id())).collect(toImmutableList()), active);
    }

    private static ContextSourceRead retain(Execution execution, RepositoryNotebook notebook, ContextSourceRead read, Map<String, ContextSourceEvidence> evidence)
    {
        Optional<String> retainedRef = read.evidenceRef().map(ignored -> read.id());
        ContextSourceRead retained = switch (read) {
            case ContextRepositoryRead file -> {
                checkArgument(file.reference().repositoryId() == execution.repositoryId()
                        && file.reference().repository().fullName().equalsIgnoreCase(execution.repository())
                        && execution.baselineSha().equals(file.reference().commit()), "Only captured monitored-baseline files may become shared knowledge");
                yield new ContextRepositoryRead(file.id(), file.reference(), file.blobId(), file.readAt(), file.startLine(), file.endLine(), file.totalLines(),
                        file.utf8Bytes(), file.truncated(), retainedRef);
            }
            case ContextHistoryRead history -> {
                checkArgument(history.repositoryId() == execution.repositoryId()
                        && history.repository().fullName().equalsIgnoreCase(execution.repository()), "History source belongs to another repository");
                yield new ContextHistoryRead(history.id(), history.reference(), history.state(), history.sourceUpdatedAt(), history.sourceRevision(),
                        history.contentHash(), history.readAt(), history.utf8Bytes(), history.truncated(), history.refreshFailed(), history.gaps(), retainedRef);
            }
            case ContextDocumentRead document -> {
                checkArgument(notebook.sources().contains(document.registration()), "Captured document registration changed");
                yield new ContextDocumentRead(document.id(), document.reference(), document.registration(), document.notebookRevision(), document.captureId(),
                        document.contentHash(), document.readAt(), document.page(), document.totalPages(), document.utf8Bytes(), document.blank(),
                        document.truncated(), document.refreshFailed(), retainedRef);
            }
        };
        read.evidenceRef().ifPresent(ref -> {
            CapturedSource source = Optional.ofNullable(execution.evidence().get(ref)).orElseThrow(() -> new IllegalArgumentException("Captured source evidence is missing"));
            ContextSourceEvidence captured = switch (read) {
                case ContextRepositoryRead file -> new ContextSourceEvidence(file.id(), file.reference(), source.provenance(), source.excerpt(),
                        ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(file.readAt()), Optional.of(file.blobId()), Optional.empty(), file.truncated());
                case ContextHistoryRead history -> new ContextSourceEvidence(history.id(), history.reference(), source.provenance(), source.excerpt(),
                        ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(history.readAt()), Optional.of(history.contentHash()),
                        history.sourceRevision(), history.truncated());
                case ContextDocumentRead document -> new ContextSourceEvidence(document.id(), document.reference(), source.provenance(), source.excerpt(),
                        ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(document.readAt()), Optional.of(document.contentHash()), Optional.empty(), document.truncated());
            };
            ContextSourceEvidence previous = evidence.putIfAbsent(captured.id(), captured);
            checkArgument(previous == null || previous.equals(captured), "Captured source identity was reused with different content");
        });
        return retained;
    }

    /** Trusted application execution shared by review and explicit learning owners, never deserialized from tool arguments. */
    public record Execution(
            String workspaceId, String repository, long repositoryId, String baselineSha, String taskId, String turnId, String turnKey,
            Optional<String> reviewId, Optional<String> runId, List<ContextSourceRead> sourceReads, Map<String, CapturedSource> evidence)
    {
        public Execution
        {
            ContextLimits.text(workspaceId, "workspace id", 200);
            ContextLimits.text(repository, "repository", 500);
            checkArgument(repositoryId > 0, "Invalid preparation repository id");
            checkArgument(baselineSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid monitored baseline");
            ContextLimits.text(taskId, "task id", 200);
            ContextLimits.text(turnId, "turn id", 200);
            ContextLimits.text(turnKey, "turn key", 500);
            requireNonNull(reviewId, "reviewId is null");
            requireNonNull(runId, "runId is null");
            checkArgument(reviewId.isPresent() == runId.isPresent(), "Review and run identities must be paired");
            sourceReads = ImmutableList.copyOf(sourceReads);
            evidence = ImmutableMap.copyOf(evidence);
            ContextLimits.unique(sourceReads.stream().map(ContextSourceRead::id).toList(), "captured source reads", ContextLimits.MAX_SOURCE_READS);
            for (ContextSourceRead read : sourceReads) {
                if (read.evidenceRef().isPresent()) {
                    CapturedSource source = evidence.get(read.evidenceRef().orElseThrow());
                    checkArgument(source != null && ContextLimits.bytes(source.excerpt()) == read.utf8Bytes(), "Captured source evidence differs from receipt");
                    if (read instanceof ContextHistoryRead history) {
                        history.checkExcerpt(source.excerpt());
                    }
                }
            }
        }

        /** Resolves the existing review's captured baseline and sources without manufacturing a review for standalone work. */
        public Execution(AgentReview review, ReviewRun run, String turnId, String turnKey)
        {
            this(review.workspaceId(), review.repo(), context(review, run).repositoryId(), context(review, run).baselineSha().orElseThrow(),
                    review.taskId(), turnId, turnKey, Optional.of(review.id()), Optional.of(run.id()), context(review, run).sourceReads(),
                    run.evidence().entrySet().stream()
                            .filter(entry -> context(review, run).sourceReads().stream().anyMatch(read -> read.evidenceRef().equals(Optional.of(entry.getKey()))))
                            .collect(toImmutableMap(Map.Entry::getKey,
                                    entry -> new CapturedSource(entry.getValue().provenance(), entry.getValue().excerpt()))));
        }

        private static ReviewContextSnapshot context(AgentReview review, ReviewRun run)
        {
            checkArgument(review.id().equals(run.reviewId()), "Preparation run belongs to another review");
            ReviewContextSnapshot snapshot = run.context().orElseThrow(() -> new IllegalArgumentException("Run has no captured repository context"));
            checkArgument(snapshot.enabled() && snapshot.baselineSha().isPresent(), "Preparation requires enabled context and a captured baseline");
            return snapshot;
        }
    }

    public record CapturedSource(String provenance, String excerpt)
    {
        public CapturedSource
        {
            ContextLimits.text(provenance, "source provenance", 4000);
            ContextLimits.text(excerpt, "source excerpt", 16 * 1024);
        }
    }

    public record Plan(RepositoryNotebook notebook, List<ContextInputFingerprint> inputs)
    {
        public Plan
        {
            requireNonNull(notebook, "notebook is null");
            inputs = ImmutableList.copyOf(inputs);
        }
    }

    public enum Status { OWNED, JOINED, REUSED, DEFERRED }

    public record Admission(Status status, long notebookRevision, Optional<ActiveContextPreparation> attempt,
            List<ContextPreparationResult> reused, List<String> gaps, ContextPacket packet)
    {
        public Admission
        {
            reused = ImmutableList.copyOf(reused);
            gaps = ImmutableList.copyOf(gaps);
        }
    }

    public record Completion(RepositoryNotebook expected, RepositoryNotebook replacement, Receipt receipt) {}

    public record Receipt(String attemptId, long notebookRevision, List<ContextPreparationResult> scopeResults, ContextPacket packet)
    {
        public Receipt
        {
            scopeResults = ImmutableList.copyOf(scopeResults);
        }
    }

    public record GeneratedReport(List<ScopeReport> scopeResults, List<GeneratedEntry> entries)
    {
        public GeneratedReport
        {
            scopeResults = ImmutableList.copyOf(scopeResults);
            entries = ImmutableList.copyOf(entries);
            ContextLimits.unique(scopeResults.stream().map(ScopeReport::scopeId).toList(), "reported scopes", 100);
            ContextLimits.unique(entries.stream().map(GeneratedEntry::id).toList(), "reported entries", 100);
            checkArgument(!scopeResults.isEmpty(), "Report needs an outcome for every admitted scope");
        }
    }

    public record ScopeReport(String scopeId, Outcome outcome, List<String> noteIds, List<String> checkedSourceIds, String explanation)
    {
        public ScopeReport
        {
            ContextLimits.text(scopeId, "reported scope", 1100);
            requireNonNull(outcome, "outcome is null");
            noteIds = ImmutableList.copyOf(noteIds);
            checkedSourceIds = ImmutableList.copyOf(checkedSourceIds);
            ContextLimits.unique(noteIds, "reported note ids", ContextLimits.MAX_ENTRIES);
            ContextLimits.unique(checkedSourceIds, "checked source ids", ContextLimits.MAX_SOURCE_READS);
            checkedSourceIds.forEach(id -> ContextLimits.text(id, "checked source id", 100));
            ContextLimits.text(explanation, "preparation explanation", 2000);
        }
    }

    public record GeneratedEntry(String id, String scopeId, String title, String summary, String body, List<ContextClaim> claims,
            List<String> sourceIds, ContextEntryKind kind)
    {
        public GeneratedEntry(String id, String scopeId, String title, String summary, String body, List<ContextClaim> claims, List<String> sourceIds)
        {
            this(id, scopeId, title, summary, body, claims, sourceIds, KNOWLEDGE);
        }

        public GeneratedEntry
        {
            kind = kind == null ? KNOWLEDGE : kind;
            checkArgument(kind != ContextEntryKind.MEMORY || (id.startsWith("lesson:" + scopeId + ":") && id.length() > scopeId.length() + 8),
                    "Generated lesson ID must belong to its scope");
            claims = ImmutableList.copyOf(claims);
            sourceIds = ImmutableList.copyOf(sourceIds);
            // Reuse the actual saved-entry constraints before any Git work or notebook mutation.
            new ContextEntry(id, kind, scopeId, title, summary, body, claims, sourceIds, GENERATED, true, 1);
        }
    }
}
