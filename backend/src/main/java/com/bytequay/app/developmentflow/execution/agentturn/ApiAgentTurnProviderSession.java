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
package com.bytequay.app.developmentflow.execution.agentturn;

import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnHooks;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Completion.CANCELED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Completion.FAILED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Completion.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.Transport.API;
import static java.util.Objects.requireNonNull;

/** In-JVM API adapter for one exact typed Turn. */
public final class ApiAgentTurnProviderSession
        implements AgentTurnProviderSession
{
    private static final int MAX_OUTPUT_TOKENS = 4_096;
    private static final int MAX_TOOL_ITERATIONS = 12;

    private final ProviderResolver providers;
    private final OwnerMcpClient tools;
    private final TurnExecutor turns;
    private final ObjectMapper json;

    public ApiAgentTurnProviderSession(
            ProviderResolver providers,
            OwnerMcpClient tools,
            TurnRunner turns,
            ObjectMapper json)
    {
        this(providers, tools, turns::runTurn, json);
    }

    ApiAgentTurnProviderSession(
            ProviderResolver providers,
            OwnerMcpClient tools,
            TurnExecutor turns,
            ObjectMapper json)
    {
        this.providers = requireNonNull(providers, "providers is null");
        this.tools = requireNonNull(tools, "tools is null");
        this.turns = requireNonNull(turns, "turns is null");
        this.json = requireNonNull(json, "json is null");
    }

    @Override
    public Session open(Request request, Observer observer)
    {
        requireNonNull(request, "request is null");
        requireNonNull(observer, "observer is null");
        if (request.transport() != API) {
            throw new IllegalArgumentException(
                    "API adapter cannot run " + request.transport() + " transport");
        }
        return new ApiSession(request, observer);
    }

    private final class ApiSession
            implements Session
    {
        private final Request request;
        private final Observer observer;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean canceled = new AtomicBoolean();
        private final AtomicReference<Thread> runner = new AtomicReference<>();
        private final AtomicLong logSequence = new AtomicLong();

        private ApiSession(Request request, Observer observer)
        {
            this.request = request;
            this.observer = observer;
        }

        @Override
        public Result startAndAwait(WriterFence writerFence)
                throws Exception
        {
            if (!started.compareAndSet(false, true)) {
                throw new IllegalStateException("API provider session was already started");
            }
            requireWriterFence(request, writerFence);
            if (canceled.get()) {
                return canceledResult("provider session canceled before API launch");
            }

            runner.set(Thread.currentThread());
            String sessionId = "api-" + UUID.randomUUID();
            observer.providerSession(request.provider(), sessionId);
            try {
                ResolvedProvider provider = providers.resolve(request);
                List<OwnerMcpClient.ToolDefinition> catalog =
                        tools.list(request.toolEndpoint(), writerFence);
                TurnResult result = turns.run(
                        spec(request, provider, catalog),
                        call -> callTool(call, writerFence),
                        hooks());
                return providerResult(sessionId, result);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (canceled.get()) {
                    return canceledResult("provider session canceled");
                }
                throw interrupted;
            }
            catch (RuntimeException failure) {
                if (canceled.get()) {
                    return canceledResult("provider session canceled");
                }
                throw failure;
            }
            finally {
                runner.set(null);
            }
        }

        private ToolExecutor.ToolCallResult callTool(
                ToolCall call, WriterFence writerFence)
        {
            if (canceled.get()) {
                return ToolExecutor.ToolCallResult.error("provider session canceled");
            }
            try {
                return tools.call(request.toolEndpoint(), writerFence, call);
            }
            catch (RuntimeException failure) {
                if (canceled.get()) {
                    return ToolExecutor.ToolCallResult.error("provider session canceled");
                }
                return ToolExecutor.ToolCallResult.error(
                        failure.getMessage() == null
                                ? "owner tool call failed"
                                : failure.getMessage());
            }
        }

        private TurnHooks hooks()
        {
            return new TurnHooks()
            {
                @Override
                public void onTextDelta(int blockIndex, String chunk)
                {
                    log("text_delta", object()
                            .put("blockIndex", blockIndex)
                            .put("chunk", chunk));
                }

                @Override
                public void onToolCallStarted(
                        String callId, String toolName, String inputJson)
                {
                    log("tool_started", object()
                            .put("callId", callId)
                            .put("tool", toolName)
                            .put("input", inputJson));
                }

                @Override
                public void onToolCallDone(
                        String callId, String resultText, boolean isError)
                {
                    log("tool_finished", object()
                            .put("callId", callId)
                            .put("result", resultText)
                            .put("isError", isError));
                }

                @Override
                public boolean interrupted()
                {
                    return canceled.get() || Thread.currentThread().isInterrupted();
                }

                @Override
                public boolean abortTurn(long costSoFarMilliUsd)
                {
                    return request.maxCostUsdMilli() != null
                            && costSoFarMilliUsd >= request.maxCostUsdMilli();
                }
            };
        }

        private void log(String event, ObjectNode fields)
        {
            fields.put("event", event);
            observer.log(logSequence.getAndIncrement(), fields.toString());
        }

        private ObjectNode object()
        {
            return json.createObjectNode();
        }

        private Result providerResult(String sessionId, TurnResult result)
        {
            return switch (result.end()) {
                case COMPLETED, MAX_STEPS -> new Result(
                        SUCCEEDED,
                        sessionId,
                        result.finalText(),
                        result.tokensIn(),
                        result.tokensOut(),
                        result.costMilliUsd(),
                        null,
                        null);
                case INTERRUPTED -> canceledResult("provider session canceled");
                case ABORTED -> new Result(
                        FAILED,
                        sessionId,
                        result.finalText(),
                        result.tokensIn(),
                        result.tokensOut(),
                        result.costMilliUsd(),
                        null,
                        "provider turn budget was exhausted");
            };
        }

        @Override
        public void cancel()
        {
            canceled.set(true);
            Thread running = runner.get();
            if (running != null) {
                running.interrupt();
            }
        }

        @Override
        public void close()
        {
            if (runner.get() != null) {
                cancel();
            }
        }
    }

    private TurnSpec spec(
            Request request,
            ResolvedProvider provider,
            List<OwnerMcpClient.ToolDefinition> catalog)
    {
        ArrayNode messages = json.createArrayNode();
        String system = request.systemPrompt();
        if (provider.transport() == TurnSpec.Transport.OPENAI_COMPAT
                && system != null) {
            messages.add(objectMessage("system", system));
            system = null;
        }
        messages.add(userMessage(request, provider.transport()));
        return new TurnSpec(
                provider.transport(),
                provider.url(),
                provider.authToken(),
                request.model(),
                apiEffort(request),
                system,
                messages,
                renderTools(provider.transport(), catalog),
                MAX_OUTPUT_TOKENS,
                MAX_TOOL_ITERATIONS);
    }

    private static String apiEffort(Request request)
    {
        String effort = request.reasoningEffort();
        if (effort == null) {
            return null;
        }
        return switch (request.provider().toLowerCase(Locale.ROOT)) {
            case "anthropic", "openai" -> effort;
            default -> throw new IllegalArgumentException(
                    "reasoning effort is unsupported for API provider "
                            + request.provider());
        };
    }

    private ObjectNode objectMessage(String role, String content)
    {
        return json.createObjectNode().put("role", role).put("content", content);
    }

    private ObjectNode userMessage(
            Request request, TurnSpec.Transport transport)
    {
        if (request.images().isEmpty()) {
            return objectMessage("user", request.prompt());
        }
        ObjectNode message = json.createObjectNode().put("role", "user");
        ArrayNode content = message.putArray("content");
        request.images().forEach(image -> content.add(
                imageContent(image, transport)));
        content.add(json.createObjectNode()
                .put("type", "text")
                .put("text", request.prompt()));
        return message;
    }

    private ObjectNode imageContent(
            AgentTurnProviderSession.ImageAttachment image,
            TurnSpec.Transport transport)
    {
        byte[] bytes = image.readVerified();
        String encoded = Base64.getEncoder().encodeToString(bytes);
        ObjectNode content = json.createObjectNode();
        if (transport == TurnSpec.Transport.ANTHROPIC) {
            content.put("type", "image");
            content.putObject("source")
                    .put("type", "base64")
                    .put("media_type", image.mediaType())
                    .put("data", encoded);
        }
        else {
            content.put("type", "image_url");
            content.putObject("image_url")
                    .put("url", "data:" + image.mediaType()
                            + ";base64," + encoded);
        }
        return content;
    }

    private ArrayNode renderTools(
            TurnSpec.Transport transport,
            List<OwnerMcpClient.ToolDefinition> catalog)
    {
        ArrayNode rendered = json.createArrayNode();
        for (OwnerMcpClient.ToolDefinition tool : catalog) {
            if (transport == TurnSpec.Transport.ANTHROPIC) {
                ObjectNode item = json.createObjectNode();
                item.put("name", tool.name());
                item.put("description", tool.description());
                item.set("input_schema", tool.inputSchema().deepCopy());
                rendered.add(item);
            }
            else {
                ObjectNode function = json.createObjectNode();
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", tool.inputSchema().deepCopy());
                ObjectNode item = json.createObjectNode();
                item.put("type", "function");
                item.set("function", function);
                rendered.add(item);
            }
        }
        return rendered;
    }

    private static void requireWriterFence(Request request, WriterFence writerFence)
    {
        if (request.access() == Access.READ_ONLY && writerFence != null) {
            throw new IllegalArgumentException("read-only API Turn cannot receive a writer fence");
        }
        if (request.access() == Access.WORKTREE_WRITE
                && (writerFence == null
                || !writerFence.worktreePath().equals(
                        request.workingDirectory().toString())
                || !writerFence.operationId().equals(
                        request.toolEndpoint().operationId()))) {
            throw new IllegalArgumentException(
                    "code-writing API Turn requires its exact writer fence");
        }
    }

    private static Result canceledResult(String error)
    {
        return new Result(CANCELED, null, "", 0, 0, 0, null, error);
    }

    @FunctionalInterface
    interface TurnExecutor
    {
        TurnResult run(TurnSpec spec, ToolExecutor tools, TurnHooks hooks);
    }

    @FunctionalInterface
    public interface ProviderResolver
    {
        ResolvedProvider resolve(Request request);
    }

    public record ResolvedProvider(
            TurnSpec.Transport transport,
            String url,
            String authToken)
    {
        public ResolvedProvider
        {
            requireNonNull(transport, "transport is null");
            requireText(url, "url");
            requireText(authToken, "authToken");
        }
    }

    public interface OwnerMcpClient
    {
        List<ToolDefinition> list(
                OwnerToolEndpoint endpoint,
                WriterFence writerFence)
                throws InterruptedException;

        ToolExecutor.ToolCallResult call(
                OwnerToolEndpoint endpoint,
                WriterFence writerFence,
                ToolCall call);

        record ToolDefinition(String name, String description, JsonNode inputSchema)
        {
            public ToolDefinition
            {
                requireText(name, "name");
                requireNonNull(description, "description is null");
                requireNonNull(inputSchema, "inputSchema is null");
                if (!inputSchema.isObject()) {
                    throw new IllegalArgumentException("inputSchema must be an object");
                }
                inputSchema = inputSchema.deepCopy();
            }
        }
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
        return value;
    }
}
