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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.agents.AgentVerdictFile;
import com.bytequay.app.service.review.CliReviewRunner;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/**
 * Repairs one conflicted cherry-pick. The agent reads the conflicted files,
 * resolves them, commits the fixup and validates it — this class only starts
 * the turn and reads the verdict off the end of it.
 *
 * <p>That is the inversion decided 2026-08-05: the program used to run the
 * compile, ask for find/replace edits, validate the anchors, apply them and
 * retry a fixed number of times. Every one of those was the program deciding
 * something the agent is better placed to decide. See "The upstream sync run"
 * in {@code docs/intermediate/ci-autofix-design.md}.
 *
 * <p>The engine is whatever this workspace resolves for CI-fix work, through
 * the one chain every other agent uses. It runs through {@link CliReviewRunner} in
 * {@link CliReviewRunner.Sandbox#WRITE}, keeping one session for the whole run
 * and resuming it by id, so a conflict late in a range still knows what the
 * fork decided about the ones before it.
 */
@Component
public class ConflictRepairAgent
        implements ConflictRepairAdvisor
{
    /** What the agent may write as its verdict status. */
    private static final String RESOLVED = "resolved";
    private static final String UNVALIDATED = "resolved_unvalidated";
    private static final String PARKED = "parked";
    private static final int MAX_DETAIL = 500;
    /** One corrective turn is usually enough: the work is already committed and
     *  the session still remembers it, so all that is missing is the file. */
    private static final int MAX_VERDICT_RETRIES = 2;

    private final CliReviewRunner cli;
    private final WorkModelResolver engines;
    private final AgentVerdictFile verdicts;

    public ConflictRepairAgent(
            CliReviewRunner cli,
            WorkModelResolver engines,
            ObjectMapper mapper)
    {
        this.cli = requireNonNull(cli, "cli is null");
        this.engines = requireNonNull(engines, "engines is null");
        this.verdicts = new AgentVerdictFile(requireNonNull(mapper, "mapper is null"));
    }

    /**
     * One chain, the same one threads and tasks use: this workspace's engine for
     * the audience, then its default, then the catalog's first CLI agent. There
     * used to be a second account-level chain here whose CI-fix fallback was
     * codex, so this path silently disagreed with every other one.
     */
    WorkModel engineFor(String workspaceId)
    {
        return engines.resolveForWorkspace(workspaceId, SessionAudience.CI_FIX).choice();
    }

    static CliReviewRunner.Provider cliProvider(String agent)
    {
        return switch (agent) {
            case "claude-code", "claude-cli", "claude" -> CliReviewRunner.Provider.CLAUDE;
            case "codex", "codex-cli" -> CliReviewRunner.Provider.CODEX;
            default -> throw new IllegalStateException("unknown CLI agent: " + agent);
        };
    }

    @Override
    public Outcome repair(
            Path worktree,
            String workspaceId,
            String targetSubject,
            List<String> conflictPaths,
            String validateHint,
            long budgetMilliUsd,
            String resumeSessionId)
    {
        requireNonNull(worktree, "worktree is null");
        WorkModel engine = engineFor(workspaceId);
        if (engine.kind() != WorkModelKind.CLI) {
            // An in-JVM API turn has no shell and no editor, so it cannot do
            // this job at all. Say so rather than silently doing nothing.
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "conflict repair needs a CLI agent; this workspace's CI-fix engine is "
                            + engine.agentOrProvider());
        }
        verdicts.clear(worktree);
        String prompt = (resumeSessionId == null ? systemPrompt() + "\n\n" : "")
                + userPrompt(targetSubject, conflictPaths, validateHint);
        String session = resumeSessionId;
        long spent = 0;
        String transcript = null;
        for (int attempt = 0; attempt <= MAX_VERDICT_RETRIES; attempt++) {
            CliReviewRunner.Result result = cli.run(
                    cliProvider(engine.agentOrProvider()), prompt, session, worktree, null,
                    toIntExact(Math.max(1, (budgetMilliUsd - spent) / 10)),
                    CliReviewRunner.Sandbox.WRITE);
            spent += result.costUsdMilli();
            session = result.sessionId() == null ? session : result.sessionId();
            transcript = result.transcript() == null ? transcript : result.transcript();
            if (result.failed()) {
                // The turn never happened — a missing binary, a refused login, a
                // rejected flag. Asking again would fail the same way.
                return new Outcome(
                        false, false,
                        "the repair agent did not run: " + clamp(String.valueOf(result.errorMessage())),
                        transcript, spent, session);
            }
            Optional<AgentVerdictFile.Verdict> verdict = verdicts.read(worktree);
            if (verdict.isPresent()) {
                Outcome outcome = outcomeOf(verdict.orElseThrow(), transcript, spent, session);
                verdicts.clear(worktree);
                return outcome;
            }
            // No verdict. The work may well be done — the session still knows what
            // it did — so ask for the file rather than redo or discard the turn.
            prompt = retryPrompt(attempt);
        }
        verdicts.clear(worktree);
        return new Outcome(
                false, false,
                "the agent finished " + (MAX_VERDICT_RETRIES + 1)
                        + " turns without writing a verdict to " + AgentVerdictFile.relativePath(),
                transcript, spent, session);
    }

    Outcome outcomeOf(
            AgentVerdictFile.Verdict verdict, String transcript, long costMilliUsd, String sessionId)
    {
        return switch (verdict.status()) {
            case RESOLVED -> new Outcome(
                    true, true, clamp(verdict.summary()), transcript, costMilliUsd, sessionId);
            case UNVALIDATED -> new Outcome(
                    true, false, clamp(verdict.summary()), transcript, costMilliUsd, sessionId);
            case PARKED -> new Outcome(
                    false, false, clamp(verdict.summary()), transcript, costMilliUsd, sessionId);
            // An unknown status is not a resolution. Parking on it is the only
            // safe reading, and it names what was written so the prompt can be fixed.
            default -> new Outcome(
                    false, false,
                    "the agent wrote an unknown verdict status: " + clamp(verdict.status()),
                    transcript, costMilliUsd, sessionId);
        };
    }

    private static String retryPrompt(int attempt)
    {
        return attempt == 0
                ? "You did not write " + AgentVerdictFile.relativePath() + ". Do not redo any "
                        + "work — just write that file now, describing what you already did."
                : "There is still no " + AgentVerdictFile.relativePath() + ". Write exactly this "
                        + "file, nothing else:\n"
                        + "{\"status\":\"resolved|resolved_unvalidated|parked\",\"summary\":\"one sentence\"}";
    }

    private static String clamp(String value)
    {
        return value.length() <= MAX_DETAIL ? value : value.substring(0, MAX_DETAIL) + "…";
    }

    private static String userPrompt(
            String targetSubject, List<String> conflictPaths, String validateHint)
    {
        StringBuilder prompt = new StringBuilder(1_024);
        prompt.append("A cherry-pick from the upstream project conflicted on this fork.\n\n")
                .append("Cherry-picked commit: ").append(targetSubject).append('\n');
        if (conflictPaths != null && !conflictPaths.isEmpty()) {
            prompt.append("Files git reported as conflicted:\n");
            for (String path : conflictPaths) {
                prompt.append("  ").append(path).append('\n');
            }
        }
        if (validateHint != null && !validateHint.isBlank()) {
            prompt.append("\nThe run was configured with this validation command:\n  ")
                    .append(validateHint.strip())
                    .append("\nPrefer it over one you find yourself, and scope it to what you"
                            + " changed if the build supports that.\n");
        }
        prompt.append("\nHEAD is that pick with git's own three-way resolution already"
                + " committed — conflict markers and all. Repair it.");
        return prompt.toString();
    }

    private static String systemPrompt()
    {
        return """
                You repair conflicted cherry-picks in a fork that tracks an upstream project.
                You work directly in the checkout you are running in. One session covers the
                whole range, so what you decide now should stay consistent for later picks.

                For each conflict you are asked about:

                1. Resolve it. Remove every conflict marker (<<<<<<<, =======, >>>>>>>) you
                   touch. Keep the fork's own behaviour where upstream did not intend to
                   change it, and upstream's change where it did. When those genuinely
                   conflict, prefer the fork's configuration names, bindings and defaults.
                   Do not reformat, do not fix unrelated code, do not delete tests.

                2. Commit it as that pick's fixup, and nothing else:
                     git add -- <only the files you changed>
                     git commit -m "fixup! <the exact cherry-picked commit subject>"
                   If HEAD is already a fixup for this same pick, amend it instead
                   (git commit --amend --no-edit) so a pick never carries two fixups.
                   Never commit with -a or add paths you did not change. Never rebase,
                   never push, never touch any branch.

                3. Validate it. Find how this project builds — its README, CONTRIBUTING,
                   or its CI config — and run that, scoped to the module you touched if
                   the build supports it. Tests are not your job here; compiling is.
                   Iterate until it passes. If the build cannot run in this environment at
                   all (no toolchain, unreachable registry, missing credentials), stop
                   trying: that is not a defect in the code, and CI will judge the range
                   later.

                Leave the worktree clean — everything you changed committed, nothing
                staged, no stray files.

                When you are done, write your verdict to .bytequay/verdict.json — create the
                directory if it is not there. That file is how the program learns what
                happened; nothing else you write is read as a decision.

                  {"status":"resolved","summary":"one sentence on what you did"}
                  {"status":"resolved_unvalidated","summary":"what you did, and why the build
                   could not run"}
                  {"status":"parked","summary":"why a human has to decide this one"}

                Never commit that file. It is removed for you between turns.

                Park rather than guess. A wrong resolution that compiles is worse than a
                stop, because nothing downstream will catch it.
                """;
    }
}
