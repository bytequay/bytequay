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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.InvestigationReviewData.ReviewObjectiveRow;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnHooks;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.review.InvestigationReviewModel.ReviewKnowledge;
import com.bytequay.app.service.review.InvestigationReviewModel.ReviewTurnPrompt;
import com.bytequay.app.service.skills.CavemanPrompt;
import com.bytequay.app.service.threads.AgentScheduler;
import com.bytequay.app.service.workspaces.SessionKnowledgeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import static java.util.Objects.requireNonNull;

/** One bounded API or CLI turn against the frozen investigation tools. */
@Component
public class InvestigationReviewRunner
        implements InvestigationReviewModel
{
    private static final Logger log = LoggerFactory.getLogger(InvestigationReviewRunner.class);
    private static final int MAX_OUTPUT_TOKENS = 4_096;
    private static final int MAX_TOOL_ITERATIONS = 18;
    private static final int RETRIEVAL_FILE_LIMIT = 8;
    private static final int RETRIEVAL_HINT_CHAR_LIMIT = 4_000;
    private static final String MCP_BASE = "http://127.0.0.1:53123";

    private final TurnRunner turnRunner;
    private final CliReviewRunner cliRunner;
    private final ReviewProviderEndpoints endpoints;
    private final ReviewPassService legacyRoster;
    private final InvestigationReviewTools tools;
    private final AgentScheduler scheduler;
    private final SessionKnowledgeProvider projectKnowledge;
    private final ObjectMapper mapper;

    public InvestigationReviewRunner(
            TurnRunner turnRunner, CliReviewRunner cliRunner,
            ReviewProviderEndpoints endpoints, ReviewPassService legacyRoster,
            InvestigationReviewTools tools, AgentScheduler scheduler,
            SessionKnowledgeProvider projectKnowledge, ObjectMapper mapper)
    {
        this.turnRunner = requireNonNull(turnRunner, "turnRunner is null");
        this.cliRunner = requireNonNull(cliRunner, "cliRunner is null");
        this.endpoints = requireNonNull(endpoints, "endpoints is null");
        this.legacyRoster = requireNonNull(legacyRoster, "legacyRoster is null");
        this.tools = requireNonNull(tools, "tools is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.projectKnowledge = requireNonNull(projectKnowledge, "projectKnowledge is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    @Override
    public List<ReviewKnowledge> reviewKnowledge(InvestigationReviewContext.Snapshot snapshot)
    {
        return projectKnowledge.reviewKnowledgeForRepository(
                        snapshot.pr().repo(), retrievalHint(snapshot)).stream()
                .map(entry -> new ReviewKnowledge(
                        entry.item().id(),
                        entry.item().kind(),
                        entry.item().statement(),
                        entry.applicability(),
                        entry.item().updatedAtMs()))
                .toList();
    }

    @Override
    public ProviderChoice choose(String requestedRunner, String requestedProvider)
    {
        List<ReviewPassService.RosterEntry> configured = legacyRoster.roster().stream()
                .filter(ReviewPassService.RosterEntry::configured)
                .toList();
        if (requestedProvider != null && !requestedProvider.isBlank()) {
            ReviewPassService.RosterEntry match = configured.stream()
                    .filter(row -> row.providerId().equalsIgnoreCase(requestedProvider))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "review provider is not configured: " + requestedProvider));
            return choice(match.providerId());
        }
        if ("cli".equalsIgnoreCase(requestedRunner)) {
            return configured.stream()
                    .filter(row -> "claude-cli".equals(row.providerId()))
                    .findFirst().map(row -> choice(row.providerId()))
                    .orElseThrow(() -> new IllegalStateException(
                            "Claude CLI is required for the structured CLI investigator"));
        }
        if (!"api".equalsIgnoreCase(requestedRunner)) {
            ProviderChoice api = configured.stream()
                    .filter(row -> !CliReviewRunner.Provider.isCliProvider(row.providerId()))
                    .findFirst().map(row -> choice(row.providerId())).orElse(null);
            if (api != null) {
                return api;
            }
        }
        return configured.stream()
                .filter(row -> !CliReviewRunner.Provider.isCliProvider(row.providerId()))
                .findFirst().map(row -> choice(row.providerId()))
                .orElseGet(() -> configured.stream()
                        .filter(row -> "claude-cli".equals(row.providerId()))
                        .findFirst().map(row -> choice(row.providerId()))
                        .orElseThrow(() -> new IllegalStateException(
                                "No API reviewer or Claude CLI is configured")));
    }

    @Override
    public ProviderChoice chooseVerifier(ProviderChoice investigator, String requiredRunner)
    {
        return legacyRoster.roster().stream()
                .filter(ReviewPassService.RosterEntry::configured)
                .map(row -> choice(row.providerId()))
                .filter(choice -> requiredRunner.equals(choice.runner()))
                .filter(choice -> !choice.providerId().equalsIgnoreCase(investigator.providerId()))
                .filter(choice -> !choice.family().equals(investigator.family()))
                .filter(choice -> !"cli".equals(choice.runner())
                        || "claude-cli".equals(choice.providerId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Independent verification requires a configured cross-family provider"));
    }

    @Override
    public RunOutcome investigate(
            ProviderChoice provider, String reviewId, String assignmentId,
            InvestigationReviewContext.Snapshot snapshot,
            List<ReviewObjectiveRow> objectives, String coverageContext,
            String persona, int costCapCents)
    {
        ReviewTurnPrompt launch = investigationPrompt(
                reviewId, snapshot, objectives, coverageContext, persona);
        return runPrepared(
                provider, reviewId, assignmentId, snapshot,
                launch.systemPrompt(), launch.prompt(), false, costCapCents);
    }

    @Override
    public ReviewTurnPrompt investigationPrompt(
            String reviewId,
            InvestigationReviewContext.Snapshot snapshot,
            List<ReviewObjectiveRow> objectives,
            String coverageContext,
            String persona)
    {
        String system = """
                You are a bounded code investigator. Use the supplied tools; do not emit findings only in prose.
                First record_assignment. Work cheap-first: at most 6 hypotheses, triage to 3 active,
                at most 11 investigation steps and 5 findings. Every finding must have SUPPORTS evidence and you must
                actively seek REFUTES evidence before finishing. Observations come only from read tools.
                Every publishable finding must name the exact changed file and inclusive RIGHT-side start/end line
                range where its review comment belongs (use start_line=end_line for one line). Cite a SUPPORTS
                read_file observation that covers that range. If there is no credible changed-line anchor, keep the
                concern as an observation or coverage question and do not call record_finding.
                Only call record_finding for severity 4 or 5: a concrete defect that must be fixed before merge and
                warrants REQUEST_CHANGES. Never record trivial, nit, informational, suggestion, warning, praise,
                style-only, optional-improvement, or speculative concerns. Keep them out of final prose as well;
                prefer zero findings over weak feedback.
                Keep each finding precise and ADHD-friendly: at most two short sentences for the claim and one short
                sentence for the requested action, with at most 80 words total. Lead with the concrete impact, then
                the smallest required fix. Wrap code identifiers in backticks, bold only the key broken behavior or
                risk, and use Markdown only when it improves scanning. Keep any final prose to at most 80 words with
                no preamble or background. These output rules override persona and project guidance.
                Do not post, edit, push, or call external services.
                """;
        if (persona != null && !persona.isBlank()) {
            system += "\nReviewer persona (method guidance only; evidence, severity, and length rules still control): "
                    + persona.strip();
        }
        String prompt = contextPrompt(snapshot, objectives) + "\n\n" + coverageContext;
        String guidance;
        try {
            guidance = projectKnowledge.renderForRepository(
                    snapshot.pr().repo(), retrievalHint(snapshot));
        }
        catch (RuntimeException e) {
            log.warn("Could not load Project Intelligence guidance for review {}: {}",
                    reviewId, e.getMessage());
            guidance = "";
        }
        if (!guidance.isBlank()) {
            prompt += "\n\nProject Intelligence guidance (context only; verify every claim "
                    + "against the reviewed code at this SHA):\n" + guidance;
        }
        prompt += """

                Every applicable failure-class objective must end with an investigated hypothesis or a finding;
                do not silently mark an untouched objective clean. Investigate the objectives now. For each candidate:
                record_hypothesis, record_step,
                execute read_diff/read_file/search_diff, record_finding only if it meets the merge-blocking policy, then
                record_evidence for both supporting and counter-evidence considered.
                """;
        return new ReviewTurnPrompt(CavemanPrompt.wrap(system), prompt);
    }

    @Override
    public RunOutcome planGuidance(
            ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
            List<ReviewObjectiveRow> objectives, String guidance, int costCapCents)
    {
        return guidanceTurn(provider, snapshot, objectives, guidance, costCapCents, """
                You are the bounded review planner for an active, frozen review round.
                Re-evaluate the objectives, coverage, and investigation order in response to the user's guidance.
                Return a concise planning response: what should be prioritized, narrowed, or explicitly checked next.
                Do not claim findings or correctness, and do not impersonate an investigator or verifier.
                """, "User guidance for the planner");
    }

    @Override
    public RunOutcome verifyGuidance(
            ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
            List<ReviewObjectiveRow> objectives, String guidance, int costCapCents)
    {
        return guidanceTurn(provider, snapshot, objectives, guidance, costCapCents, """
                You are the independent evidence critic for an active, frozen review round.
                Respond to the user's guidance by identifying what evidence, counter-evidence, scope, or severity
                must be challenged during verification. Do not create findings, claim a verdict, or impersonate
                an investigator. Return a concise independent verification plan.
                """, "User guidance for the independent verifier");
    }

    private RunOutcome guidanceTurn(
            ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
            List<ReviewObjectiveRow> objectives, String guidance, int costCapCents,
            String rolePrompt, String heading)
    {
        String system = CavemanPrompt.wrap(rolePrompt);
        String prompt = contextPrompt(snapshot, objectives)
                + "\n\n" + heading + ":\n" + guidance;
        if ("cli".equals(provider.runner())) {
            Path workingDir = snapshot.localRoot() == null
                    ? Path.of(System.getProperty("java.io.tmpdir")) : snapshot.localRoot();
            CliReviewRunner.Result result = scheduler.invokeCli(() -> cliRunner.runWithSchedulerCapacity(
                    CliReviewRunner.Provider.of(provider.providerId()), system + "\n\n" + prompt,
                    null, workingDir, null, costCapCents));
            return new RunOutcome(
                    provider, cents(result.costUsdMilli()), result.text(), 0, 0, 1, result.end());
        }
        ReviewProviderEndpoints.Endpoint endpoint = endpoints.resolve(provider.providerId());
        TurnHooks hooks = new TurnHooks()
        {
            @Override
            public boolean abortTurn(long costSoFarMilliUsd)
            {
                return costSoFarMilliUsd >= (long) costCapCents * 10;
            }
        };
        Callable<TurnResult> work = () -> turnRunner.runTurn(
                new TurnSpec(endpoint.transport(), endpoint.url(), endpoint.authToken(),
                        endpoint.modelId(), endpoint.transport() == TurnSpec.Transport.ANTHROPIC
                                ? system : null,
                        messages(endpoint.transport(), system, prompt), mapper.createArrayNode(),
                        1_024, 1),
                call -> ToolExecutor.ToolCallResult.error("planning guidance has no tools"), hooks);
        TurnResult result = scheduler.invokeAll(List.of(work)).get(0);
        return new RunOutcome(provider, cents(result.costMilliUsd()), result.finalText(),
                result.tokensIn(), result.tokensOut(), result.rounds(), result.end().name());
    }

    @Override
    public RunOutcome selfRefute(
            ProviderChoice provider, String reviewId, String assignmentId,
            InvestigationReviewContext.Snapshot snapshot, String findingBundles,
            int costCapCents)
    {
        String system = """
                You are performing the mandatory bounded self-refutation pass over surviving findings.
                Try to prove each finding wrong using existing observations first, then cheap read/search tools.
                Attach REFUTES evidence only when an observation actually contradicts or materially narrows a claim.
                It is valid to find no counter-evidence. Do not create new findings or hypotheses.
                """;
        String prompt = "Reviewed head: " + snapshot.headCommit()
                + "\nFindings requiring an explicit counter-evidence pass:\n" + findingBundles;
        return run(provider, reviewId, assignmentId, snapshot, system, prompt, false, costCapCents);
    }

    @Override
    public RunOutcome reconstruct(
            ProviderChoice provider, String reviewId, String assignmentId,
            InvestigationReviewContext.Snapshot snapshot, String locations,
            String persona, int costCapCents)
    {
        String system = """
                You are an independent verifier doing blind reconstruction. The original finding is hidden.
                Inspect only the cited locations and seek counter-evidence. Do not call record_verification;
                return one short paragraph reconstructing the behavior, scope, and plausible severity. Explicitly
                assess whether the evidence supports a severity 4 or 5 defect that warrants REQUEST_CHANGES.
                """;
        if (persona != null && !persona.isBlank()) {
            system += "\nVerifier persona (method guidance only; severity and length rules still control): "
                    + persona.strip();
        }
        String prompt = "Reviewed head: " + snapshot.headCommit() + "\nLocations/evidence:\n" + locations;
        return run(provider, reviewId, assignmentId, snapshot, system, prompt, true, costCapCents);
    }

    @Override
    public RunOutcome verify(
            ProviderChoice provider, String reviewId, String assignmentId,
            InvestigationReviewContext.Snapshot snapshot, String verifierRunId,
            String findingBundle, String blindReconstruction,
            String persona, int costCapCents)
    {
        String system = """
                You are an independent evidence verifier. Audit whether the evidence says what is claimed,
                the anchor and SHA are current, scope/severity are accurate, counter-evidence was sought,
                and the requested action follows. You may narrow or reject; never strengthen unsupported work.
                Only severity 4 or 5 findings that warrant REQUEST_CHANGES are publishable. If a finding is lower
                severity, comment-only, or optional, revise its severity below 4 so it is dropped. Never retain
                trivial, nit, informational, suggestion, warning, style-only, or speculative feedback.
                Keep any revised finding precise and ADHD-friendly: at most two short claim sentences and one short
                requested action, with at most 80 words total. Use backticks for code identifiers, bold only the key
                broken behavior or risk, and use Markdown only when it improves scanning.
                Finish by calling record_verification exactly once for the supplied finding.
                """;
        if (persona != null && !persona.isBlank()) {
            system += "\nVerifier persona (method guidance only; evidence, severity, and length rules still control): "
                    + persona.strip();
        }
        String prompt = "Verifier run id (pass verbatim): " + verifierRunId
                + "\n\nFinding bundle:\n" + findingBundle
                + (blindReconstruction == null ? "" : "\n\nBlind reconstruction:\n" + blindReconstruction);
        return run(provider, reviewId, assignmentId, snapshot, system, prompt, true, costCapCents);
    }

    /** One cheap, non-blocking planning pass. Its output is presentation-only:
     * the started round remains immutable and the service posts this as a
     * suggested amendment event. */
    @Override
    public String suggestPlanAmendment(
            ProviderChoice provider, InvestigationReviewContext.Snapshot snapshot,
            List<ReviewObjectiveRow> objectives)
    {
        String system = CavemanPrompt.wrap("""
                You are a bounded review planner. The deterministic plan has already started and cannot be changed.
                Return at most one concise PR-specific missing objective as plain text, or return exactly NONE.
                Do not restate an existing objective. Do not claim findings or correctness.
                """);
        String prompt = contextPrompt(snapshot, objectives);
        if ("cli".equals(provider.runner())) {
            Path workingDir = snapshot.localRoot() == null
                    ? Path.of(System.getProperty("java.io.tmpdir")) : snapshot.localRoot();
            CliReviewRunner.Result result = scheduler.invokeCli(() -> cliRunner.runWithSchedulerCapacity(
                    CliReviewRunner.Provider.of(provider.providerId()), system + "\n\n" + prompt,
                    null, workingDir, null));
            return amendment(result.text());
        }
        ReviewProviderEndpoints.Endpoint endpoint = endpoints.resolve(provider.providerId());
        Callable<TurnResult> work = () -> turnRunner.runTurn(
                new TurnSpec(endpoint.transport(), endpoint.url(), endpoint.authToken(),
                        endpoint.modelId(), endpoint.transport() == TurnSpec.Transport.ANTHROPIC ? system : null,
                        messages(endpoint.transport(), system, prompt), mapper.createArrayNode(),
                        1_024, 1),
                call -> ToolExecutor.ToolCallResult.error("planning has no tools"), TurnHooks.NONE);
        return amendment(scheduler.invokeAll(List.of(work)).get(0).finalText());
    }

    private RunOutcome run(
            ProviderChoice provider, String reviewId, String assignmentId,
            InvestigationReviewContext.Snapshot snapshot, String system,
            String prompt, boolean verifier, int costCapCents)
    {
        String styledSystem = CavemanPrompt.wrap(system);
        return runPrepared(
                provider, reviewId, assignmentId, snapshot,
                styledSystem, prompt, verifier, costCapCents);
    }

    private RunOutcome runPrepared(
            ProviderChoice provider, String reviewId, String assignmentId,
            InvestigationReviewContext.Snapshot snapshot, String styledSystem,
            String prompt, boolean verifier, int costCapCents)
    {
        return "cli".equals(provider.runner())
                ? runCli(provider, reviewId, assignmentId, snapshot,
                        styledSystem + "\n\n" + prompt, costCapCents)
                : runApi(provider, reviewId, assignmentId, styledSystem, prompt, verifier, costCapCents);
    }

    private RunOutcome runApi(
            ProviderChoice provider, String reviewId, String assignmentId,
            String system, String prompt, boolean verifier, int costCapCents)
    {
        ReviewProviderEndpoints.Endpoint endpoint = endpoints.resolve(provider.providerId());
        ArrayNode messages = messages(endpoint.transport(), system, prompt);
        ToolExecutor executor = tools.executor(reviewId, assignmentId);
        TurnHooks hooks = new TurnHooks()
        {
            @Override
            public boolean abortTurn(long costSoFarMilliUsd)
            {
                return costSoFarMilliUsd >= (long) costCapCents * 10;
            }
        };
        Callable<TurnResult> work = () -> turnRunner.runTurn(
                new TurnSpec(endpoint.transport(), endpoint.url(), endpoint.authToken(),
                        endpoint.modelId(), endpoint.transport() == TurnSpec.Transport.ANTHROPIC
                                ? system : null,
                        messages, tools.tools(endpoint.transport(), verifier),
                        MAX_OUTPUT_TOKENS, MAX_TOOL_ITERATIONS), executor, hooks);
        TurnResult result = scheduler.invokeAll(List.of(work)).get(0);
        return new RunOutcome(provider, cents(result.costMilliUsd()), result.finalText(),
                result.tokensIn(), result.tokensOut(), result.rounds(), result.end().name());
    }

    private RunOutcome runCli(
            ProviderChoice provider, String reviewId, String assignmentId,
            InvestigationReviewContext.Snapshot snapshot, String prompt, int costCapCents)
    {
        CliReviewRunner.Provider cli = CliReviewRunner.Provider.of(provider.providerId());
        if (cli != CliReviewRunner.Provider.CLAUDE) {
            throw new IllegalStateException("structured CLI investigation currently requires Claude CLI MCP");
        }
        String url = MCP_BASE + "/api/agent-reviews/" + reviewId
                + "/assignments/" + assignmentId + "/mcp";
        Path workingDir = snapshot.localRoot() == null
                ? Path.of(System.getProperty("java.io.tmpdir")) : snapshot.localRoot();
        CliReviewRunner.Result result = scheduler.invokeCli(() -> cliRunner.runWithSchedulerCapacity(
                cli, prompt, null, workingDir,
                new CliReviewRunner.McpEndpoint(reviewId, assignmentId, url), costCapCents));
        return new RunOutcome(
                provider, cents(result.costUsdMilli()), result.text(), 0, 0, 1, result.end());
    }

    private ArrayNode messages(TurnSpec.Transport transport, String system, String prompt)
    {
        ArrayNode messages = mapper.createArrayNode();
        if (transport == TurnSpec.Transport.OPENAI_COMPAT) {
            ObjectNode systemMessage = mapper.createObjectNode();
            systemMessage.put("role", "system");
            systemMessage.put("content", system);
            messages.add(systemMessage);
        }
        ObjectNode user = mapper.createObjectNode();
        user.put("role", "user");
        user.put("content", prompt);
        messages.add(user);
        return messages;
    }

    static String retrievalHint(InvestigationReviewContext.Snapshot snapshot)
    {
        StringBuilder hint = new StringBuilder();
        changedFilenames(snapshot).forEach(path -> hint.append(path).append('\n'));
        if (snapshot.pr().title() != null) {
            hint.append(snapshot.pr().title()).append('\n');
        }
        if (snapshot.pr().description() != null) {
            hint.append(snapshot.pr().description());
        }
        return hint.substring(0, Math.min(RETRIEVAL_HINT_CHAR_LIMIT, hint.length()));
    }

    static List<String> changedFilenames(InvestigationReviewContext.Snapshot snapshot)
    {
        if (snapshot.files() != null && !snapshot.files().isEmpty()) {
            return snapshot.files().stream()
                    .map(DiffFile::filename)
                    .filter(path -> path != null && !path.isBlank())
                    .distinct()
                    .limit(RETRIEVAL_FILE_LIMIT)
                    .toList();
        }
        if (snapshot.diff() == null || snapshot.diff().isBlank()) {
            return List.of();
        }
        return snapshot.diff().lines()
                .filter(line -> line.startsWith("diff --git a/"))
                .map(line -> {
                    int right = line.indexOf(" b/");
                    return right < 0 ? "" : line.substring(right + 3).strip();
                })
                .filter(path -> !path.isBlank())
                .distinct()
                .limit(RETRIEVAL_FILE_LIMIT)
                .toList();
    }

    private static String contextPrompt(
            InvestigationReviewContext.Snapshot snapshot, List<ReviewObjectiveRow> objectives)
    {
        StringBuilder prompt = new StringBuilder()
                .append("PR: ").append(snapshot.pr().title()).append('\n')
                .append("Description: ").append(snapshot.pr().description()).append('\n')
                .append("Reviewed head: ").append(snapshot.headCommit()).append("\nObjectives:\n");
        for (ReviewObjectiveRow objective : objectives) {
            prompt.append("- ").append(objective.id()).append(": ")
                    .append(objective.statement()).append(" [")
                    .append(objective.criterionId()).append("]\n");
        }
        prompt.append("\nDiff orientation (use read tools for cited output):\n")
                .append(snapshot.diff(), 0, Math.min(12_000, snapshot.diff().length()));
        return prompt.toString();
    }

    private static int cents(long milliUsd)
    {
        return (int) Math.max(0, (milliUsd + 9) / 10);
    }

    private static String amendment(String text)
    {
        if (text == null || text.isBlank() || "NONE".equalsIgnoreCase(text.strip())) {
            return null;
        }
        String stripped = text.strip();
        return stripped.substring(0, Math.min(600, stripped.length()));
    }

    private static ProviderChoice choice(String providerId)
    {
        if (CliReviewRunner.Provider.isCliProvider(providerId)) {
            String family = providerId.startsWith("claude") ? "anthropic" : "openai";
            return new ProviderChoice(providerId, "cli", family);
        }
        String lower = providerId.toLowerCase(Locale.ROOT);
        String family = lower.contains("claude") || lower.contains("anthropic")
                ? "anthropic" : lower.contains("deepseek") ? "deepseek" : "openai";
        return new ProviderChoice(providerId, "api", family);
    }

    public record ProviderChoice(String providerId, String runner, String family) {}

    public record RunOutcome(
            ProviderChoice provider, int costCents, String finalText,
            long tokensIn, long tokensOut, int providerRounds, String end) {}
}
