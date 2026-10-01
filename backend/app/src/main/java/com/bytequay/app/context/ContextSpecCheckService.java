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

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.app.context.ContextSpecCheck.Candidate;
import com.bytequay.app.context.ContextSpecCheck.Draft;
import com.bytequay.app.context.ContextSpecCheck.Duplicate;
import com.bytequay.app.context.ContextSpecCheck.Status;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.repository.github.GitHubSearchResponse;
import com.bytequay.app.review.MavenCommands;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.context.ContextEntryKind;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextSourceEvidence;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryLabel;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * Spec checks: which specifications a topic's code implements, and checks that save candidate gaps and reproduce each with a test.
 * The user starts a check and reads its report; the turns in between run without asking.
 */
@Service
public class ContextSpecCheckService
{
    private static final Logger log = LoggerFactory.getLogger(ContextSpecCheckService.class);
    private static final Pattern LOCATION = Pattern.compile("([^\\s:]+):([1-9][0-9]{0,6})(?:-([1-9][0-9]{0,6}))?");
    // ponytail: build-tool words for "the test never ran"; a truncated output can hide them, and other ecosystems need their own.
    private static final List<String> NOT_RUN = ImmutableList.of("COMPILATION ERROR", "Compilation failure", "Compilation failed", "No tests were executed",
            "No tests matching", "No tests found");
    private static final int MAX_RECORDS = 4000;
    private final ContextLearningPlanStore plans;
    private final WorkspaceRepositoryService workspaces;
    private final GitCommandRunner git;
    private final ContextSpecCheckStore checks;
    private final ContextBuildService builds;
    private final ContextBuildStore buildStore;
    private final ContextLearningService learning;
    private final RepositoryContextService notebooks;
    private final GitHubRepoClient github;
    private final CredentialService credentials;
    private final AgentTaskStore tasks;
    private final TaskLogStore logs;
    private final WorktreeService worktrees;
    private final Clock clock;

    public ContextSpecCheckService(ContextLearningPlanStore plans, WorkspaceRepositoryService workspaces, GitCommandRunner git, ContextSpecCheckStore checks,
            ContextBuildService builds, ContextBuildStore buildStore, ContextLearningService learning, RepositoryContextService notebooks, GitHubRepoClient github,
            CredentialService credentials, AgentTaskStore tasks, TaskLogStore logs, WorktreeService worktrees, Clock clock)
    {
        this.plans = requireNonNull(plans, "plans is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
        this.checks = requireNonNull(checks, "checks is null");
        this.builds = requireNonNull(builds, "builds is null");
        this.buildStore = requireNonNull(buildStore, "buildStore is null");
        this.learning = requireNonNull(learning, "learning is null");
        this.notebooks = requireNonNull(notebooks, "notebooks is null");
        this.github = requireNonNull(github, "github is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.logs = requireNonNull(logs, "logs is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** The documents the topic's code links at the plan's baseline, and its approved documentation. A local read only. */
    public List<ContextSpecSource> sources(String workspaceId, String topicId)
    {
        ContextLearningPlan plan = plans.find(workspaceId).orElseThrow(() -> new IllegalArgumentException("This repository has no learning plan"));
        return sources(workspaceId, plan, plan.topic(topicId));
    }

    private List<ContextSpecSource> sources(String workspaceId, ContextLearningPlan plan, ContextLearningPlan.Topic topic)
    {
        List<String> approved = plan.documentationSources().stream().filter(source -> topic.sourceIds().contains(source.id()))
                .map(RegisteredContextSource::url).toList();
        return ContextSpecSource.scan(git, workspaces.requireReadableRepository(workspaceId), plan.baselineSha(), topic.paths(), approved);
    }

    public List<ContextSpecCheck> checks(String workspaceId)
    {
        return checks.find(workspaceId);
    }

    /**
     * Starts one check of a topic at the monitored branch head: a planning turn, then a reproduction turn per candidate. A topic's
     * earlier findings are kept, and its covered and next sections tell the new check where to continue.
     */
    public Started start(String workspaceId, StartRequest request)
    {
        ContextLearningPlan plan = plans.find(workspaceId).filter(value -> value.id().equals(request.planId()))
                .orElseThrow(() -> new IllegalArgumentException("The learning plan changed; reload it"));
        checkArgument(plan.batch().isEmpty(), "Learning is running; let it finish or stop it before checking against spec");
        checkArgument(checks.active(workspaceId).isEmpty(), "A spec check is already running");
        ContextLearningPlan.Topic topic = plan.topic(request.topicId());
        ContextBuildService.Baseline baseline = builds.baseline(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("The monitored branch is unavailable; refresh the repository"));
        List<ContextSpecSource> found = sources(workspaceId, plan, topic);
        Optional<ContextSpecCheck> previous = checks.find(workspaceId).stream().filter(check -> check.topicId().equals(topic.id())).findFirst();
        Optional<ContextSpecCheck> earlier = previous.filter(check -> check.planId().equals(plan.id()));
        ContextSpecCheck check = checks.save(workspaceId, previous, new ContextSpecCheck(UUID.randomUUID().toString(), plan.id(), topic.id(), topic.title(),
                topic.scopeId(), topic.paths(), baseline.sha(), request.requestKey(), request.specs(), ImmutableList.of(), labels(plan.repository()),
                ContextSpecCheck.State.CHECKING,
                earlier.map(ContextSpecCheck::kept).orElse(ImmutableList.of()), earlier.map(ContextSpecCheck::covered).orElse(ImmutableList.of()),
                earlier.map(ContextSpecCheck::next).orElse(ImmutableList.of()), 0, Optional.empty(), Instant.now(clock), Optional.empty()));
        try {
            ContextBuildService.View view = builds.check(workspaceId, new ContextBuildService.Request(request.requestKey(), request.expectedRevision(),
                    request.expectedBuildSequence(), ImmutableList.of(check.scopeId()), ImmutableList.of()), check.baselineSha(),
                    new ContextBuild.Check(check.id(), check.topicTitle(), Optional.empty(), planningBrief(check, earlier, found)));
            return new Started(checks.find(workspaceId, check.id()).orElseThrow(), view);
        }
        catch (RuntimeException failure) {
            checks.find(workspaceId, check.id()).filter(saved -> saved.state().active()).ifPresent(saved -> checks.save(workspaceId, Optional.of(saved),
                    saved.ended(ContextSpecCheck.State.FAILED, Optional.of(ContextText.excerpt("The check could not start: " + message(failure), 1000)),
                            Instant.now(clock))));
            throw failure;
        }
    }

    /** Repository labels a draft may use; drafts go without labels when GitHub cannot list them. */
    private List<String> labels(RepoRef repository)
    {
        try {
            return github.fetchLabels(credentials.resolveGitHubCredential().token(), repository).stream().map(RepositoryLabel::name)
                    .filter(name -> ContextLimits.bytes(name) <= 100).distinct().limit(300).toList();
        }
        catch (RuntimeException unavailable) {
            log.debug("Spec check drafts go without labels for {}: {}", repository.fullName(), unavailable.toString());
            return ImmutableList.of();
        }
    }

    /**
     * Settles the turn that ended and reproduces the next candidate as another turn of the same task, or of a fresh one when the task
     * could not be kept. The coordinator calls this after every turn; a turn still running changes nothing.
     */
    public void continueCheck(String workspaceId)
    {
        Optional<ContextSpecCheck> active = checks.active(workspaceId);
        if (active.isEmpty()) {
            return;
        }
        ContextSpecCheck check = active.orElseThrow();
        Optional<ContextBuild> build = buildStore.find(workspaceId).filter(value -> value.check().filter(bound -> bound.checkId().equals(check.id())).isPresent());
        if (build.isEmpty()) {
            end(workspaceId, check, ContextSpecCheck.State.FAILED, Optional.of("Another learning request replaced the check's task"));
            return;
        }
        ContextBuildService.View view = builds.get(workspaceId);
        Optional<ContextRun> run = view.runs().stream().filter(value -> value.sequence() == build.orElseThrow().sequence()).findFirst();
        if (run.isEmpty() || run.orElseThrow().outcome() == ContextRun.Outcome.RUNNING) {
            return;
        }
        ContextSpecCheck current = checks.find(workspaceId, check.id()).orElseThrow();
        if (!current.state().active()) {
            return;
        }
        ContextRun ended = run.orElseThrow();
        if (ended.outcome() == ContextRun.Outcome.STOPPED) {
            end(workspaceId, current, ContextSpecCheck.State.STOPPED, Optional.of("Stopped"));
            return;
        }
        Optional<ContextBuild.Reproduction> reproduced = build.orElseThrow().check().orElseThrow().reproduction();
        if (reproduced.isEmpty() && ended.outcome() != ContextRun.Outcome.SUBMITTED) {
            end(workspaceId, current, ContextSpecCheck.State.FAILED, Optional.of(ContextText.excerpt("The check turn ended without saving candidates"
                    + ended.detail().map(detail -> ": " + detail).orElse("."), 1000)));
            return;
        }
        if (reproduced.isPresent() && current.candidate(reproduced.orElseThrow().candidateId()).status() == Status.REPRODUCING) {
            Candidate unfinished = current.candidate(reproduced.orElseThrow().candidateId());
            current = checks.save(workspaceId, Optional.of(current), current.withCandidate(unfinished.withResult(Status.NOT_REPRODUCED, Optional.empty(),
                    Optional.of(ContextText.excerpt("The reproduction turn ended without a result" + ended.detail().map(detail -> ": " + detail).orElse("."), 2000)),
                    Optional.empty())));
        }
        Optional<Candidate> next = current.nextToReproduce();
        if (next.isEmpty()) {
            end(workspaceId, current, ContextSpecCheck.State.DONE, Optional.empty());
            return;
        }
        Optional<ContextBuildService.TaskView> task = view.build().flatMap(ContextBuildService.BuildView::task);
        boolean kept = task.filter(value -> value.state().equals("OPEN") && value.mutationClaim().isEmpty()).isPresent();
        if (!kept && !view.canStart()) {
            // An ended task whose worktree could not be removed holds the workspace until the user cleans it up.
            if (task.filter(value -> !value.state().equals("OPEN") && value.worktreeRemovedAt().isEmpty()).isPresent()) {
                end(workspaceId, current, ContextSpecCheck.State.FAILED, Optional.of("The check's task ended with its worktree kept; clean it up in Tasks"));
            }
            return;
        }
        ContextSpecCheck reproducing = checks.save(workspaceId, Optional.of(current), current.reproducing(next.orElseThrow()));
        try {
            builds.check(workspaceId, new ContextBuildService.Request(reproducing.requestKey() + ":" + reproducing.reproductions(),
                    notebooks.getNotebook(workspaceId).revision(), view.buildRequestSequence(), ImmutableList.of(reproducing.scopeId()), ImmutableList.of()),
                    reproducing.baselineSha(), new ContextBuild.Check(reproducing.id(), reproducing.topicTitle(),
                            Optional.of(new ContextBuild.Reproduction(next.orElseThrow().id(), next.orElseThrow().title())),
                            reproductionBrief(reproducing, next.orElseThrow())));
        }
        catch (RuntimeException failure) {
            checks.find(workspaceId, reproducing.id()).filter(saved -> saved.state().active()).ifPresent(saved -> end(workspaceId, saved,
                    ContextSpecCheck.State.FAILED, Optional.of(ContextText.excerpt("The check could not continue: " + message(failure), 1000))));
            throw failure;
        }
    }

    private void end(String workspaceId, ContextSpecCheck check, ContextSpecCheck.State state, Optional<String> why)
    {
        checks.save(workspaceId, Optional.of(check), check.ended(state, why, Instant.now(clock)));
    }

    /** Hides a finding from the report and records why; nothing is sent to GitHub. */
    public ContextSpecCheck dismiss(String workspaceId, String checkId, String candidateId, String reason)
    {
        ContextLimits.text(reason, "dismissal", 500);
        ContextSpecCheck check = checks.find(workspaceId, checkId).orElseThrow(() -> new IllegalArgumentException("Unknown spec check"));
        return checks.save(workspaceId, Optional.of(check), check.withCandidate(check.candidate(candidateId).withDismissed(reason.strip())));
    }

    /** Issues and pull requests in any state that may already report a gap; the tool records each so a candidate can cite it. */
    public List<Report> searchReports(ContextBuild build, String words)
    {
        requireCheck(build, false);
        GitHubSearchResponse found = github.searchReports(credentials.resolveGitHubCredential().token(), build.repository(), words);
        return Optional.ofNullable(found.items()).orElse(ImmutableList.of()).stream().limit(10).map(item -> new Report(item.number(),
                ContextText.excerpt(Optional.ofNullable(item.title()).orElse("#" + item.number()), 500), state(item),
                item.pullRequest() == null ? "issue" : "pull request", Optional.ofNullable(item.htmlUrl()).orElse(""),
                Optional.ofNullable(item.labels()).orElse(ImmutableList.of()).stream().map(GitHubSearchResponse.Label::name).limit(10).toList(),
                ContextText.excerpt(Optional.ofNullable(item.body()).orElse(""), 800))).toList();
    }

    private static String state(GitHubSearchResponse.Item item)
    {
        if (item.pullRequest() != null && item.pullRequest().mergedAt() != null) {
            return "merged";
        }
        return Optional.ofNullable(item.state()).map(value -> value.toLowerCase(Locale.ROOT)).orElse("closed");
    }

    /**
     * Adds the planning turn's candidates, keeping each title once, and replaces the specs it read, and the covered and next sections,
     * when it passes them. A duplicate must be a report this turn's search returned: an open one is already reported and never
     * reproduced, a closed one is reproduced.
     */
    public Saved saveCandidates(ContextBuild build, List<Found> found, Optional<List<String>> checked, List<String> covered, List<String> next,
            List<Question> questions, IntFunction<Optional<Duplicate>> searched)
    {
        ContextSpecCheck check = requireCheck(build, false);
        checked.ifPresent(specs -> specs.forEach(spec -> checkArgument(check.specs().contains(spec) || build.capture().attempts().containsKey("page:" + spec),
                "specs: %s is not a page of this check or a page read in this turn", spec)));
        checkArgument(found.size() <= 20, "Save at most 20 candidates per call");
        List<Candidate> candidates = new ArrayList<>(check.candidates());
        int number = candidates.stream().mapToInt(candidate -> Integer.parseInt(candidate.id().substring(1))).max().orElse(0);
        List<String> saved = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Found value : found) {
            if (candidates.stream().anyMatch(candidate -> candidate.title().equalsIgnoreCase(value.title().strip()))) {
                skipped.add(value.title());
                continue;
            }
            String address = value.specUrl().contains("#") ? value.specUrl().substring(0, value.specUrl().indexOf('#')) : value.specUrl();
            checkArgument(check.specs().contains(address) || build.capture().attempts().containsKey("page:" + address),
                    "specUrl: %s is not a spec of this check or a page read in this turn", address);
            value.code().forEach(location -> location(location));
            checkArgument(check.paths().isEmpty() || value.code().stream().map(location -> location(location).path()).anyMatch(path ->
                            check.paths().stream().anyMatch(path::startsWith)),
                    "code: cite at least one location in the topic's paths %s; gaps in other code go to questions", check.paths());
            Optional<Duplicate> duplicate = value.duplicate().map(issue -> searched.apply(issue)
                    .orElseThrow(() -> new IllegalArgumentException("duplicate: #" + issue + " was not returned by this turn's report search")));
            number++;
            candidates.add(new Candidate("c" + number, value.predicted().type(), value.predicted(), value.title().strip(), value.rule(), value.specUrl(),
                    value.code(), value.reasoning(), value.severity(), duplicate, duplicate.filter(Duplicate::open).isPresent() ? Status.ALREADY_REPORTED : Status.PENDING,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
            saved.add("c" + number);
        }
        checkArgument(candidates.size() <= ContextSpecCheck.MAX_CANDIDATES, "A check keeps at most %s candidates; save the most severe",
                ContextSpecCheck.MAX_CANDIDATES);
        ContextSpecCheck updated = checks.save(build.workspaceId(), Optional.of(check), check.withCandidates(candidates, checked.orElse(check.checkedAgainst()),
                covered.isEmpty() ? check.covered() : covered, next.isEmpty() ? check.next() : next));
        List<String> asked = questions.stream().map(question -> {
            try {
                ContextLearningService.Asked answer = learning.ask(build.workspaceId(), new ContextLearningService.Ask(question.question(), question.paths(),
                        Optional.empty(), ContextLearningPlan.Origin.TASK));
                return answer.status() + ": " + question.question();
            }
            catch (RuntimeException failure) {
                return "NOT_ADDED: " + question.question() + " (" + message(failure) + ")";
            }
        }).toList();
        return new Saved(saved, skipped, (int) updated.candidates().stream().filter(candidate -> candidate.status() == Status.PENDING).count(),
                (int) updated.candidates().stream().filter(candidate -> candidate.status() == Status.ALREADY_REPORTED).count(), asked);
    }

    /**
     * Records one reproduction. A confirmed or refuted result names the test file and the command that ran it in this turn; ByteQuay
     * takes that command's exit code and output from the provider's events. A confirmed gap's issue draft is composed from them.
     */
    public Candidate saveResult(ContextBuild build, TurnId turn, Result result)
    {
        ContextSpecCheck check = requireCheck(build, true);
        Candidate candidate = check.candidate(build.check().orElseThrow().reproduction().orElseThrow().candidateId());
        checkArgument(candidate.status() == Status.REPRODUCING, "This candidate already has its result");
        checkArgument(result.outcome() == Status.CONFIRMED || result.outcome() == Status.REFUTED || result.outcome() == Status.NOT_REPRODUCED,
                "outcome: use CONFIRMED, REFUTED or NOT_REPRODUCED");
        checkArgument(result.outcome() == Status.NOT_REPRODUCED || result.testPath().isPresent() && result.command().isPresent(),
                "A confirmed or refuted result passes testPath and the command that ran the test");
        checkArgument(result.outcome() == Status.REFUTED || result.note().isEmpty(), "Only a refuted candidate saves a note");
        Optional<ContextSpecCheck.Test> test = result.command().map(command -> test(build, turn, result.testPath()
                .orElseThrow(() -> new IllegalArgumentException("testPath: name the test the command ran")), command));
        test.ifPresent(ran -> {
            if (result.outcome() == Status.CONFIRMED) {
                checkArgument(ran.exitCode() != 0, "The test passed (exit 0): report REFUTED, or make the test assert what the spec requires");
                checkArgument(NOT_RUN.stream().noneMatch(ran.output()::contains),
                        "The command failed before the test ran; fix the test, or report NOT_REPRODUCED with the reason");
            }
            if (result.outcome() == Status.REFUTED) {
                checkArgument(ran.exitCode() == 0, "The test failed (exit %s): report CONFIRMED when its failure shows the gap, otherwise NOT_REPRODUCED",
                        ran.exitCode());
            }
        });
        Optional<Draft> draft = Optional.empty();
        if (result.outcome() == Status.CONFIRMED) {
            checkArgument(result.summary().isPresent() && result.impact().isPresent() && result.proposal().isPresent(),
                    "A confirmed gap writes its issue: summary, impact and proposal");
            List<String> unknown = result.labels().stream().filter(label -> !check.labels().contains(label)).toList();
            checkArgument(unknown.isEmpty(), "labels: %s are not labels of this repository", unknown);
            draft = Optional.of(new Draft(result.title().orElse(candidate.title()), body(build.repository(), check, candidate, test.orElseThrow(),
                    result.summary().orElseThrow(), result.impact().orElseThrow(), result.proposal().orElseThrow()), result.labels()));
        }
        result.note().ifPresent(note -> saveNote(build, turn, check, candidate, note));
        Candidate settled = candidate.withResult(result.outcome(), test, Optional.of(result.reason()), draft);
        checks.save(build.workspaceId(), Optional.of(check), check.withCandidate(settled));
        return settled;
    }

    /** The refuted rule as a note: the code follows the spec here, citing the lines that show it. */
    private void saveNote(ContextBuild build, TurnId turn, ContextSpecCheck check, Candidate candidate, Note note)
    {
        String operation = "task:" + digest(build.taskId() + "\n" + turn + "\n" + candidate.id()).substring(0, 32) + ":"
                + digest(check.id() + "\n" + candidate.id()).substring(0, 32);
        notebooks.saveTaskNote(build.workspaceId(), notebooks.getNotebook(build.workspaceId()).revision(), new RepositoryContextService.TaskNote(operation,
                build.taskId(), ContextEntryKind.KNOWLEDGE, check.scopeId(), Optional.empty(), Optional.empty(), note.title(), note.summary(), note.body(),
                build.baselineSha(), note.evidence()));
    }

    /** The command this turn ran, as the provider recorded it, and the test file it ran from the task's worktree. */
    private ContextSpecCheck.Test test(ContextBuild build, TurnId turn, String path, String command)
    {
        List<String> words = MavenCommands.loneCommand(ImmutableList.of(command))
                .orElseThrow(() -> new IllegalArgumentException("command: run the test as one command, without cd, pipes, redirects or &&"));
        String file = path.substring(path.lastIndexOf('/') + 1);
        String stem = file.contains(".") ? file.substring(0, file.indexOf('.')) : file;
        String directory = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        checkArgument(words.stream().anyMatch(word -> word.contains(stem) || !directory.isEmpty() && word.contains(directory)),
                "command: it does not name the test in %s", path);
        AgentEventPayload.CommandActivity ran = logs.findLatest(TaskId.valueOf(build.taskId()), envelope -> ranCommand(envelope, turn, words), MAX_RECORDS)
                .map(envelope -> (AgentEventPayload.CommandActivity) envelope.payload())
                .orElseThrow(() -> new IllegalArgumentException("command: this turn ran no such command to completion; run the test, then pass the command "
                        + "exactly as you ran it"));
        return new ContextSpecCheck.Test(path, String.join(" ", words), ran.exitCode().orElseThrow(),
                tail(ran.output().orElse(""), ContextSpecCheck.Test.MAX_OUTPUT_BYTES), source(build, path));
    }

    private static boolean ranCommand(AgentEventEnvelope envelope, TurnId turn, List<String> words)
    {
        return envelope.turnId().filter(turn::equals).isPresent() && envelope.payload() instanceof AgentEventPayload.CommandActivity command
                && (command.state() == ProviderAgentEvent.ActivityState.COMPLETED || command.state() == ProviderAgentEvent.ActivityState.FAILED)
                && command.exitCode().isPresent() && !command.background() && MavenCommands.loneCommand(command.argv()).filter(words::equals).isPresent();
    }

    /** The test file as it is in the task's own worktree, the first 12 KiB of it. */
    private String source(ContextBuild build, String path)
    {
        ContextLimits.pathPrefix(path + "/");
        AgentTask task = tasks.find(TaskId.valueOf(build.taskId())).orElseThrow(() -> new IllegalArgumentException("The check's task is unavailable"));
        Path checkout = worktrees.ownedCheckout(task.taskWorktree().orElseThrow(() -> new IllegalArgumentException("The check's task has no worktree")));
        Path file = checkout.resolve(path).normalize();
        checkArgument(file.startsWith(checkout) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "testPath: %s is not a file in the worktree", path);
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] head = input.readNBytes(ContextSpecCheck.Test.MAX_SOURCE_BYTES + 1);
            return head.length <= ContextSpecCheck.Test.MAX_SOURCE_BYTES ? new String(head, UTF_8)
                    : ContextText.excerpt(new String(head, UTF_8), ContextSpecCheck.Test.MAX_SOURCE_BYTES);
        }
        catch (IOException failure) {
            throw new IllegalArgumentException("testPath: " + path + " cannot be read", failure);
        }
    }

    /** The end of a command's output, where build tools summarize failures, within the byte limit. */
    static String tail(String text, int maximumBytes)
    {
        byte[] bytes = text.getBytes(UTF_8);
        if (bytes.length <= maximumBytes) {
            return text;
        }
        int start = bytes.length - (maximumBytes - 4);
        while (start < bytes.length && (bytes[start] & 0xC0) == 0x80) {
            start++;
        }
        return "…\n" + new String(bytes, start, bytes.length - start, UTF_8);
    }

    /** A bug, feature request or improvement as its reader needs it: the rule, the code, the failing test and what to change. */
    static String body(RepoRef repository, ContextSpecCheck check, Candidate candidate, ContextSpecCheck.Test test, String summary, String impact, String proposal)
    {
        String code = candidate.code().stream().map(location -> {
            Location parsed = location(location);
            return "- [`" + location + "`](https://github.com/" + repository.fullName() + "/blob/" + check.baselineSha() + "/" + parsed.path() + "#L" + parsed.start()
                    + parsed.end().map(end -> "-L" + end).orElse("") + ")";
        }).collect(Collectors.joining("\n"));
        String effect = switch (candidate.type()) {
            case BUG -> "Impact";
            case FEATURE_REQUEST -> "Who needs it";
            case IMPROVEMENT -> "Better behavior";
        };
        String change = switch (candidate.type()) {
            case BUG -> "Proposed fix";
            case FEATURE_REQUEST -> "Proposed scope";
            case IMPROVEMENT -> "Proposed change";
        };
        String body = summary.strip() + "\n\n### Specification\n\n" + candidate.rule().strip().lines().map(line -> "> " + line).collect(Collectors.joining("\n"))
                + "\n\n" + candidate.specUrl() + "\n\n### Current behavior\n\n" + code + "\n\n### " + effect + "\n\n" + impact.strip()
                + "\n\n### Reproduction\n\n`" + test.path() + "`:\n\n" + fenced(test.source(), language(test.path())) + "\n\n`" + test.command() + "` exits with "
                + test.exitCode() + ":\n\n" + fenced(test.output(), "") + "\n\n### " + change + "\n\n" + proposal.strip() + "\n";
        return ContextLimits.bytes(body) <= Draft.MAX_BODY_BYTES ? body : ContextText.excerpt(body, Draft.MAX_BODY_BYTES);
    }

    /** A fence longer than any backtick run in the text, so the text cannot close it. */
    private static String fenced(String text, String language)
    {
        int longest = 0;
        Matcher run = Pattern.compile("`+").matcher(text);
        while (run.find()) {
            longest = Math.max(longest, run.group().length());
        }
        String fence = "`".repeat(Math.max(3, longest + 1));
        return fence + language + "\n" + text.stripTrailing() + "\n" + fence;
    }

    private static String language(String path)
    {
        String extension = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        return switch (extension) {
            case "java", "scala", "go", "c", "cpp", "sql" -> extension;
            case "kt" -> "kotlin";
            case "py" -> "python";
            case "rs" -> "rust";
            case "ts", "js" -> extension.equals("ts") ? "typescript" : "javascript";
            default -> "";
        };
    }

    record Location(String path, int start, Optional<Integer> end) {}

    static Location location(String location)
    {
        Matcher matcher = LOCATION.matcher(location);
        checkArgument(matcher.matches(), "code: write each location as path:start-end, got %s", location);
        ContextLimits.pathPrefix(matcher.group(1) + "/");
        int start = Integer.parseInt(matcher.group(2));
        Optional<Integer> end = Optional.ofNullable(matcher.group(3)).map(Integer::parseInt);
        checkArgument(end.orElse(start) >= start, "code: %s ends before it starts", location);
        return new Location(matcher.group(1), start, end);
    }

    /** The check a turn works for, which must still be running; a planning turn reproduces nothing and a reproduction turn plans nothing. */
    private ContextSpecCheck requireCheck(ContextBuild build, boolean reproduction)
    {
        ContextBuild.Check bound = build.check().orElseThrow(() -> new IllegalArgumentException("This turn does not check a spec"));
        checkArgument(bound.reproduction().isPresent() == reproduction, reproduction ? "This turn plans the check; it reproduces nothing"
                : "This turn reproduces one candidate; it saves no candidates or searches");
        ContextSpecCheck check = checks.find(build.workspaceId(), bound.checkId()).orElseThrow(() -> new IllegalArgumentException("The spec check was removed"));
        checkArgument(check.state().active(), "The spec check has ended");
        return check;
    }

    /**
     * What a planning turn starts from: the pages to find the specs among, the reference code, the topic's paths, and where an earlier
     * check stopped, which specs it used and what it kept.
     */
    static String planningBrief(ContextSpecCheck check, Optional<ContextSpecCheck> earlier, List<ContextSpecSource> found)
    {
        StringBuilder brief = new StringBuilder("Pages the topic's code links, most cited first; decide which are specifications the code implements:\n");
        StringBuilder reference = new StringBuilder();
        for (String spec : check.specs()) {
            Optional<ContextSpecSource> scanned = found.stream().filter(source -> source.url().equals(spec)).findFirst();
            String line = "- " + spec + scanned.map(source -> (source.links() > 0 ? " · cited " + source.links() + (source.links() == 1 ? " time" : " times") : "")
                    + (source.approved() ? " · approved documentation of the topic" : "")
                    + (source.sections().isEmpty() ? "" : " · sections the code links: #" + String.join(", #", source.sections()))).orElse(" · added by the user") + "\n";
            if (scanned.filter(source -> source.kind() == ContextSpecSource.Kind.REFERENCE_CODE).isPresent()) {
                reference.append(line);
            }
            else {
                brief.append(line);
            }
        }
        if (!reference.isEmpty()) {
            brief.append("Reference implementations in another engine, for comparison only and never rules:\n").append(reference);
        }
        earlier.filter(value -> !value.checkedAgainst().isEmpty())
                .ifPresent(value -> brief.append("The last check used these as specifications: ").append(String.join(", ", value.checkedAgainst())).append('\n'));
        brief.append("Topic paths: ").append(check.paths().isEmpty() ? "the whole repository" : String.join(", ", check.paths())).append('\n');
        if (!check.covered().isEmpty()) {
            brief.append("An earlier check covered: ").append(String.join("; ", check.covered())).append('\n');
        }
        if (!check.next().isEmpty()) {
            brief.append("Continue with: ").append(String.join("; ", check.next())).append('\n');
        }
        if (!check.candidates().isEmpty()) {
            brief.append("Kept from earlier checks; do not save these again:\n");
            check.candidates().forEach(candidate -> brief.append("- ").append(candidate.status()).append(": ").append(candidate.title()).append('\n'));
        }
        return ContextText.excerpt(brief.toString(), ContextBuild.Check.MAX_BRIEF_BYTES);
    }

    /** What a reproduction turn starts from: the one candidate, its prediction and the repository labels a draft may use. */
    static String reproductionBrief(ContextSpecCheck check, Candidate candidate)
    {
        String brief = "Candidate " + candidate.id() + ": " + candidate.title() + "\n"
                + "Predicted " + candidate.predicted() + ", so a confirmed gap files a " + candidate.type().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                + "; severity " + candidate.severity() + "\n"
                + "Rule: \"" + candidate.rule() + "\"\n"
                + "Spec: " + candidate.specUrl() + "\n"
                + "Code: " + String.join(", ", candidate.code()) + "\n"
                + "Reasoning: " + candidate.reasoning() + "\n"
                + candidate.duplicate().map(duplicate -> "A " + duplicate.state() + " report may cover it: #" + duplicate.number() + " " + duplicate.title()
                        + "; the gap may have come back.\n").orElse("")
                + "Repository labels: " + (check.labels().isEmpty() ? "none available" : String.join(", ", check.labels())) + "\n";
        return ContextText.excerpt(brief, ContextBuild.Check.MAX_BRIEF_BYTES);
    }

    private static String digest(String value)
    {
        return Hashing.sha256().hashString(value, UTF_8).toString();
    }

    private static String message(RuntimeException failure)
    {
        return Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName());
    }

    public record StartRequest(String requestKey, long expectedRevision, long expectedBuildSequence, String planId, String topicId, List<String> specs)
    {
        public StartRequest
        {
            ContextLimits.text(requestKey, "requestKey", 90);
            checkArgument(expectedRevision >= 0 && expectedBuildSequence >= 0, "Revisions must not be negative");
            ContextLimits.text(planId, "planId", 200);
            ContextLimits.text(topicId, "topicId", 200);
            specs = ImmutableList.copyOf(specs);
            checkArgument(!specs.isEmpty(), "Select at least one spec");
            ContextLimits.unique(specs, "specs", ContextSpecCheck.MAX_SPECS);
            specs.forEach(ContextLimits::documentationUrl);
        }
    }

    public record Started(ContextSpecCheck check, ContextBuildService.View build) {}

    /** A search result a candidate may cite as its duplicate. */
    public record Report(int number, String title, String state, String kind, String url, List<String> labels, String excerpt) {}

    /** One candidate as the planning turn found it; ByteQuay derives its type from the predicted outcome. */
    public record Found(ContextSpecCheck.Predicted predicted, String title, String rule, String specUrl, List<String> code, String reasoning, int severity,
            Optional<Integer> duplicate) {}

    /** A rule the topic's code cannot settle, filed as a plan question. */
    public record Question(String question, List<String> paths) {}

    public record Saved(List<String> saved, List<String> skipped, int pending, int alreadyReported, List<String> questions) {}

    public record Result(Status outcome, String reason, Optional<String> testPath, Optional<String> command, Optional<String> title, Optional<String> summary,
            Optional<String> impact, Optional<String> proposal, List<String> labels, Optional<Note> note) {}

    /** A refuted rule's note, with the FILE reads that show the code follows it. */
    public record Note(String title, String summary, String body, List<ContextSourceEvidence> evidence) {}
}
