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
package com.bytequay.app.service.harness;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.harness.HarnessModels.Failure;
import com.bytequay.app.service.review.CliReviewRunner;
import com.bytequay.app.service.settings.AiDefaultsService;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.WorkspaceEngineSettings;
import com.bytequay.app.service.workspaces.SessionKnowledgeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/**
 * Drives one round of the CI loop. The agent is handed every failure the round
 * produced and decides what to take on; it edits, commits its fixups and says
 * when it is done. The program pushes afterwards and waits for the next CI run.
 *
 * <p>It is the same session that made the picks, resumed — so "the compile broke
 * because of how I resolved commit 37" is a thought it can actually have. The
 * program never re-reads the round for it, never scores its confidence and never
 * bounds its attempts; see "The upstream sync run" in
 * {@code docs/intermediate/ci-autofix-design.md}.
 */
@Component
public class HarnessRepairAgent
{
    /** The agent ends its turn with one of these on the last line. */
    private static final String COMMITTED = "COMMITTED:";
    private static final String NOTHING = "NOTHING:";
    private static final String PARKED = "PARKED:";
    private static final int MAX_DETAIL = 500;
    private static final int MAX_EXCERPT = 6_000;
    private static final int MAX_FAILURES = 40;
    private static final int MAX_LEARNED = 5;
    private static final int MAX_TITLE = 200;
    private static final int MAX_BODY = 8_000;
    private static final Pattern LEARNED_BLOCK = Pattern.compile(
            "<learned\\s+title=\"([^\"]{1,300})\"\\s*>(.*?)</learned>", Pattern.DOTALL);
    private static final Logger log = LoggerFactory.getLogger(HarnessRepairAgent.class);

    private final CliReviewRunner cli;
    private final WorkspaceEngineSettings engines;
    private final AiDefaultsService aiDefaults;
    private final SessionKnowledgeProvider knowledge;

    public HarnessRepairAgent(
            CliReviewRunner cli,
            WorkspaceEngineSettings engines,
            AiDefaultsService aiDefaults,
            SessionKnowledgeProvider knowledge)
    {
        this.cli = requireNonNull(cli, "cli is null");
        this.engines = requireNonNull(engines, "engines is null");
        this.aiDefaults = requireNonNull(aiDefaults, "aiDefaults is null");
        this.knowledge = requireNonNull(knowledge, "knowledge is null");
    }

    // ponytail: duplicated from ConflictRepairAgent — 12 lines and one enum
    // constant. Extract a shared resolver when a third agent needs it, not now.
    WorkModel engineFor(String workspaceId)
    {
        return engines.forAudience(workspaceId, SessionAudience.CI_FIX)
                .map(WorkspaceEngineSettings.Engine::model)
                .or(() -> WorkspaceEngineSettings.parseChoice(aiDefaults.get().ciFix()))
                .orElseGet(() -> new WorkModel(WorkModelKind.CLI, "codex", null, null));
    }

    static CliReviewRunner.Provider cliProvider(String agent)
    {
        return switch (agent) {
            case "claude-code", "claude-cli", "claude" -> CliReviewRunner.Provider.CLAUDE;
            case "codex", "codex-cli" -> CliReviewRunner.Provider.CODEX;
            default -> throw new IllegalStateException("unknown CLI agent: " + agent);
        };
    }

    public Outcome fix(
            Path worktree,
            String workspaceId,
            List<Failure> failures,
            long budgetMilliUsd,
            String resumeSessionId,
            String steeringText)
    {
        requireNonNull(worktree, "worktree is null");
        WorkModel engine = engineFor(workspaceId);
        if (engine.kind() != WorkModelKind.CLI) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "the CI loop needs a CLI agent; this workspace's CI-fix engine is "
                            + engine.agentOrProvider());
        }
        boolean resuming = resumeSessionId != null && !resumeSessionId.isBlank();
        String prompt = (resuming ? "" : systemPrompt() + "\n\n")
                + knowledge(workspaceId, failures)
                + userPrompt(failures, steeringText, resuming);
        CliReviewRunner.Result result = cli.run(
                cliProvider(engine.agentOrProvider()), prompt, resumeSessionId, worktree, null,
                toIntExact(Math.max(1, budgetMilliUsd / 10)),
                CliReviewRunner.Sandbox.WRITE);
        return read(result.text(), result.costUsdMilli(), result.sessionId());
    }

    /**
     * The run's last turn, after a human merged it. Everything worth keeping from
     * this range gets written down now or not at all: the worktree is about to go,
     * and the session that knows what was tried and rejected goes with it.
     *
     * @return the entries it wrote; the caller persists them
     */
    public Outcome retrospective(
            Path worktree, String workspaceId, Integer prNumber,
            long budgetMilliUsd, String resumeSessionId)
    {
        requireNonNull(worktree, "worktree is null");
        WorkModel engine = engineFor(workspaceId);
        if (engine.kind() != WorkModelKind.CLI) {
            return new Outcome(false, true, "no CLI agent to write a retrospective", 0, null);
        }
        String prompt = RETROSPECTIVE_PROMPT
                + (prNumber == null ? "" : "\n\nThe merged pull request was #" + prNumber + ".");
        CliReviewRunner.Result result = cli.run(
                cliProvider(engine.agentOrProvider()), prompt, resumeSessionId, worktree, null,
                toIntExact(Math.max(1, budgetMilliUsd / 10)),
                CliReviewRunner.Sandbox.WRITE);
        return read(result.text(), result.costUsdMilli(), result.sessionId());
    }

    /**
     * The base moved and the branch no longer merges. Same session, same commit
     * shape rules — only the job is different, so the prompt is too.
     */
    public Outcome rebaseOntoBase(
            Path worktree, String workspaceId, long budgetMilliUsd, String resumeSessionId)
    {
        requireNonNull(worktree, "worktree is null");
        WorkModel engine = engineFor(workspaceId);
        if (engine.kind() != WorkModelKind.CLI) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "rebasing needs a CLI agent; this workspace's CI-fix engine is "
                            + engine.agentOrProvider());
        }
        boolean resuming = resumeSessionId != null && !resumeSessionId.isBlank();
        String prompt = (resuming ? "" : systemPrompt() + "\n\n") + REBASE_PROMPT;
        CliReviewRunner.Result result = cli.run(
                cliProvider(engine.agentOrProvider()), prompt, resumeSessionId, worktree, null,
                toIntExact(Math.max(1, budgetMilliUsd / 10)),
                CliReviewRunner.Sandbox.WRITE);
        return read(result.text(), result.costUsdMilli(), result.sessionId());
    }

    /**
     * What this repo has already taught the agent about failures like these.
     * Prose, retrieved by relevance to this round's signatures — it informs the
     * agent's judgement and never routes around it.
     */
    private String knowledge(String workspaceId, List<Failure> failures)
    {
        String hint = failures.stream()
                .map(Failure::signature)
                .filter(signature -> signature != null && !signature.isBlank())
                .limit(8)
                .collect(Collectors.joining(" "));
        String projection = "";
        try {
            projection = knowledge.render(workspaceId, "ci-fix", hint);
        }
        catch (RuntimeException unavailable) {
            // Memory is an advantage, not a precondition. A round still runs
            // without it; it just starts from less.
            log.warn("CI harness knowledge projection unavailable: {}", unavailable.getMessage());
        }
        if (projection == null || projection.isBlank()) {
            return "";
        }
        return "What this repository has taught you before:\n<knowledge>\n"
                + projection.strip() + "\n</knowledge>\n\n";
    }

    /**
     * The verdict is the last non-blank line. A turn that ends any other way is
     * a park: the program will not push a tree whose author never said it was
     * ready.
     */
    Outcome read(String raw, long costMilliUsd, String sessionId)
    {
        List<Learned> learned = learned(raw);
        String last = lastLine(raw);
        String upper = last.toUpperCase(Locale.ROOT);
        if (upper.startsWith(COMMITTED)) {
            return new Outcome(
                    true, false, detail(last, COMMITTED), learned, costMilliUsd, sessionId);
        }
        if (upper.startsWith(NOTHING)) {
            return new Outcome(
                    false, true, detail(last, NOTHING), learned, costMilliUsd, sessionId);
        }
        if (upper.startsWith(PARKED)) {
            return new Outcome(
                    false, false, detail(last, PARKED), learned, costMilliUsd, sessionId);
        }
        return new Outcome(
                false, false,
                last.isBlank()
                        ? "the round ended without a verdict"
                        : "the round ended without a verdict: " + clamp(last),
                learned, costMilliUsd, sessionId);
    }

    /**
     * Entries the agent wrote for a fix CI has now confirmed. Extracted rather
     * than written by the agent directly: it authors the memory, the program
     * persists it, same as every other side effect in this loop.
     */
    static List<Learned> learned(String raw)
    {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<Learned> entries = new ArrayList<>();
        Matcher matcher = LEARNED_BLOCK.matcher(raw);
        while (matcher.find() && entries.size() < MAX_LEARNED) {
            String title = matcher.group(1).strip();
            String body = matcher.group(2).strip();
            if (!title.isBlank() && !body.isBlank()) {
                entries.add(new Learned(
                        title.length() <= MAX_TITLE ? title : title.substring(0, MAX_TITLE),
                        body.length() <= MAX_BODY ? body : body.substring(0, MAX_BODY)));
            }
        }
        return List.copyOf(entries);
    }

    private static String lastLine(String raw)
    {
        if (raw == null) {
            return "";
        }
        return raw.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .reduce((first, second) -> second)
                .orElse("");
    }

    private static String detail(String line, String marker)
    {
        return clamp(line.substring(marker.length()).strip());
    }

    private static String clamp(String value)
    {
        return value.length() <= MAX_DETAIL ? value : value.substring(0, MAX_DETAIL) + "…";
    }

    static String userPrompt(List<Failure> failures, String steeringText, boolean resuming)
    {
        StringBuilder prompt = new StringBuilder(4_096);
        if (resuming) {
            prompt.append("CI has finished another run on the pull request.\n\n")
                    .append("Before anything else: did what you changed last round work?")
                    .append(" The failures below are the whole of what is still red. If a")
                    .append(" failure you were fixing is still here, finishing it comes before")
                    .append(" starting anything new.\n\n");
        }
        else {
            prompt.append("CI has finished a run on the pull request holding this range.\n\n");
        }
        List<Failure> shown = failures.size() <= MAX_FAILURES
                ? failures : failures.subList(0, MAX_FAILURES);
        prompt.append(failures.size()).append(" failure(s) this round");
        if (shown.size() < failures.size()) {
            // No silent truncation: the agent has to know the list is partial or
            // it will read "that is all of it" into a cut-off prompt.
            prompt.append(" (the ").append(shown.size()).append(" below are shown; the rest")
                    .append(" will still be here next round if they matter)");
        }
        prompt.append(":\n");
        for (Failure failure : shown) {
            prompt.append("\n<failure job=\"").append(failure.jobName()).append('"');
            if (failure.module() != null && !failure.module().isBlank()) {
                prompt.append(" module=\"").append(failure.module()).append('"');
            }
            if (failure.bucketLabel() != null && !failure.bucketLabel().isBlank()) {
                // A hint from the log parser, not a routing decision.
                prompt.append(" looks-like=\"").append(failure.bucketLabel()).append('"');
            }
            prompt.append(">\n").append(failure.signature()).append('\n');
            String excerpt = failure.logExcerpt();
            if (excerpt != null && !excerpt.isBlank()) {
                prompt.append(excerpt.length() <= MAX_EXCERPT
                                ? excerpt : excerpt.substring(0, MAX_EXCERPT) + "\n… truncated")
                        .append('\n');
            }
            prompt.append("</failure>\n");
        }
        if (steeringText != null && !steeringText.isBlank()) {
            prompt.append("\nThe person watching this run added:\n<steer>\n")
                    .append(steeringText.strip()).append("\n</steer>\n");
        }
        return prompt.toString();
    }

    private static final String RETROSPECTIVE_PROMPT = """
            A human has reviewed and merged this range. The run is over and this
            worktree is about to be removed, so anything worth keeping has to be
            written down now.

            Look back over the whole range — your own picks, the conflicts you
            resolved, the CI failures you chased — and, importantly, at what changed
            between what you last pushed and what was actually merged. A reviewer's
            correction is the most useful thing you will see all run and this is the
            only moment it is still visible: `git log` and `git diff` against what you
            pushed will show it.

            Write what a future run on this fork should know. One block per thing
            worth knowing, and nothing that is merely true of this one range:

              <learned title="short, searchable, names the thing">
              What happens, why it happens on this fork specifically, and what to do
              about it. Include what you got wrong and what corrected it.
              </learned>

            Write nothing if the range taught nothing durable — that is a real answer.

            End your turn with:
              NOTHING: <one sentence on what this range taught, or that it taught nothing>
            """;

    private static final String REBASE_PROMPT = """
            This branch no longer merges into the fork's target branch — the target has
            moved under us, which it will on a range that takes days.

            Fetch the target branch and rebase this one onto it. Resolve the conflicts
            the way this fork wants them, keeping every commit-shape rule above: each
            resolution belongs in the fixup of the pick that owns it, one fixup per pick,
            and a change no single pick owns becomes its own commit at the tip.

            Do not push. Do not squash the picks together. Do not reorder them.

            End your turn with exactly one of:
              COMMITTED: <one sentence on what the rebase took>
              PARKED: <why a human has to resolve this one>
            """;

    private static String systemPrompt()
    {
        return """
                You are keeping a long version-bump pull request green on a fork that tracks
                an upstream project. The branch is a series of cherry-picks from upstream;
                you already made the picks and repaired the conflicts in this session.

                Each time CI finishes, you are woken with everything that is still red and
                you decide what to do about it. How much to take on in one round is your
                call — all of it, or the one thing everything else depends on.

                Two rules override that judgement:

                1. If anything failed to COMPILE or BUILD, fix that first and consider
                   nothing else this round. Every other red job is noise until the tree
                   builds: tests that never ran, style gates on a tree that will not
                   compile, jobs failing on an artifact that was never produced.

                2. If a failure you were fixing last round is still here, finishing it
                   comes before starting anything new.

                Infrastructure failures (secrets, cloud access, special hardware) and
                obvious flakes are not yours. Say so and leave them.

                How your fixes must land:

                - Each fix is a commit named "fixup! <the exact subject of the cherry-pick
                  that owns it>", sitting directly after that pick. Every pick has to stay
                  independently reviewable against upstream, so never amend a pick itself.
                - One fixup per pick, ever. If that pick already has a fixup, squash into
                  it rather than adding a second.
                - A fix that no single pick owns — a fork-wide adjustment, a new fork-only
                  file — is its own ordinary commit at the tip of the branch instead.
                  Do not invent an owner for it.
                - Use git rebase to position commits. Never push, and never touch a branch
                  other than the one checked out here.

                Validate however you can before you finish — find the project's build in
                its README, CONTRIBUTING or CI config and run what is relevant to what you
                changed. CI is the real verdict and it will run again on what you leave.

                Leave the worktree clean: everything committed, nothing staged, no strays.

                When a fix of yours is GONE from the failures — CI has confirmed it — write
                what it taught this repository, before you move on:

                  <learned title="short, searchable, names the failure">
                  What the failure looked like in the log. What actually caused it. What you
                  changed and why that worked. What you tried first that did not, and why.
                  </learned>

                Only after CI confirms it. A fix that merely passed on your machine has not
                been confirmed, and a memory of something that did not work is worse than no
                memory at all. You will be shown these entries on later runs, so write them
                for a reader who has forgotten everything but has the same failure in front
                of them. Nothing to confirm this round means no block — most rounds have none.

                End your turn with exactly one of these as the final line:
                  COMMITTED: <one sentence on what you fixed>
                  NOTHING: <why nothing here is yours to fix>
                  PARKED: <why a human has to take this one>
                """;
    }

    /**
     * @param committed the agent made commits and the program should push them
     * @param nothing   the agent looked and judged nothing here to be its work — not a
     *                  failure, but nothing to push either
     * @param detail    the agent's own sentence
     * @param learned   memories for fixes CI has now confirmed; empty on most rounds
     */
    public record Outcome(
            boolean committed,
            boolean nothing,
            String detail,
            List<Learned> learned,
            long costMilliUsd,
            String sessionId)
    {
        public Outcome(
                boolean committed, boolean nothing, String detail,
                long costMilliUsd, String sessionId)
        {
            this(committed, nothing, detail, List.of(), costMilliUsd, sessionId);
        }
    }

    /** One knowledge-base entry, in the agent's own prose. */
    public record Learned(String title, String body) {}
}
