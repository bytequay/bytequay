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
package com.bytequay.app.service.ai;

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.ReviewOutput;
import com.bytequay.app.domain.ReviewRequest;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnHooks;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.local.ds4.Ds4State;
import com.bytequay.app.service.review.CliReviewRunner;
import com.bytequay.app.service.settings.AiDefaultsService;
import com.bytequay.app.service.threads.AgentScheduler;
import com.bytequay.app.service.workmodel.WorkModelCatalog;
import com.bytequay.app.service.workmodel.WorkspaceEngineSettings;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

/**
 * Runs the account-wide, diff-only PR reviewer selected in Settings → AI.
 * It owns no workspace or durable agent session, but still uses the shared
 * scheduler lanes so a quick review cannot bypass CLI/API resource caps.
 */
@Component
public class GlobalReviewRunner
{
    private static final int MAX_OUTPUT_TOKENS = 8_192;
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";
    private static final String LOCAL_MODEL = "deepseek-v4-flash";
    private static final String LOCAL_TOKEN = "dsv4-local";

    private final AiDefaultsService defaults;
    private final CredentialService credentials;
    private final Ds4LifecycleService ds4;
    private final TurnRunner turnRunner;
    private final CliReviewRunner cliRunner;
    private final AgentScheduler scheduler;
    private final ObjectMapper mapper;

    public GlobalReviewRunner(
            AiDefaultsService defaults,
            CredentialService credentials,
            Ds4LifecycleService ds4,
            TurnRunner turnRunner,
            CliReviewRunner cliRunner,
            AgentScheduler scheduler,
            ObjectMapper mapper)
    {
        this.defaults = requireNonNull(defaults, "defaults is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.ds4 = requireNonNull(ds4, "ds4 is null");
        this.turnRunner = requireNonNull(turnRunner, "turnRunner is null");
        this.cliRunner = requireNonNull(cliRunner, "cliRunner is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public ReviewOutput review(ReviewRequest request)
    {
        requireNonNull(request, "request is null");
        String choice = defaults.get().globalReview();
        WorkModel workModel = WorkspaceEngineSettings.parseChoice(choice)
                .orElseThrow(() -> new IllegalStateException(
                        "Global PR review engine '" + choice + "' is not supported. Choose another in Settings → AI."));
        String model = modelId(workModel);
        String text = workModel.kind() == WorkModelKind.CLI
                ? runCli(workModel, request)
                : runApi(workModel, model, request);
        return parse(text, workModel.agentOrProvider(), model);
    }

    private String runCli(WorkModel workModel, ReviewRequest request)
    {
        CliReviewRunner.Provider provider = switch (workModel.agentOrProvider()) {
            case "claude-code", "claude-cli" -> CliReviewRunner.Provider.CLAUDE;
            case "codex", "codex-cli" -> CliReviewRunner.Provider.CODEX;
            default -> throw new IllegalStateException(
                    "Unsupported global-review CLI: " + workModel.agentOrProvider());
        };
        String prompt = ReviewPrompt.systemPrompt(request) + "\n\n" + ReviewPrompt.userMessage(request);
        CliReviewRunner.Result result = scheduler.invokeCli(() -> cliRunner.runWithSchedulerCapacity(
                provider, prompt, null, Path.of(System.getProperty("java.io.tmpdir")), null));
        if (!"COMPLETED".equals(result.end())) {
            throw new IllegalStateException(requireNonNullElse(
                    result.errorMessage(), provider.displayName() + " quick review failed"));
        }
        return result.text();
    }

    private String runApi(WorkModel workModel, String model, ReviewRequest request)
    {
        Endpoint endpoint = endpoint(workModel, model);
        String system = ReviewPrompt.systemPrompt(request);
        ArrayNode messages = mapper.createArrayNode();
        if (endpoint.transport() == TurnSpec.Transport.OPENAI_COMPAT) {
            messages.add(message("system", system));
        }
        messages.add(message("user", ReviewPrompt.userMessage(request)));
        ToolExecutor noTools = call -> ToolExecutor.ToolCallResult.error("no tools are available in quick review");
        Callable<TurnResult> turn = () -> turnRunner.runTurn(
                new TurnSpec(
                        endpoint.transport(), endpoint.url(), endpoint.token(), model,
                        endpoint.transport() == TurnSpec.Transport.ANTHROPIC ? system : null,
                        messages, mapper.createArrayNode(), MAX_OUTPUT_TOKENS, 1),
                noTools, TurnHooks.NONE);
        TurnResult result = scheduler.invokeAll(List.of(turn)).getFirst();
        if (result.end() == TurnResult.End.INTERRUPTED || result.end() == TurnResult.End.ABORTED) {
            throw new IllegalStateException("Global PR review did not complete: " + result.end().name().toLowerCase(Locale.ROOT));
        }
        return result.finalText();
    }

    private Endpoint endpoint(WorkModel workModel, String model)
    {
        String provider = workModel.agentOrProvider().toLowerCase(Locale.ROOT);
        if (provider.equals("deepseek") && model.equals(LOCAL_MODEL)) {
            var status = ds4.status();
            if (status.state() != Ds4State.RUNNING) {
                throw new IllegalStateException(
                        "Local AI is not running. Start it in Settings → AI → Local AI (ds4).");
            }
            return new Endpoint(
                    TurnSpec.Transport.OPENAI_COMPAT,
                    status.endpoint().replaceAll("/+$", "") + "/v1/chat/completions",
                    LOCAL_TOKEN);
        }
        String token = secret(provider, workModel.account());
        return switch (provider) {
            case "anthropic", "claude" -> new Endpoint(TurnSpec.Transport.ANTHROPIC, ANTHROPIC_URL, token);
            case "openai" -> new Endpoint(TurnSpec.Transport.OPENAI_COMPAT, OPENAI_URL, token);
            case "deepseek" -> new Endpoint(TurnSpec.Transport.OPENAI_COMPAT, DEEPSEEK_URL, token);
            default -> throw new IllegalStateException(
                    "Provider '" + provider + "' cannot run a global PR review.");
        };
    }

    private String secret(String provider, String account)
    {
        Optional<String> secret = account == null || account.isBlank()
                ? credentials.getDefault(CredentialType.AI, provider)
                        .flatMap(value -> credentials.getSecret(
                                CredentialType.AI, provider, value.instanceName()))
                        .or(() -> credentials.getSecret(CredentialType.AI, provider))
                : credentials.getSecret(CredentialType.AI, provider, account);
        return secret.orElseThrow(() -> new IllegalStateException(
                "No " + provider + " API key on file"
                        + (account == null ? "" : " for account " + account)
                        + ". Add one in Settings → Credentials."));
    }

    private static String modelId(WorkModel workModel)
    {
        if (workModel.model() != null && !workModel.model().isBlank()) {
            return workModel.model();
        }
        if (workModel.kind() == WorkModelKind.CLI) {
            var agent = WorkModelCatalog.agent(workModel.agentOrProvider());
            return agent == null ? workModel.agentOrProvider() : agent.defaultModel().id();
        }
        var provider = WorkModelCatalog.provider(workModel.agentOrProvider());
        if (provider == null) {
            throw new IllegalStateException("Unknown global-review provider: " + workModel.agentOrProvider());
        }
        return provider.defaultModel().id();
    }

    private ReviewOutput parse(String text, String provider, String model)
    {
        try {
            String raw = requireNonNullElse(text, "");
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalStateException("No JSON object in model response");
            }
            ParsedOutput parsed = mapper.readValue(raw.substring(start, end + 1), ParsedOutput.class);
            List<ReviewOutput.LineComment> comments = Optional.ofNullable(parsed.comments())
                    .orElse(List.of()).stream()
                    .map(comment -> new ReviewOutput.LineComment(
                            comment.file(), comment.line(), comment.body(), comment.severity()))
                    .toList();
            return new ReviewOutput(
                    requireNonNullElse(parsed.summary(), ""), comments, provider, model);
        }
        catch (Exception e) {
            throw new IllegalStateException("Global PR reviewer returned malformed JSON", e);
        }
    }

    private ObjectNode message(String role, String content)
    {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private record Endpoint(TurnSpec.Transport transport, String url, String token) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ParsedOutput(String summary, List<ParsedComment> comments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ParsedComment(String file, int line, String severity, String body) {}
}
