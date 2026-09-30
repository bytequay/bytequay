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
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparing;
import static java.util.Comparator.comparingInt;
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

    /**
     * Accepts topics into a new plan or, when they come from the plan's own discovery report, appends them to it.
     * Either way one run starts on the first accepted topic's first question.
     */
    public ContextBuildService.View start(String workspaceId, StartRequest request)
    {
        String digest = digest(request);
        Optional<ContextLearningPlan> existing = get(workspaceId);
        if (existing.filter(plan -> plan.acceptedRequestKey().equals(request.requestKey())).isPresent()) {
            ContextLearningPlan replay = existing.orElseThrow();
            checkArgument(replay.acceptedRequestDigest().equals(digest), "Learning request key was reused with different topics");
            return startBatch(replay, request.requestKey(), request.expectedBuildSequence(), replay.acceptedNotebookRevision(), true,
                    Optional.of(new ContextLearningPlan.Target(replay.acceptedTopicIds().getFirst(), 0)));
        }
        ContextDiscoveryReport report = reports.find(workspaceId).filter(value -> value.id().equals(request.reportId()))
                .orElseThrow(() -> new IllegalArgumentException("Discovery report changed; inspect the latest suggestions"));
        boolean extend = existing.filter(plan -> plan.reportId().equals(report.id())).isPresent();
        checkArgument(extend || existing.map(plan -> plan.next().isEmpty()).orElse(true),
                "Finish or discard the current learning plan before choosing topics from a newer discovery");
        existing.filter(ignored -> extend).ifPresent(plan -> {
            checkArgument(request.topicIds().stream().noneMatch(id -> plan.topics().stream().anyMatch(topic -> topic.id().equals(id))),
                    "A selected topic is already in the learning plan");
            checkArgument(plan.topics().size() + request.topicIds().size() <= ContextLearningPlan.MAX_TOPICS, "The learning plan has too many topics");
        });
        List<ContextDiscoveryReport.Topic> selected = request.topicIds().stream().map(id -> report.topics().stream()
                .filter(topic -> topic.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Selected discovery topic is unavailable: " + id))).toList();
        checkArgument(selected.stream().allMatch(topic -> topic.notePaths().isPresent()),
                "These suggestions have no explicit note applicability. Discover again before learning; saved notes are kept.");
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
            Prepared prepared = prepare(report, selected, notebook, request.excludedSourceIds());
            RepositoryNotebook updated = prepared.scopes().isEmpty() && prepared.sources().isEmpty()
                    ? notebook : notebooks.registerDiscovered(workspaceId, notebook.revision(),
                            prepared.scopes(), prepared.sources(), prepared.evidence());
            ContextLearningPlan accepted;
            if (extend) {
                ContextLearningPlan current = existing.orElseThrow();
                ContextDocumentInventory inventory = current.documentInventory();
                for (RegisteredContextSource source : prepared.documentation()) {
                    inventory = inventory.add(source);
                }
                checkArgument(inventory.sources().size() <= 20, "Learning plan has too many documentation sources");
                accepted = new ContextLearningPlan(current.id(), workspaceId, report.id(), request.requestKey(), digest, updated.revision(),
                        request.expectedBuildSequence(), request.topicIds(), current.repositoryId(), current.repository(), current.baselineSha(),
                        ImmutableList.<ContextLearningPlan.Topic>builder().addAll(current.topics()).addAll(prepared.topics()).build(),
                        inventory.sources(), inventory, current.gaps(), Instant.now(clock), current.batch());
            }
            else {
                accepted = new ContextLearningPlan(UUID.randomUUID().toString(), workspaceId, report.id(),
                        request.requestKey(), digest, updated.revision(), request.expectedBuildSequence(), request.topicIds(),
                        report.repositoryId(), report.repository(), report.baselineSha(), prepared.topics(),
                        prepared.documentation(), ContextDocumentInventory.initial(prepared.documentation()),
                        report.gaps(), Instant.now(clock), Optional.empty());
            }
            return plans.save(existing, accepted);
        }));
        return startBatch(plan, request.requestKey(), request.expectedBuildSequence(), plan.acceptedNotebookRevision(), true,
                Optional.of(new ContextLearningPlan.Target(request.topicIds().getFirst(), 0)));
    }

    public ContextBuildService.View continueLearning(String workspaceId, ContinueRequest request)
    {
        ContextLearningPlan plan = get(workspaceId).filter(value -> value.id().equals(request.planId()))
                .orElseThrow(() -> new IllegalArgumentException("Learning plan changed; inspect current progress"));
        return startBatch(plan, request.requestKey(), request.expectedBuildSequence(), request.expectedRevision(), false, Optional.empty());
    }

    /**
     * Starts the batch's next question once the previous run has ended and its worktree is gone. A stopped or failed run,
     * or a run the batch did not start, ends the batch; so does running out of questions.
     */
    public void continueBatch(String workspaceId)
    {
        if (plans.find(workspaceId).flatMap(ContextLearningPlan::batch).isEmpty()) {
            return;
        }
        ContextBuildService.View view = builds.get(workspaceId);
        if (!view.canStart()) {
            return;
        }
        ContextLearningPlan plan = plans.find(workspaceId).orElseThrow();
        ContextLearningPlan.Batch batch = plan.batch().orElseThrow();
        Optional<ContextBuild> last = buildStore.find(workspaceId).filter(build -> batch.owns(build.requestKey()));
        Optional<ContextRun.Outcome> outcome = last.flatMap(build -> view.runs().stream().filter(run -> run.taskId().equals(build.taskId())).findFirst())
                .map(ContextRun::outcome);
        if (outcome.isEmpty() || outcome.orElseThrow() == ContextRun.Outcome.STOPPED || outcome.orElseThrow() == ContextRun.Outcome.FAILED) {
            plans.save(Optional.of(plan), plan.withBatch(Optional.empty()));
            return;
        }
        List<ContextLearningPlan.Target> remaining = batch.remaining(plan);
        if (remaining.isEmpty() || batch.ran().size() >= ContextLearningPlan.Batch.MAX_RUNS) {
            plans.save(Optional.of(plan), plan.withBatch(Optional.empty()));
            return;
        }
        ContextLearningPlan.Target next = remaining.getFirst();
        List<ContextLearningPlan.Target> ran = ImmutableList.<ContextLearningPlan.Target>builder().addAll(batch.ran()).add(next).build();
        int total = Math.min(ContextLearningPlan.Batch.MAX_RUNS, ran.size() + remaining.size() - 1);
        List<ContextLearningPlan.Target> queue = batch.queue().stream().filter(target -> remaining.contains(target) && !target.equals(next))
                .limit(total - ran.size()).toList();
        ContextLearningPlan started = plans.save(Optional.of(plan), plan.withBatch(Optional.of(new ContextLearningPlan.Batch(batch.key(), queue, ran, total,
                batch.alone()))));
        try {
            launch(started, batch.runKey(ran.size()), view.buildRequestSequence(), notebooks.getNotebook(workspaceId).revision(), false, Optional.of(next));
        }
        catch (RuntimeException failure) {
            plans.find(workspaceId).filter(current -> current.batch().isPresent())
                    .ifPresent(current -> plans.save(Optional.of(current), current.withBatch(Optional.empty())));
            throw failure;
        }
    }

    /** Adds the user's question to a topic; it waits for the next batch. No agent runs. */
    public ContextLearningPlan addQuestion(String workspaceId, QuestionRequest request)
    {
        return changeTopic(workspaceId, request.planId(), request.topicId(), (plan, topic) -> {
            checkArgument(!topic.asks(request.text()), "This topic already has that question");
            checkArgument(topic.questions().size() < ContextLearningPlan.Topic.MAX_QUESTIONS, "This topic already has %s questions",
                    ContextLearningPlan.Topic.MAX_QUESTIONS);
            return topic.withQuestions(ImmutableList.<ContextLearningPlan.Question>builder().addAll(topic.questions())
                    .add(ContextLearningPlan.Question.added(request.text().strip(), ContextLearningPlan.Origin.USER, "")).build());
        });
    }

    /** Rewrites a question no run has started. No agent runs. */
    public ContextLearningPlan editQuestion(String workspaceId, QuestionEdit request)
    {
        return changeTopic(workspaceId, request.planId(), request.topicId(), (plan, topic) -> {
            ContextLearningPlan.Question question = openQuestion(workspaceId, plan, topic, request.question());
            checkArgument(question.status() == ContextLearningPlan.Status.PENDING, "Only a question no run has started can be edited; drop it instead");
            checkArgument(!topic.asks(request.text()) || question.text().strip().equalsIgnoreCase(request.text().strip()), "This topic already has that question");
            return topic.withQuestion(request.question(), question.withText(request.text().strip()));
        });
    }

    /** Closes an open question so no batch runs it; saved notes stay. No agent runs. */
    public ContextLearningPlan dropQuestion(String workspaceId, QuestionDrop request)
    {
        return changeTopic(workspaceId, request.planId(), request.topicId(), (plan, topic) -> {
            ContextLearningPlan.Question question = openQuestion(workspaceId, plan, topic, request.question());
            return topic.withQuestion(request.question(), question.closed(ContextLearningPlan.Status.DROPPED, question.progress(), "Dropped by you"));
        });
    }

    /**
     * Files a question a development task or review needed answered, with the paths it concerns and an optional lead: the page
     * or lines that answered it. The question joins the topic whose note target covers every path, the narrowest when several
     * do; without one it becomes a plan gap for the next discovery. No agent runs.
     */
    public Asked ask(String workspaceId, Ask ask)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> unitOfWork.execute(() -> {
            ContextLearningPlan plan = get(workspaceId).orElseThrow(() -> new IllegalArgumentException(
                    "This repository has no learning plan to file questions in; keep the finding in this work"));
            Optional<ContextLearningPlan.Topic> covering = plan.topics().stream().filter(topic -> depth(topic, ask.paths()) >= 0)
                    .max(comparingInt(topic -> depth(topic, ask.paths())));
            if (covering.isEmpty()) {
                checkArgument(plan.gaps().size() < ContextBuildService.MAX_GAPS, "The learning plan holds no more gaps; keep the finding in this work");
                String gap = (ask.origin() == ContextLearningPlan.Origin.REVIEW ? "A review" : "A task") + " asked: " + ask.question().strip()
                        + " (" + String.join(", ", ask.paths()) + ")" + ask.lead().map(lead -> " Lead: " + lead).orElse("");
                plans.save(Optional.of(plan), plan.withProgress(plan.topics(), ContextBuildService.gaps(plan.gaps(), gap)));
                return new Asked("GAP", Optional.empty(), "No plan topic covers these paths; the next discovery sees the question as a gap.");
            }
            ContextLearningPlan.Topic topic = covering.orElseThrow();
            if (topic.asks(ask.question())) {
                return new Asked("ALREADY_ASKED", Optional.of(topic.title()), "The plan already asks this question.");
            }
            checkArgument(topic.questions().size() < ContextLearningPlan.Topic.MAX_QUESTIONS, "The plan topic %s already has %s questions",
                    topic.title(), ContextLearningPlan.Topic.MAX_QUESTIONS);
            List<ContextLearningPlan.Question> questions = ImmutableList.<ContextLearningPlan.Question>builder().addAll(topic.questions())
                    .add(ContextLearningPlan.Question.added(ask.question().strip(), ask.origin(), ask.lead().orElse(""))).build();
            plans.save(Optional.of(plan), plan.withProgress(plan.topics().stream()
                    .map(value -> value.id().equals(topic.id()) ? value.withQuestions(questions) : value).toList(), plan.gaps()));
            return new Asked("ADDED", Optional.of(topic.title()), "The next learning batch checks the lead against the code and saves the note.");
        }));
    }

    /**
     * How narrowly a topic's note target covers every path: the length of the shortest best-matching note path, 0 for the
     * whole repository, and -1 when a path is outside it or the topic has no explicit note target.
     */
    private static int depth(ContextLearningPlan.Topic topic, List<String> paths)
    {
        return topic.notePaths().map(notePaths -> notePaths.isEmpty() ? 0 : paths.stream()
                .mapToInt(path -> notePaths.stream().filter(prefix -> (path + "/").startsWith(prefix)).mapToInt(String::length).max().orElse(-1))
                .min().orElse(-1)).orElse(-1);
    }

    /** Adds a question asking what the topic should cover next and starts one run for it alone. */
    public ContextBuildService.View goDeeper(String workspaceId, DeeperRequest request)
    {
        if (buildStore.find(workspaceId).filter(build -> build.requestKey().equals(request.requestKey())).isPresent()) {
            return builds.get(workspaceId);
        }
        ContextLearningPlan plan = get(workspaceId).filter(value -> value.id().equals(request.planId()))
                .orElseThrow(() -> new IllegalArgumentException("Learning plan changed; inspect current progress"));
        ContextLearningPlan.Topic topic = topic(plan, request.topicId());
        String text = "What else should someone working on " + topic.title() + " know that this topic's answered questions don't cover? "
                + "Propose the follow-up questions worth learning, and save notes only for what the code shows.";
        int index = topic.questions().stream().filter(question -> question.open() && question.text().equals(text)).findFirst()
                .map(topic.questions()::indexOf).orElse(topic.questions().size());
        ContextLearningPlan deeper = plan;
        if (index == topic.questions().size()) {
            checkArgument(index < ContextLearningPlan.Topic.MAX_QUESTIONS, "This topic already has %s questions", ContextLearningPlan.Topic.MAX_QUESTIONS);
            deeper = addQuestionAt(workspaceId, plan, topic, ContextLearningPlan.Question.added(text, ContextLearningPlan.Origin.USER, "Go deeper"));
        }
        return startBatch(deeper, request.requestKey(), request.expectedBuildSequence(), request.expectedRevision(), false,
                Optional.of(new ContextLearningPlan.Target(topic.id(), index)), true);
    }

    private ContextLearningPlan addQuestionAt(String workspaceId, ContextLearningPlan plan, ContextLearningPlan.Topic topic, ContextLearningPlan.Question question)
    {
        return changeTopic(workspaceId, plan.id(), topic.id(), (current, found) -> found.withQuestions(ImmutableList.<ContextLearningPlan.Question>builder()
                .addAll(found.questions()).add(question).build()));
    }

    private ContextLearningPlan changeTopic(String workspaceId, String planId, String topicId,
            BiFunction<ContextLearningPlan, ContextLearningPlan.Topic, ContextLearningPlan.Topic> change)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> unitOfWork.execute(() -> {
            ContextLearningPlan plan = get(workspaceId).filter(value -> value.id().equals(planId))
                    .orElseThrow(() -> new IllegalArgumentException("Learning plan changed; inspect current progress"));
            ContextLearningPlan.Topic replacement = change.apply(plan, topic(plan, topicId));
            return plans.save(Optional.of(plan), plan.withProgress(plan.topics().stream()
                    .map(topic -> topic.id().equals(topicId) ? replacement : topic).toList(), plan.gaps()));
        }));
    }

    /** An open question that no run is working on right now. */
    private ContextLearningPlan.Question openQuestion(String workspaceId, ContextLearningPlan plan, ContextLearningPlan.Topic topic, int index)
    {
        checkArgument(index >= 0 && index < topic.questions().size(), "Learning question changed");
        ContextLearningPlan.Question question = topic.questions().get(index);
        checkArgument(question.open(), "This question is already answered, corrected or dropped");
        boolean running = !builds.get(workspaceId).canStart() && buildStore.find(workspaceId).flatMap(ContextBuild::assignment)
                .filter(assignment -> assignment.planId().equals(plan.id()) && assignment.topicId().equals(topic.id()) && assignment.questionIndex() == index)
                .isPresent();
        checkArgument(!running, "A run is working on this question; stop it first");
        return question;
    }

    /** One click runs each open question in turn, starting with the requested one; the coordinator starts the rest. */
    private ContextBuildService.View startBatch(ContextLearningPlan plan, String requestKey, long expectedBuildSequence,
            long expectedRevision, boolean accepted, Optional<ContextLearningPlan.Target> requested)
    {
        return startBatch(plan, requestKey, expectedBuildSequence, expectedRevision, accepted, requested, false);
    }

    private ContextBuildService.View startBatch(ContextLearningPlan plan, String requestKey, long expectedBuildSequence,
            long expectedRevision, boolean accepted, Optional<ContextLearningPlan.Target> requested, boolean alone)
    {
        if (buildStore.find(plan.workspaceId()).filter(build -> build.requestKey().startsWith(requestKey + "#")).isPresent()) {
            return builds.get(plan.workspaceId());
        }
        List<ContextLearningPlan.Target> open = plan.open();
        Optional<ContextLearningPlan.Target> first = requested.or(() -> open.stream().findFirst());
        if (first.isEmpty() || buildStore.find(plan.workspaceId()).filter(build -> build.requestKey().equals(requestKey)).isPresent()) {
            return launch(plan, requestKey, expectedBuildSequence, expectedRevision, accepted, first);
        }
        checkArgument(requestKey.length() <= 90, "A learning request key is at most 90 characters");
        List<ContextLearningPlan.Target> queue = alone ? ImmutableList.of() : open.stream().filter(target -> !target.equals(first.orElseThrow()))
                .limit(ContextLearningPlan.Batch.MAX_RUNS - 1).toList();
        ContextLearningPlan batched = plans.save(Optional.of(plan), plan.withBatch(Optional.of(new ContextLearningPlan.Batch(requestKey, queue,
                ImmutableList.of(first.orElseThrow()), queue.size() + 1, alone))));
        try {
            return launch(batched, requestKey, expectedBuildSequence, expectedRevision, accepted, first);
        }
        catch (RuntimeException failure) {
            // A refused click leaves the plan as it was, including a batch still running.
            plans.find(plan.workspaceId()).filter(batched::equals).ifPresent(current -> plans.save(Optional.of(current), plan));
            throw failure;
        }
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

    /**
     * Approves one exact suggested URL at the boundary the user chose; it never starts a model or resets existing link depths.
     * The source joins the topic whose run found the link.
     */
    public ContextLearningPlan allowSource(String workspaceId, SourceRequest request)
    {
        ContextBuildService.View state = builds.get(workspaceId);
        checkArgument(state.canStart() && state.buildRequestSequence() == request.expectedBuildSequence(), "Finish the current task before approving sources");
        return WorkspaceOperationLocks.withLock(workspaceId, () -> unitOfWork.execute(() -> {
            RepositoryNotebook notebook = notebooks.getNotebook(workspaceId);
            checkArgument(notebook.revision() == request.expectedRevision(), "Notebook revision changed; inspect registered sources");
            checkArgument(buildStore.find(workspaceId).map(ContextBuild::sequence).orElse(0L) == request.expectedBuildSequence(), "Build request sequence changed");
            ContextLearningPlan plan = plans.find(workspaceId).filter(value -> value.id().equals(request.planId()))
                    .orElseThrow(() -> new IllegalArgumentException("Learning plan changed"));
            checkArgument(notebook.sources().containsAll(plan.documentationSources()),
                    "Approved documentation source changed; discard the old plan before expanding sources");
            ContextDocumentInventory.Suggestion suggestion = plan.documentInventory().suggestion(request.suggestedUrl())
                    .orElseThrow(() -> new IllegalArgumentException("Choose an exact URL suggested by a recorded document read"));
            String url = suggestion.url();
            checkArgument(request.boundary() == Boundary.PAGE || url.endsWith("/"), "Only a URL ending in / can be approved as a documentation root");
            RegisteredContextSource.Kind kind = request.boundary() == Boundary.ROOT
                    ? RegisteredContextSource.Kind.DOCUMENTATION_ROOT : RegisteredContextSource.Kind.DOCUMENTATION_PAGE;
            RegisteredContextSource source = notebook.sources().stream().filter(value -> value.url().equals(url) && value.kind() == kind).findFirst()
                    .orElseGet(() -> new RegisteredContextSource("suggested:" + Hashing.sha256().hashString(kind + " " + url, UTF_8),
                            kind, url, ContextEntryOrigin.USER, Optional.empty()));
            checkArgument(notebook.sources().stream().noneMatch(value -> value.id().equals(source.id()) && !value.equals(source)),
                    "Suggested source identity is already registered differently");
            ContextLearningPlan.Topic owner = plan.topic(suggestion.topicId());
            checkArgument(plan.documentationSources().size() < 20 || plan.documentationSources().contains(source),
                    "Learning plan has too many documentation sources");
            checkArgument(owner.sourceIds().size() < 20 || owner.sourceIds().contains(source.id()), "Learning topic has too many documentation sources");
            if (!notebook.sources().contains(source)) {
                List<EditContextConfiguration.Source> registrations = ImmutableList.<EditContextConfiguration.Source>builder()
                        .addAll(notebook.sources().stream().map(value -> new EditContextConfiguration.Source(value.id(), value.kind(), value.url())).toList())
                        .add(new EditContextConfiguration.Source(source.id(), source.kind(), source.url())).build();
                notebooks.configure(workspaceId, notebook.revision(),
                        new EditContextConfiguration(Optional.empty(), Optional.empty(), Optional.of(registrations)));
            }
            Optional<RegisteredContextSource> known = plan.documentationSources().stream().filter(value -> value.id().equals(source.id())).findFirst();
            checkArgument(known.isEmpty() || known.orElseThrow().equals(source), "This source changed; discard the old plan before accepting a different version");
            ContextDocumentInventory inventory = plan.documentInventory().withoutSuggestion(url).add(source);
            List<ContextLearningPlan.Topic> topics = plan.topics().stream()
                    .map(topic -> topic.id().equals(owner.id()) ? topic.withSource(source.id()) : topic).toList();
            return plans.save(Optional.of(plan), plan.withSources(topics, inventory));
        }));
    }

    /** Removes one suggestion without registering anything; learning stays within the approved sources. */
    public ContextLearningPlan declineSource(String workspaceId, DeclineRequest request)
    {
        return WorkspaceOperationLocks.withLock(workspaceId, () -> unitOfWork.execute(() -> {
            ContextLearningPlan plan = plans.find(workspaceId).filter(value -> value.id().equals(request.planId()))
                    .orElseThrow(() -> new IllegalArgumentException("Learning plan changed"));
            return plans.save(Optional.of(plan), plan.withInventory(plan.documentInventory().withoutSuggestion(request.suggestedUrl())));
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
        requireScope(notebook, topic);
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

    public ContextLearningPlan progress(ContextBuild build, String progress, boolean completed, List<String> gaps)
    {
        return progress(build, progress, completed, gaps, Optional.empty(), ImmutableList.of());
    }

    /**
     * Saves the run's checkpoint for its question. A correction closes the question with its reason and adds the questions
     * that replace it; follow-ups join the same topic. Questions the topic already asks are not added twice. Only a question
     * from discovery, the user, a task or a review adds questions, so what learning runs add grows the plan one generation deep.
     */
    public ContextLearningPlan progress(ContextBuild build, String progress, boolean completed, List<String> gaps, Optional<Correction> correction,
            List<FollowUp> followUps)
    {
        ContextLimits.text(progress, "question progress", 2000);
        List<String> additions = ImmutableList.copyOf(gaps);
        checkArgument(additions.size() <= 20, "Too many progress limitations");
        checkArgument(followUps.size() <= 3, "Add at most three follow-up questions per run");
        ContextLearningPlan plan = require(build);
        ContextBuild.Assignment assignment = build.assignment().orElseThrow();
        ContextLearningPlan.Topic target = topic(plan, assignment.topicId());
        ContextLearningPlan.Question current = target.questions().get(assignment.questionIndex());
        checkArgument(current.origin() != ContextLearningPlan.Origin.LEARNING || (followUps.isEmpty() && correction.map(value -> value.questions().isEmpty()).orElse(true)),
                "A learning run added this question, so its run adds no questions: pass no followUps and no correction questions, and put other questions in gaps");
        checkArgument(current.status() != ContextLearningPlan.Status.COMPLETED || completed || correction.isPresent(),
                "Completed question cannot be reset by a later tool call");
        checkArgument(current.status() != ContextLearningPlan.Status.CORRECTED && current.status() != ContextLearningPlan.Status.DROPPED,
                "This question was closed while the run worked; end the run");
        List<ContextLearningPlan.Question> questions = new ArrayList<>(target.questions());
        questions.set(assignment.questionIndex(), correction.map(value -> current.closed(ContextLearningPlan.Status.CORRECTED, progress, value.reason()))
                .orElseGet(() -> current.withProgress(completed ? ContextLearningPlan.Status.COMPLETED : ContextLearningPlan.Status.UNFINISHED, progress)));
        List<ContextLearningPlan.Question> added = new ArrayList<>();
        correction.ifPresent(value -> value.questions().forEach(text -> added.add(ContextLearningPlan.Question.added(text,
                ContextLearningPlan.Origin.LEARNING, "Replaces \u201c" + current.text() + "\u201d"))));
        followUps.forEach(followUp -> added.add(ContextLearningPlan.Question.added(followUp.question(), ContextLearningPlan.Origin.LEARNING, followUp.why())));
        for (ContextLearningPlan.Question question : added) {
            if (questions.stream().noneMatch(existing -> existing.text().strip().equalsIgnoreCase(question.text().strip()))) {
                questions.add(question);
            }
        }
        checkArgument(questions.size() <= ContextLearningPlan.Topic.MAX_QUESTIONS, "The topic holds at most %s questions; add fewer and put the rest in gaps",
                ContextLearningPlan.Topic.MAX_QUESTIONS);
        List<ContextLearningPlan.Topic> updated = plan.topics().stream().map(topic -> topic.id().equals(assignment.topicId())
                ? topic.withQuestions(questions) : topic).toList();
        List<String> limitations = plan.gaps();
        for (String gap : additions) {
            limitations = ContextBuildService.gaps(limitations, gap);
        }
        return plans.save(Optional.of(plan), plan.withProgress(updated, limitations));
    }

    private ContextBuildService.View launch(ContextLearningPlan plan, String requestKey, long expectedBuildSequence,
            long expectedRevision, boolean accepted, Optional<ContextLearningPlan.Target> requested)
    {
        Optional<ContextBuild> current = buildStore.find(plan.workspaceId());
        if (current.filter(build -> build.requestKey().equals(requestKey)).isPresent()) {
            ContextBuild replay = current.orElseThrow();
            checkArgument(replay.assignment().filter(assignment -> assignment.planId().equals(plan.id())).isPresent()
                    && replay.sequence() == expectedBuildSequence + 1 && replay.notebookRevision() == expectedRevision,
                    "Learning request key was reused with different inputs");
            return builds.get(plan.workspaceId());
        }
        checkArgument(!accepted || expectedBuildSequence == plan.acceptedBuildSequence(),
                "Accepted learning request sequence changed");
        checkArgument(builds.get(plan.workspaceId()).buildRequestSequence() == expectedBuildSequence,
                "Build request sequence changed; inspect latest learning task");
        RepositoryNotebook notebook = notebooks.getNotebook(plan.workspaceId());
        checkArgument(notebook.revision() == expectedRevision, "Notebook revision changed; inspect current context");
        Optional<ContextLearningPlan.Target> target = requested.or(plan::next);
        if (target.isEmpty()) {
            return builds.get(plan.workspaceId());
        }
        ContextLearningPlan.Topic topic = topic(plan, target.orElseThrow().topicId());
        requireScope(notebook, topic);
        int index = target.orElseThrow().question();
        checkArgument(index < topic.questions().size(), "Learning question changed");
        ContextLearningPlan.Question question = topic.questions().get(index);
        checkArgument(question.open(), "This learning question is already answered, corrected or dropped");
        List<RegisteredContextSource> selected = plan.documentationSources().stream()
                .filter(source -> topic.sourceIds().contains(source.id())).toList();
        ContextBuild.Assignment assignment = new ContextBuild.Assignment(plan.id(), topic.id(), topic.title(), index,
                question.text(), question.progress(), topic.paths(), topic.repositoryFiles());
        return builds.learn(plan.workspaceId(), new ContextBuildService.Request(requestKey, notebook.revision(),
                expectedBuildSequence, ImmutableList.of(topic.scopeId()), ImmutableList.of()),
                plan.baselineSha(), selected, assignment);
    }

    private void checkAcceptedRepository(String workspaceId, ContextDiscoveryReport report)
    {
        WorkspaceRepositoryContext workspace = workspaces.requireReadableRepository(workspaceId);
        checkArgument(RepositoryContextReader.repositoryId(workspace) == report.repositoryId()
                        && workspace.monitoredRepository().equals(report.repository()), "Monitored repository changed since discovery");
        // Learning reads the recorded discovery revision, so a later fetch of the monitored branch does not invalidate suggestions.
        GitCommandRunner.Result resolved = git.run(workspace.repository(), "rev-parse", "--verify", report.baselineSha() + "^{commit}");
        checkArgument(resolved.successful() && !resolved.truncated() && resolved.output().strip().equals(report.baselineSha()),
                "The discovery revision is no longer available locally; discover again before accepting topics");
    }

    /** Proposed sources the user unchecked are neither registered nor offered to the topic's runs. */
    private Prepared prepare(ContextDiscoveryReport report, List<ContextDiscoveryReport.Topic> selected, RepositoryNotebook notebook, List<String> excluded)
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
        Map<String, ContextScope> topicScopes = new LinkedHashMap<>();

        for (ContextDiscoveryReport.Topic candidate : selected) {
            List<String> notePaths = candidate.notePaths().orElseThrow();
            ContextScope scope = scopeById.values().stream()
                    .filter(value -> matchesPaths(value.match(), notePaths))
                    .min(comparing(value -> !value.enabled()))
                    .orElseGet(() -> {
                        ContextMatch match = new ContextMatch.Selected(notePaths.stream().sorted().toList(), ImmutableList.of());
                        String scopeId = "discovery:" + matchDigest(match);
                        checkArgument(!scopeById.containsKey(scopeId), "Discovered scope ID conflicts with existing configuration");
                        String ref = evidence(report, cited, candidate.evidenceReadIds().getFirst(), captured).id();
                        ContextScope created = new ContextScope(scopeId, candidate.title(), match, true, Optional.empty(), Optional.of(ref));
                        scopes.add(created);
                        scopeById.put(scopeId, created);
                        return created;
                    });
            checkArgument(scope.enabled(), "Learning scope is disabled: " + scope.id());
            topicScopes.put(candidate.id(), scope);
            for (String proposedId : candidate.sourceIds()) {
                if (excluded.contains(proposedId)) {
                    continue;
                }
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
            candidate.sourceIds().stream().filter(id -> !excluded.contains(id)).map(suggestions::get).forEach(source -> {
                if (source.kind() == ContextDiscoveryReport.Source.Kind.REPOSITORY_FILE) {
                    files.add(source.location());
                }
                source.evidenceReadIds().stream().map(cited::get).map(ContextDiscoveryReport.Evidence::path).forEach(files::add);
            });
            ContextScope scope = topicScopes.get(candidate.id());
            List<String> docs = candidate.sourceIds().stream().filter(sourceIds::containsKey).map(sourceIds::get).distinct().toList();
            return new ContextLearningPlan.Topic(candidate.id(), candidate.title(), scope.id(),
                    matchDigest(scope.match()), candidate.paths(), candidate.notePaths(), ImmutableList.copyOf(files), docs,
                    candidate.questions().stream().map(ContextLearningPlan.Question::open).toList());
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
        return plan.topic(id);
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

    private void requireScope(RepositoryNotebook notebook, ContextLearningPlan.Topic topic)
    {
        List<String> notePaths = topic.notePaths().orElseThrow(() -> new IllegalArgumentException(
                "This learning plan has no explicit note applicability. Discard the plan and discover again; saved notes are kept."));
        ContextScope scope = notebook.scopes().stream().filter(value -> value.id().equals(topic.scopeId()) && value.enabled()
                        && matchDigest(value.match()).equals(topic.scopeMatchDigest())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Learning scope was disabled or its matching rule changed"));
        checkArgument(matchesPaths(scope.match(), notePaths),
                "Saved note paths do not match its note scope. Discard this learning plan and select topics again; saved notes are kept.");
    }

    private static boolean matchesPaths(ContextMatch rule, List<String> paths)
    {
        return paths.isEmpty() ? rule instanceof ContextMatch.Repository
                : rule instanceof ContextMatch.Selected match && match.terms().isEmpty()
                        && match.pathPrefixes().size() == paths.size() && match.pathPrefixes().containsAll(paths);
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

    public record StartRequest(String requestKey, long expectedRevision, long expectedBuildSequence, String reportId, List<String> topicIds,
            List<String> excludedSourceIds)
    {
        public StartRequest
        {
            excludedSourceIds = ImmutableList.copyOf(excludedSourceIds);
            ContextLimits.unique(excludedSourceIds, "excludedSourceIds", ContextDiscoveryReport.MAX_SOURCES);
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

    public enum Boundary
    {
        PAGE,
        ROOT
    }

    public record SourceRequest(String planId, String suggestedUrl, Boundary boundary, long expectedRevision, long expectedBuildSequence)
    {
        public SourceRequest
        {
            ContextLimits.text(planId, "planId", 100);
            ContextLimits.documentationUrl(suggestedUrl);
            requireNonNull(boundary, "boundary is null");
            checkArgument(expectedRevision >= 0 && expectedBuildSequence >= 0, "Revisions must not be negative");
        }
    }

    public record DeclineRequest(String planId, String suggestedUrl)
    {
        public DeclineRequest
        {
            ContextLimits.text(planId, "planId", 100);
            ContextLimits.documentationUrl(suggestedUrl);
        }
    }

    /** Why a run closed its question and up to three questions that replace it. */
    public record Correction(String reason, List<String> questions)
    {
        public Correction
        {
            ContextLimits.text(reason, "correction reason", ContextLearningPlan.Question.MAX_NOTE_BYTES);
            questions = ImmutableList.copyOf(questions);
            checkArgument(questions.size() <= 3, "A correction replaces the question with at most three questions");
            questions.forEach(question -> ContextLimits.text(question, "corrected question", 1000));
        }
    }

    /** A question the run found its topic should also answer, and why. */
    public record FollowUp(String question, String why)
    {
        public FollowUp
        {
            ContextLimits.text(question, "follow-up question", 1000);
            ContextLimits.text(why, "follow-up reason", ContextLearningPlan.Question.MAX_NOTE_BYTES);
        }
    }

    public record QuestionRequest(String planId, String topicId, String text)
    {
        public QuestionRequest
        {
            ContextLimits.text(planId, "planId", 100);
            ContextLimits.text(topicId, "topicId", 100);
            ContextLimits.text(text, "learning question", 1000);
        }
    }

    public record QuestionEdit(String planId, String topicId, int question, String text)
    {
        public QuestionEdit
        {
            ContextLimits.text(planId, "planId", 100);
            ContextLimits.text(topicId, "topicId", 100);
            ContextLimits.text(text, "learning question", 1000);
        }
    }

    public record QuestionDrop(String planId, String topicId, int question)
    {
        public QuestionDrop
        {
            ContextLimits.text(planId, "planId", 100);
            ContextLimits.text(topicId, "topicId", 100);
        }
    }

    public record DeeperRequest(String requestKey, long expectedRevision, long expectedBuildSequence, String planId, String topicId)
    {
        public DeeperRequest
        {
            ContextLimits.text(requestKey, "requestKey", 90);
            checkArgument(expectedRevision >= 0 && expectedBuildSequence >= 0, "Invalid Go deeper request");
            ContextLimits.text(planId, "planId", 100);
            ContextLimits.text(topicId, "topicId", 100);
        }
    }

    /** A question a task or review files for learning; paths are repository files or directories. */
    public record Ask(String question, List<String> paths, Optional<String> lead, ContextLearningPlan.Origin origin)
    {
        public Ask
        {
            ContextLimits.text(question, "question", 1000);
            paths = paths.stream().map(path -> path.endsWith("/") ? path.substring(0, path.length() - 1) : path).collect(toImmutableList());
            checkArgument(!paths.isEmpty(), "Name the repository paths the question concerns");
            ContextLimits.unique(paths, "paths", 8);
            paths.forEach(path -> ContextLimits.pathPrefix(path + "/"));
            requireNonNull(lead, "lead is null");
            lead.ifPresent(value -> ContextLimits.text(value, "lead", ContextLearningPlan.Question.MAX_NOTE_BYTES));
            checkArgument(origin == ContextLearningPlan.Origin.TASK || origin == ContextLearningPlan.Origin.REVIEW, "Only tasks and reviews ask questions");
        }
    }

    public record Asked(String status, Optional<String> topic, String note) {}

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
