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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TaskState;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextPreparationResult;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * What one explicit discovery, learning or spec check run did and how it ended, derived from its build capture and agent task.
 * Pages opened, questions answered and notes proposed stay separate facts.
 */
public record ContextRun(
        long sequence, String taskId, ContextBuild.Mode mode, Instant requestedAt, Optional<Instant> endedAt,
        Optional<Question> question, Optional<Integer> pullRequest, Outcome outcome, int sourceOperations, int documentOpens,
        List<String> noteIds, List<ContextBuild.Activity> activity, Optional<String> detail, Optional<Investigated> investigation,
        Optional<Checked> check)
{
    /** Capture attempt key under which a learning run records whether its question was answered. */
    public static final String PROGRESS = "question-progress";
    public static final String COMPLETED = "COMPLETED";
    public static final String UNFINISHED = "UNFINISHED";
    public static final String CORRECTED = "CORRECTED";
    /** Capture attempt key under which an investigation records the outcome that settled its dispute. */
    public static final String SETTLED = "investigation-settled";
    /** Capture attempt key under which a spec check turn records that it saved its candidates or its reproduction result. */
    public static final String CHECKED = "spec-check-saved";

    public ContextRun
    {
        checkArgument(sequence > 0, "Invalid run sequence");
        ContextLimits.text(taskId, "run task", 200);
        requireNonNull(mode, "mode is null");
        requireNonNull(requestedAt, "requestedAt is null");
        requireNonNull(endedAt, "endedAt is null");
        requireNonNull(question, "question is null");
        requireNonNull(pullRequest, "pullRequest is null");
        requireNonNull(outcome, "outcome is null");
        checkArgument(sourceOperations >= 0 && documentOpens >= 0, "Invalid run counts");
        noteIds = ImmutableList.copyOf(noteIds);
        activity = ImmutableList.copyOf(activity);
        requireNonNull(detail, "detail is null");
        detail.ifPresent(value -> ContextLimits.text(value, "run detail", 1000));
        requireNonNull(investigation, "investigation is null");
        requireNonNull(check, "check is null");
    }

    /** Summarizes a build without starting, resending or settling anything. */
    public static ContextRun of(ContextBuild build, Optional<AgentTask> task, Optional<AgentTurn> turn)
    {
        ContextBuild.Capture capture = build.capture();
        boolean discovery = build.mode() == ContextBuild.Mode.DISCOVER;
        int operations = (int) capture.attempts().keySet().stream()
                .filter(key -> key.startsWith(discovery ? "discovery:" : "read-operation:")).count();
        int opens = (int) capture.attempts().keySet().stream().filter(key -> key.startsWith("document-open:")).count();
        Optional<String> settledBy = Optional.ofNullable(capture.attempts().get(SETTLED));
        List<String> notes = build.investigation().isPresent()
                ? settledBy.filter(outcome -> !outcome.equals(RepositoryContextService.Settle.Outcome.KEEP_CURRENT.name()))
                        .map(ignored -> ImmutableList.of(build.investigation().orElseThrow().entryId())).orElse(ImmutableList.of())
                : capture.result().stream().flatMap(result -> result.scopeResults().stream())
                        .filter(result -> result.outcome() == ContextPreparationResult.Outcome.READY)
                        .flatMap(result -> result.noteIds().stream()).distinct().collect(ImmutableList.toImmutableList());
        Optional<Question> question = build.assignment().map(assignment -> new Question(assignment.planId(), assignment.topicId(),
                assignment.topicTitle(), assignment.questionIndex(), assignment.question()));
        Optional<Integer> pullRequest = build.assignment().isPresent() ? Optional.empty() : build.history().stream()
                .filter(history -> history.kind() == ContextHistoryReader.SourceKind.PR).map(ContextBuild.History::number).findFirst();
        Outcome outcome = outcome(build, task, turn, notes);
        Optional<String> detail = task.flatMap(AgentTask::terminalReason).or(() -> capture.gaps().stream().findFirst())
                .filter(ignored -> outcome == Outcome.FAILED).map(value -> ContextText.excerpt(value, 1000));
        Optional<Instant> ended = outcome == Outcome.RUNNING ? Optional.empty()
                : turn.flatMap(AgentTurn::endedAt).or(() -> task.flatMap(AgentTask::terminalAt));
        return new ContextRun(build.sequence(), build.taskId(), build.mode(), build.requestedAt(), ended, question, pullRequest, outcome,
                operations, opens, notes, capture.activity(), detail,
                build.investigation().map(value -> new Investigated(value.entryId(), value.title(), value.statement(), settledBy)),
                build.check().map(value -> new Checked(value.checkId(), value.topicTitle(), value.reproduction().map(ContextBuild.Reproduction::title))));
    }

    private static Outcome outcome(ContextBuild build, Optional<AgentTask> task, Optional<AgentTurn> turn, List<String> notes)
    {
        if (task.isEmpty()) {
            return build.capture().attempts().containsKey("allocation-failed") ? Outcome.FAILED : Outcome.RUNNING;
        }
        if (task.orElseThrow().state() == TaskState.OPEN && turn.filter(value -> value.state().terminal()).isEmpty()) {
            return Outcome.RUNNING;
        }
        if (turn.flatMap(AgentTurn::cancellationRequestedAt).isPresent()) {
            return Outcome.STOPPED;
        }
        String progress = build.capture().attempts().getOrDefault(PROGRESS, "");
        if (progress.equals(CORRECTED)) {
            return Outcome.CORRECTED;
        }
        boolean settled = build.assignment().isPresent() ? progress.equals(COMPLETED)
                : build.check().isPresent() ? build.capture().attempts().containsKey(CHECKED) : build.capture().result().isPresent();
        if (settled) {
            return build.mode() != ContextBuild.Mode.LEARN ? Outcome.SUBMITTED
                    : build.investigation().isPresent() ? Outcome.SETTLED : notes.isEmpty() ? Outcome.NO_NOTE : Outcome.ANSWERED;
        }
        if (build.capture().activity().stream().anyMatch(line -> line.tone() == ContextBuild.Activity.Tone.LIMIT)) {
            return Outcome.LIMIT;
        }
        return progress.equals(UNFINISHED) ? Outcome.UNFINISHED : Outcome.FAILED;
    }

    public record Question(String planId, String topicId, String topicTitle, int index, String text)
    {
        public Question
        {
            ContextLimits.text(planId, "planId", 200);
            ContextLimits.text(topicId, "topicId", 200);
            ContextLimits.text(topicTitle, "topic title", 200);
            checkArgument(index >= 0, "Invalid question index");
            ContextLimits.text(text, "question", 1000);
        }
    }

    /** The note and statement an investigation run took up, and the outcome that settled it. */
    public record Investigated(String entryId, String title, String statement, Optional<String> outcome)
    {
        public Investigated
        {
            ContextLimits.text(entryId, "investigated note", 1200);
            ContextLimits.text(title, "investigated title", 200);
            ContextLimits.text(statement, "disputed statement", 500);
            requireNonNull(outcome, "outcome is null");
        }
    }

    /** The spec check a run worked for, and the candidate it reproduced; its planning turn reproduces none. */
    public record Checked(String checkId, String topicTitle, Optional<String> reproduced)
    {
        public Checked
        {
            ContextLimits.text(checkId, "check id", 100);
            ContextLimits.text(topicTitle, "topic title", 200);
            requireNonNull(reproduced, "reproduced is null");
        }
    }

    public enum Outcome
    {
        RUNNING,
        SUBMITTED,
        SETTLED,
        ANSWERED,
        CORRECTED,
        NO_NOTE,
        UNFINISHED,
        LIMIT,
        STOPPED,
        FAILED
    }
}
