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
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextEntryOrigin;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextMatch;
import com.bytequay.core.context.ContextReference;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.context.RepositoryNotebook;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/** Accepts one discovery proposal and runs one saved learning question per explicit task. */
@Service
public class ContextLearningService
{
    private final ContextDiscoveryReportStore reports;
    private final ContextLearningPlanStore plans;
    private final ContextBuildStore buildStore;
    private final ContextBuildService builds;
    private final RepositoryContextService notebooks;
    private final WorkspaceRepositoryService workspaces;
    private final GitCommandRunner git;
    private final AgentUnitOfWork unitOfWork;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ContextLearningService(ContextDiscoveryReportStore reports, ContextLearningPlanStore plans, ContextBuildStore buildStore,
            ContextBuildService builds, RepositoryContextService notebooks, WorkspaceRepositoryService workspaces, GitCommandRunner git,
            AgentUnitOfWork unitOfWork, ObjectMapper mapper, Clock clock)
    {
        this.reports = requireNonNull(reports, "reports is null");
        this.plans = requireNonNull(plans, "plans is null");
        this.buildStore = requireNonNull(buildStore, "buildStore is null");
        this.builds = requireNonNull(builds, "builds is null");
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public Optional<ContextLearningPlan> get(String workspaceId)
    {
        notebooks.getNotebook(workspaceId);
        return plans.find(workspaceId);
    }

    public ContextBuildService.View start(String workspaceId, StartRequest request)
    {
        String digest = digest(request);
        Optional<ContextLearningPlan> existing = get(workspaceId);
        if (existing.filter(plan -> plan.acceptedRequestKey().equals(request.requestKey())).isPresent()) {
            ContextLearningPlan replay = existing.orElseThrow();
            checkArgument(replay.acceptedRequestDigest().equals(digest), "Learning request key was reused with different topics");
            return launch(replay, request.requestKey(), request.expectedBuildSequence(), replay.acceptedNotebookRevision(), true);
        }
        checkArgument(existing.map(plan -> plan.topics().stream().allMatch(topic -> topic.status() == ContextLearningPlan.Status.COMPLETED))
                        .orElse(true), "Finish the current learning plan before selecting another batch");
        ContextDiscoveryReport report = reports.find(workspaceId).filter(value -> value.id().equals(request.reportId()))
                .orElseThrow(() -> new IllegalArgumentException("Discovery report changed; inspect the latest suggestions"));
        List<ContextDiscoveryReport.Topic> selected = request.topicIds().stream().map(id -> report.topics().stream()
                .filter(topic -> topic.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Selected discovery topic is unavailable: " + id))).toList();
        RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
        checkArgument(notebook.revision() == request.expectedRevision(), "Notebook revision changed; inspect current context");
        ContextBuildService.View before = builds.get(workspaceId);
        checkArgument(before.buildRequestSequence() == request.expectedBuildSequence(), "Build request sequence changed");
        checkArgument(before.canStart(), "Finish and clean up the current learning task before accepting another plan");
        checkAcceptedRepository(workspaceId, report);
        ContextLearningPlan plan = WorkspaceOperationLocks.withLock(workspaceId, () -> unitOfWork.execute(() -> {
            checkArgument(reports.find(workspaceId).filter(report::equals).isPresent(), "Discovery report changed during acceptance");
            checkArgument(plans.find(workspaceId).equals(existing), "Learning plan changed during acceptance");
            checkArgument(buildStore.find(workspaceId).map(ContextBuild::sequence).orElse(0L) == request.expectedBuildSequence(),
                    "Build request sequence changed during acceptance");
            checkArgument(notebooks.getNotebook(workspaceId).equals(notebook), "Notebook changed during learning acceptance");
            checkAcceptedRepository(workspaceId, report);
            Prepared prepared = prepare(report, selected, notebook);
            RepositoryNotebook updated = prepared.scopes().isEmpty() && prepared.sources().isEmpty()
                    ? notebook : notebooks.registerDiscovered(workspaceId, notebook.revision(),
                            prepared.scopes(), prepared.sources(), prepared.evidence());
            ContextLearningPlan accepted = new ContextLearningPlan(UUID.randomUUID().toString(), workspaceId, report.id(),
                    request.requestKey(), digest, updated.revision(), request.expectedBuildSequence(), request.topicIds(),
                    report.repositoryId(), report.repository(), report.baselineSha(), prepared.topics(),
                    prepared.documentation(), ContextDocumentInventory.initial(prepared.documentation()),
                    report.gaps(), Instant.now(clock));
            return plans.save(existing, accepted);
        }));
        return launch(plan, request.requestKey(), request.expectedBuildSequence(), plan.acceptedNotebookRevision(), true);
    }

    public ContextBuildService.View continueLearning(String workspaceId, ContinueRequest request)
    {
        ContextLearningPlan plan = get(workspaceId).filter(value -> value.id().equals(request.planId()))
                .orElseThrow(() -> new IllegalArgumentException("Learning plan changed; inspect current progress"));
        return launch(plan, request.requestKey(), request.expectedBuildSequence(), request.expectedRevision(), false);
    }

    public void discard(String workspaceId, DiscardRequest request)
    {
        ContextLearningPlan plan = get(workspaceId).filter(value -> value.id().equals(request.planId()))
                .orElseThrow(() -> new IllegalArgumentException("Learning plan changed; inspect current progress"));
        checkArgument(notebooks.getNotebook(workspaceId).revision() == request.expectedRevision(),
                "Notebook revision changed; inspect current context");
        ContextBuildService.View build = builds.get(workspaceId);
        checkArgument(build.buildRequestSequence() == request.expectedBuildSequence() && build.canStart(),
                "Finish and clean up the current task before discarding the learning plan");
        WorkspaceOperationLocks.withLock(workspaceId, () -> unitOfWork.execute(() -> {
            checkArgument(notebooks.getNotebook(workspaceId).revision() == request.expectedRevision()
                    && buildStore.find(workspaceId).map(ContextBuild::sequence).orElse(0L) == request.expectedBuildSequence(),
                    "Notebook or task changed while discarding the plan");
            plans.delete(plan);
            return null;
        }));
    }

    /** Explicit source expansion changes only the saved plan; it never starts a model or resets existing link depths. */
    public ContextLearningPlan allowSource(String workspaceId, SourceRequest request)
    {
        ContextBuildService.View state = builds.get(workspaceId);
        checkArgument(state.canStart() && state.buildRequestSequence() == request.expectedBuildSequence(), "Finish the current task before expanding sources");
        return WorkspaceOperationLocks.withLock(workspaceId, () -> unitOfWork.execute(() -> {
            RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
            checkArgument(notebook.revision() == request.expectedRevision(), "Notebook revision changed; inspect registered sources");
            checkArgument(buildStore.find(workspaceId).map(ContextBuild::sequence).orElse(0L) == request.expectedBuildSequence(), "Build request sequence changed");
            ContextLearningPlan plan = plans.find(workspaceId).filter(value -> value.id().equals(request.planId()))
                    .orElseThrow(() -> new IllegalArgumentException("Learning plan changed"));
            checkArgument(notebook.sources().containsAll(plan.documentationSources()),
                    "Approved documentation source changed; discard the old plan before expanding sources");
            ContextLearningPlan.Topic current = plan.topics().stream().filter(topic -> topic.status() == ContextLearningPlan.Status.PENDING).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No remaining learning question"));
            RegisteredContextSource source;
            if (request.sourceId().isPresent()) {
                String selectedId = request.sourceId().orElseThrow();
                source = notebook.sources().stream().filter(value -> value.id().equals(selectedId)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Choose a registered documentation source"));
            }
            else {
                String url = request.suggestedUrl().orElseThrow();
                checkArgument(plan.documentInventory().suggestedExpansionUrls().contains(url),
                        "Choose an exact URL suggested by a recorded document read");
                RegisteredContextSource.Kind kind = url.endsWith("/")
                        ? RegisteredContextSource.Kind.DOCUMENTATION_ROOT : RegisteredContextSource.Kind.DOCUMENTATION_PAGE;
                source = notebook.sources().stream().filter(value -> value.url().equals(url) && value.kind() == kind).findFirst()
                        .orElseGet(() -> new RegisteredContextSource("suggested:" + Hashing.sha256().hashString(url, UTF_8),
                                kind, url, ContextEntryOrigin.USER, Optional.empty()));
                RegisteredContextSource selected = source;
                checkArgument(notebook.sources().stream().noneMatch(value -> value.id().equals(selected.id()) && !value.equals(selected)),
                        "Suggested source identity is already registered differently");
                checkArgument(plan.documentationSources().size() < 20 || plan.documentationSources().contains(source),
                        "Learning plan has too many documentation sources");
                checkArgument(plan.documentInventory().items().size() < ContextDocumentInventory.MAX_ITEMS
                                || plan.documentationSources().contains(source), "Documentation inventory is full");
                checkArgument(current.sourceIds().size() < 20 || current.sourceIds().contains(source.id()),
                        "Learning question has too many documentation sources");
                if (!notebook.sources().contains(source)) {
                    List<EditContextConfiguration.Source> registrations = ImmutableList.<EditContextConfiguration.Source>builder()
                            .addAll(notebook.sources().stream().map(value -> new EditContextConfiguration.Source(value.id(), value.kind(), value.url())).toList())
                            .add(new EditContextConfiguration.Source(source.id(), source.kind(), source.url())).build();
                    notebooks.configure(workspaceId, notebook.revision(),
                            new EditContextConfiguration(Optional.empty(), Optional.of(registrations), Optional.empty()));
                }
            }
            Optional<RegisteredContextSource> existing = plan.documentationSources().stream().filter(value -> value.id().equals(source.id())).findFirst();
            checkArgument(existing.isEmpty() || existing.orElseThrow().equals(source), "This source changed; discard the old plan before accepting a different version");
            List<RegisteredContextSource> sources = existing.isPresent() ? plan.documentationSources()
                    : ImmutableList.<RegisteredContextSource>builder().addAll(plan.documentationSources()).add(source).build();
            List<ContextDocumentInventory.Item> items = existing.isPresent() ? plan.documentInventory().items()
                    : ImmutableList.<ContextDocumentInventory.Item>builder().addAll(plan.documentInventory().items())
                            .addAll(ContextDocumentInventory.initial(ImmutableList.of(source)).items()).build();
            List<ContextLearningPlan.Topic> topics = plan.topics().stream().map(topic -> {
                if (!topic.id().equals(current.id()) || topic.sourceIds().contains(source.id())) {
                    return topic;
                }
                return new ContextLearningPlan.Topic(topic.id(), topic.title(), topic.question(), topic.scopeId(), topic.scopeMatchDigest(), topic.paths(),
                        topic.repositoryFiles(), ImmutableList.<String>builder().addAll(topic.sourceIds()).add(source.id()).build(), topic.status(), topic.remainingQuestion());
            }).toList();
            List<String> suggestions = request.suggestedUrl().isPresent()
                    ? plan.documentInventory().withoutSuggestion(request.suggestedUrl().orElseThrow()).suggestedExpansionUrls()
                    : plan.documentInventory().suggestedExpansionUrls();
            return plans.save(Optional.of(plan), new ContextLearningPlan(plan.id(), plan.workspaceId(), plan.reportId(), plan.acceptedRequestKey(),
                    plan.acceptedRequestDigest(), plan.acceptedNotebookRevision(), plan.initialBuildSequence(), plan.acceptedTopicIds(), plan.repositoryId(),
                    plan.repository(), plan.baselineSha(), topics, sources, new ContextDocumentInventory(sources, items, plan.documentInventory().truncated(), suggestions),
                    plan.gaps(), plan.acceptedAt()));
        }));
    }

    /** Checked by the learning tool against its exact accepted task binding. */
    public ContextLearningPlan require(ContextBuild build)
    {
        ContextBuild.Assignment assignment = build.assignment()
                .orElseThrow(() -> new IllegalArgumentException("Learning task has no accepted topic"));
        ContextLearningPlan plan = plans.find(build.workspaceId()).filter(value -> value.id().equals(assignment.planId()))
                .orElseThrow(() -> new IllegalArgumentException("Learning plan was replaced"));
        checkArgument(plan.repositoryId() == build.repositoryId() && plan.repository().equals(build.repository())
                && plan.baselineSha().equals(build.baselineSha()), "Learning plan differs from the frozen repository");
        ContextLearningPlan.Topic topic = topic(plan, assignment.topicId());
        checkArgument(topic.scopeId().equals(build.scopeIds().getFirst()) && topic.paths().equals(assignment.paths())
                        && topic.repositoryFiles().equals(assignment.repositoryFiles()),
                "Learning assignment differs from the accepted topic");
        RepositoryNotebook notebook = notebooks.getNotebook(build.workspaceId());
        checkArgument(notebook.scopes().stream().anyMatch(scope -> scope.id().equals(topic.scopeId()) && scope.enabled()
                        && matchDigest(scope.match()).equals(topic.scopeMatchDigest())),
                "Learning scope was disabled or its matching rule changed");
        checkArgument(notebook.sources().containsAll(build.documentationSources()), "Approved documentation source changed");
        return plan;
    }

    public ContextLearningPlan updateInventory(ContextBuild build, UnaryOperator<ContextDocumentInventory> update)
    {
        requireNonNull(update, "update is null");
        ContextLearningPlan plan = require(build);
        ContextDocumentInventory replacement = requireNonNull(update.apply(plan.documentInventory()), "Updated inventory is null");
        return plans.save(Optional.of(plan), plan.withInventory(replacement));
    }

    public ContextLearningPlan progress(ContextBuild build, String remainingQuestion, boolean completed, List<String> gaps)
    {
        ContextLimits.text(remainingQuestion, "remaining question", 2000);
        List<String> additions = ImmutableList.copyOf(gaps);
        checkArgument(additions.size() <= 20, "Too many progress limitations");
        ContextLearningPlan plan = require(build);
        String topicId = build.assignment().orElseThrow().topicId();
        ContextLearningPlan.Topic current = topic(plan, topicId);
        checkArgument(current.status() != ContextLearningPlan.Status.COMPLETED || completed,
                "Completed question cannot be reset by a later tool call");
        List<ContextLearningPlan.Topic> updated = plan.topics().stream().map(topic ->
                topic.id().equals(topicId) ? topic.progress(remainingQuestion, completed) : topic).toList();
        List<String> limitations = plan.gaps();
        for (String gap : additions) {
            limitations = ContextBuildService.gaps(limitations, gap);
        }
        return plans.save(Optional.of(plan), plan.withProgress(updated, limitations));
    }

    private ContextBuildService.View launch(ContextLearningPlan plan, String requestKey, long expectedBuildSequence,
            long expectedRevision, boolean initial)
    {
        Optional<ContextBuild> current = buildStore.find(plan.workspaceId());
        if (current.filter(build -> build.requestKey().equals(requestKey)).isPresent()) {
            ContextBuild replay = current.orElseThrow();
            checkArgument(replay.assignment().filter(assignment -> assignment.planId().equals(plan.id())).isPresent()
                    && replay.sequence() == expectedBuildSequence + 1 && replay.notebookRevision() == expectedRevision,
                    "Learning request key was reused with different inputs");
            return builds.get(plan.workspaceId());
        }
        checkArgument(!initial || expectedBuildSequence == plan.initialBuildSequence(),
                "Initial learning request sequence changed");
        checkArgument(builds.get(plan.workspaceId()).buildRequestSequence() == expectedBuildSequence,
                "Build request sequence changed; inspect latest learning task");
        RepositoryNotebook notebook = notebooks.getNotebook(plan.workspaceId());
        checkArgument(notebook.revision() == expectedRevision, "Notebook revision changed; inspect current context");
        ContextLearningPlan.Topic next = plan.topics().stream()
                .filter(topic -> topic.status() == ContextLearningPlan.Status.PENDING).findFirst().orElse(null);
        if (next == null) {
            return builds.get(plan.workspaceId());
        }
        List<RegisteredContextSource> selected = plan.documentationSources().stream()
                .filter(source -> next.sourceIds().contains(source.id())).toList();
        ContextBuild.Assignment assignment = new ContextBuild.Assignment(plan.id(), next.id(),
                next.remainingQuestion(), next.paths(), next.repositoryFiles());
        return builds.learn(plan.workspaceId(), new ContextBuildService.Request(requestKey, notebook.revision(),
                expectedBuildSequence, ImmutableList.of(next.scopeId()), ImmutableList.of()),
                plan.baselineSha(), selected, assignment);
    }

    private void checkAcceptedRepository(String workspaceId, ContextDiscoveryReport report)
    {
        WorkspaceRepositoryContext workspace = workspaces.requireReadableRepository(workspaceId);
        checkArgument(RepositoryContextReader.repositoryId(workspace) == report.repositoryId()
                        && workspace.monitoredRepository().equals(report.repository()), "Monitored repository changed since discovery");
        GitCommandRunner.Result resolved = git.run(workspace.repository(), "rev-parse", "--verify",
                "refs/remotes/" + workspace.sourceRemote() + "/" + workspace.defaultBranch() + "^{commit}");
        checkArgument(resolved.successful() && !resolved.truncated() && resolved.output().strip().equals(report.baselineSha()),
                "Monitored baseline changed since discovery; discover again before accepting topics");
    }

    private Prepared prepare(ContextDiscoveryReport report, List<ContextDiscoveryReport.Topic> selected, RepositoryNotebook notebook)
    {
        Map<String, ContextDiscoveryReport.Evidence> cited = new LinkedHashMap<>();
        report.evidence().forEach(value -> cited.put(value.readId(), value));
        Map<String, ContextDiscoveryReport.Source> suggestions = new LinkedHashMap<>();
        report.sources().forEach(value -> suggestions.put(value.id(), value));
        Map<String, String> sourceIds = new LinkedHashMap<>();
        List<RegisteredContextSource> registrations = new ArrayList<>();
        List<ContextSourceEvidence> captures = new ArrayList<>();
        Map<String, ContextSourceEvidence> captured = new LinkedHashMap<>();
        List<ContextScope> scopes = new ArrayList<>();
        Map<String, ContextScope> scopeById = new LinkedHashMap<>();
        notebook.scopes().forEach(scope -> scopeById.put(scope.id(), scope));

        for (ContextDiscoveryReport.Topic candidate : selected) {
            String scopeId = candidate.paths().isEmpty() ? "project" : "path:" + candidate.paths().getFirst();
            ContextScope existing = scopeById.get(scopeId);
            checkArgument(existing == null || existing.enabled(), "Learning scope is disabled: " + scopeId);
            if (existing == null) {
                String ref = evidence(report, cited, candidate.evidenceReadIds().getFirst(), captured).id();
                ContextScope scope = new ContextScope(scopeId, candidate.title(),
                        new ContextMatch.Selected(candidate.paths(), ImmutableList.of()), true, Optional.empty(), Optional.of(ref));
                scopes.add(scope);
                scopeById.put(scopeId, scope);
            }
            for (String proposedId : candidate.sourceIds()) {
                ContextDiscoveryReport.Source proposed = requireNonNull(suggestions.get(proposedId), "Discovery source is missing");
                if (proposed.kind() == ContextDiscoveryReport.Source.Kind.REPOSITORY_FILE) {
                    continue;
                }
                if (sourceIds.containsKey(proposed.id())) {
                    continue;
                }
                RegisteredContextSource.Kind kind = proposed.kind() == ContextDiscoveryReport.Source.Kind.DOCUMENTATION_ROOT
                        ? RegisteredContextSource.Kind.DOCUMENTATION_ROOT : RegisteredContextSource.Kind.DOCUMENTATION_PAGE;
                Optional<RegisteredContextSource> present = notebook.sources().stream()
                        .filter(source -> source.kind() == kind && source.url().equals(proposed.location())).findFirst();
                String registrationId = "discovery:" + report.id() + ":" + proposed.id();
                RegisteredContextSource registration = present.orElseGet(() -> new RegisteredContextSource(registrationId, kind,
                        proposed.location(), ContextEntryOrigin.GENERATED,
                        Optional.of(evidence(report, cited, proposed.evidenceReadIds().getFirst(), captured).id())));
                if (present.isEmpty()) {
                    checkArgument(notebook.sources().stream().noneMatch(source -> source.id().equals(registrationId)),
                            "Discovered source ID conflicts with existing configuration");
                    registrations.add(registration);
                }
                sourceIds.put(proposed.id(), registration.id());
            }
        }

        List<ContextLearningPlan.Topic> topics = selected.stream().map(candidate -> {
            LinkedHashSet<String> files = new LinkedHashSet<>();
            candidate.evidenceReadIds().stream().map(cited::get).map(ContextDiscoveryReport.Evidence::path).forEach(files::add);
            candidate.sourceIds().stream().map(suggestions::get).forEach(source -> {
                if (source.kind() == ContextDiscoveryReport.Source.Kind.REPOSITORY_FILE) {
                    files.add(source.location());
                }
                source.evidenceReadIds().stream().map(cited::get).map(ContextDiscoveryReport.Evidence::path).forEach(files::add);
            });
            String scopeId = candidate.paths().isEmpty() ? "project" : "path:" + candidate.paths().getFirst();
            List<String> docs = candidate.sourceIds().stream().filter(sourceIds::containsKey).map(sourceIds::get).distinct().toList();
            return new ContextLearningPlan.Topic(candidate.id(), candidate.title(), candidate.question(), scopeId,
                    matchDigest(scopeById.get(scopeId).match()), candidate.paths(),
                    ImmutableList.copyOf(files), docs, ContextLearningPlan.Status.PENDING, candidate.question());
        }).toList();
        List<RegisteredContextSource> documentation = sourceIds.values().stream().distinct().map(id -> notebook.sources().stream()
                .filter(source -> source.id().equals(id)).findFirst()
                .orElseGet(() -> registrations.stream().filter(source -> source.id().equals(id)).findFirst().orElseThrow())).toList();
        LinkedHashSet<String> retainedRefs = new LinkedHashSet<>();
        scopes.forEach(scope -> retainedRefs.add(scope.discoveryEvidenceRef().orElseThrow()));
        registrations.forEach(source -> retainedRefs.add(source.discoveryEvidenceRef().orElseThrow()));
        retainedRefs.forEach(ref -> captures.add(captured.values().stream().filter(value -> value.id().equals(ref)).findFirst().orElseThrow()));
        return new Prepared(scopes, registrations, captures, topics, documentation);
    }

    private static ContextSourceEvidence evidence(ContextDiscoveryReport report, Map<String, ContextDiscoveryReport.Evidence> cited,
            String readId, Map<String, ContextSourceEvidence> captured)
    {
        return captured.computeIfAbsent(readId, ignored -> {
            ContextDiscoveryReport.Evidence read = requireNonNull(cited.get(readId), "Discovery read is missing");
            return new ContextSourceEvidence("discovery:" + report.id() + ":" + readId,
                    new ContextReference.RepositoryFile(report.repositoryId(), report.repository(), read.path(), report.baselineSha()),
                    "Agent discovery cited ByteQuay frozen blob " + read.blobId() + " at lines " + read.startLine() + "-" + read.endLine(),
                    read.excerpt(), ContextSourceEvidence.Capture.BYTEQUAY_READ, Optional.of(report.createdAt()),
                    Optional.of("git-blob:" + read.blobId()), Optional.of(report.baselineSha()), read.truncated());
        });
    }

    private static ContextLearningPlan.Topic topic(ContextLearningPlan plan, String id)
    {
        return plan.topics().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Learning topic changed"));
    }

    private String matchDigest(ContextMatch match)
    {
        try {
            return "sha256:" + Hashing.sha256().hashString(mapper.writeValueAsString(match), UTF_8);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Scope matching rule cannot be encoded", failure);
        }
    }

    private String digest(StartRequest request)
    {
        try {
            return "sha256:" + Hashing.sha256().hashString(mapper.writeValueAsString(request), UTF_8);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Learning selection cannot be encoded", failure);
        }
    }

    public record StartRequest(String requestKey, long expectedRevision, long expectedBuildSequence, String reportId, List<String> topicIds)
    {
        public StartRequest
        {
            ContextLimits.text(requestKey, "requestKey", 100);
            checkArgument(expectedRevision >= 0 && expectedBuildSequence >= 0, "Revisions must not be negative");
            ContextLimits.text(reportId, "reportId", 100);
            topicIds = ImmutableList.copyOf(topicIds);
            ContextLimits.unique(topicIds, "topicIds", 20);
            checkArgument(!topicIds.isEmpty(), "Select at least one topic");
            topicIds.forEach(id -> ContextLimits.text(id, "topicId", 100));
        }
    }

    public record ContinueRequest(String requestKey, long expectedRevision, long expectedBuildSequence, String planId)
    {
        public ContinueRequest
        {
            ContextLimits.text(requestKey, "requestKey", 100);
            checkArgument(expectedRevision >= 0 && expectedBuildSequence >= 0, "Revisions must not be negative");
            ContextLimits.text(planId, "planId", 100);
        }
    }

    public record SourceRequest(String planId, Optional<String> sourceId, Optional<String> suggestedUrl,
            long expectedRevision, long expectedBuildSequence)
    {
        public SourceRequest(String planId, String sourceId, long expectedRevision, long expectedBuildSequence)
        {
            this(planId, Optional.of(sourceId), Optional.empty(), expectedRevision, expectedBuildSequence);
        }

        public SourceRequest
        {
            ContextLimits.text(planId, "planId", 100);
            requireNonNull(sourceId, "sourceId is null");
            requireNonNull(suggestedUrl, "suggestedUrl is null");
            checkArgument(sourceId.isPresent() != suggestedUrl.isPresent(), "Choose exactly one source ID or suggested URL");
            sourceId.ifPresent(value -> ContextLimits.text(value, "sourceId", 500));
            suggestedUrl.ifPresent(ContextLimits::documentationUrl);
            checkArgument(expectedRevision >= 0 && expectedBuildSequence >= 0, "Revisions must not be negative");
        }
    }

    public record DiscardRequest(String planId, long expectedRevision, long expectedBuildSequence)
    {
        public DiscardRequest
        {
            ContextLimits.text(planId, "planId", 100);
            checkArgument(expectedRevision >= 0 && expectedBuildSequence >= 0, "Revisions must not be negative");
        }
    }

    private record Prepared(List<ContextScope> scopes, List<RegisteredContextSource> sources, List<ContextSourceEvidence> evidence,
            List<ContextLearningPlan.Topic> topics, List<RegisteredContextSource> documentation) {}
}
