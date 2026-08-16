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
package com.bytequay.agent.cli.claude;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

final class ClaudeProtocol
{
    private ClaudeProtocol() {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = UnknownMessage.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(name = "system", value = SystemMessage.class),
            @JsonSubTypes.Type(name = "stream_event", value = StreamEventMessage.class),
            @JsonSubTypes.Type(name = "assistant", value = AssistantMessage.class),
            @JsonSubTypes.Type(name = "user", value = UserMessage.class),
            @JsonSubTypes.Type(name = "tool_progress", value = ToolProgressMessage.class),
            @JsonSubTypes.Type(name = "rate_limit_event", value = RateLimitMessage.class),
            @JsonSubTypes.Type(name = "result", value = ResultMessage.class),
    })
    sealed interface Message
            permits SystemMessage, StreamEventMessage, AssistantMessage, UserMessage,
            ToolProgressMessage, RateLimitMessage, ResultMessage, UnknownMessage
    {
        default Optional<String> uuid()
        {
            return Optional.empty();
        }

        default Optional<String> sessionId()
        {
            return Optional.empty();
        }
    }

    record SystemMessage(
            String subtype,
            @JsonProperty("session_id") String sessionIdValue,
            Optional<String> uuid,
            @JsonProperty("hook_id") Optional<String> hookId,
            @JsonProperty("hook_name") Optional<String> hookName,
            @JsonProperty("hook_event") Optional<String> hookEvent,
            Optional<String> output,
            Optional<String> stdout,
            Optional<String> stderr,
            @JsonProperty("exit_code") Optional<Integer> exitCode,
            Optional<String> outcome,
            Optional<String> status,
            @JsonProperty("task_id") Optional<String> taskId,
            @JsonProperty("tool_use_id") Optional<String> toolUseId,
            Optional<String> description,
            Optional<String> summary,
            Optional<String> prompt,
            @JsonProperty("parent_tool_use_id") Optional<String> parentToolUseId,
            @JsonProperty("elapsed_time_seconds") Optional<Double> elapsedTimeSeconds,
            Optional<Integer> attempt,
            @JsonProperty("retry_delay_ms") Optional<Long> retryDelayMs)
            implements Message
    {
        SystemMessage
        {
            requireNonNull(subtype, "subtype is null");
            requireNonNull(sessionIdValue, "sessionId is null");
            requireNonNull(uuid, "uuid is null");
            requireNonNull(hookId, "hookId is null");
            requireNonNull(hookName, "hookName is null");
            requireNonNull(hookEvent, "hookEvent is null");
            requireNonNull(output, "output is null");
            requireNonNull(stdout, "stdout is null");
            requireNonNull(stderr, "stderr is null");
            requireNonNull(exitCode, "exitCode is null");
            requireNonNull(outcome, "outcome is null");
            requireNonNull(status, "status is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(toolUseId, "toolUseId is null");
            requireNonNull(description, "description is null");
            requireNonNull(summary, "summary is null");
            requireNonNull(prompt, "prompt is null");
            requireNonNull(parentToolUseId, "parentToolUseId is null");
            requireNonNull(elapsedTimeSeconds, "elapsedTimeSeconds is null");
            requireNonNull(attempt, "attempt is null");
            requireNonNull(retryDelayMs, "retryDelayMs is null");
        }

        @Override
        public Optional<String> sessionId()
        {
            return Optional.of(sessionIdValue);
        }
    }

    record StreamEventMessage(
            ApiEvent event,
            @JsonProperty("session_id") String sessionIdValue,
            @JsonProperty("parent_tool_use_id") Optional<String> parentToolUseId,
            Optional<String> uuid)
            implements Message
    {
        StreamEventMessage
        {
            requireNonNull(event, "event is null");
            requireNonNull(sessionIdValue, "sessionId is null");
            requireNonNull(parentToolUseId, "parentToolUseId is null");
            requireNonNull(uuid, "uuid is null");
        }

        @Override
        public Optional<String> sessionId()
        {
            return Optional.of(sessionIdValue);
        }
    }

    record AssistantMessage(
            ApiMessage message,
            @JsonProperty("parent_tool_use_id") Optional<String> parentToolUseId,
            @JsonProperty("session_id") String sessionIdValue,
            Optional<String> uuid,
            Optional<String> timestamp)
            implements Message
    {
        AssistantMessage
        {
            requireNonNull(message, "message is null");
            requireNonNull(parentToolUseId, "parentToolUseId is null");
            requireNonNull(sessionIdValue, "sessionId is null");
            requireNonNull(uuid, "uuid is null");
            requireNonNull(timestamp, "timestamp is null");
        }

        @Override
        public Optional<String> sessionId()
        {
            return Optional.of(sessionIdValue);
        }
    }

    record UserMessage(
            ApiMessage message,
            @JsonProperty("parent_tool_use_id") Optional<String> parentToolUseId,
            @JsonProperty("session_id") Optional<String> sessionIdValue,
            Optional<String> uuid,
            Optional<String> timestamp)
            implements Message
    {
        UserMessage
        {
            requireNonNull(message, "message is null");
            requireNonNull(parentToolUseId, "parentToolUseId is null");
            requireNonNull(sessionIdValue, "sessionId is null");
            requireNonNull(uuid, "uuid is null");
            requireNonNull(timestamp, "timestamp is null");
        }

        @Override
        public Optional<String> sessionId()
        {
            return sessionIdValue;
        }
    }

    record ToolProgressMessage(
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("tool_name") String toolName,
            @JsonProperty("elapsed_time_seconds") double elapsedTimeSeconds,
            @JsonProperty("parent_tool_use_id") Optional<String> parentToolUseId,
            @JsonProperty("session_id") String sessionIdValue,
            Optional<String> uuid)
            implements Message
    {
        ToolProgressMessage
        {
            requireNonNull(toolUseId, "toolUseId is null");
            requireNonNull(toolName, "toolName is null");
            requireNonNull(parentToolUseId, "parentToolUseId is null");
            requireNonNull(sessionIdValue, "sessionId is null");
            requireNonNull(uuid, "uuid is null");
        }

        @Override
        public Optional<String> sessionId()
        {
            return Optional.of(sessionIdValue);
        }
    }

    record RateLimitMessage(
            @JsonProperty("rate_limit_info") RateLimitInfo rateLimitInfo,
            @JsonProperty("session_id") String sessionIdValue,
            Optional<String> uuid)
            implements Message
    {
        RateLimitMessage
        {
            requireNonNull(rateLimitInfo, "rateLimitInfo is null");
            requireNonNull(sessionIdValue, "sessionId is null");
            requireNonNull(uuid, "uuid is null");
        }

        @Override
        public Optional<String> sessionId()
        {
            return Optional.of(sessionIdValue);
        }
    }

    record ResultMessage(
            @JsonProperty("is_error") boolean error,
            @JsonProperty("duration_ms") long durationMs,
            @JsonProperty("duration_api_ms") long durationApiMs,
            @JsonProperty("num_turns") long numberOfTurns,
            @JsonProperty("session_id") String sessionIdValue,
            @JsonProperty("total_cost_usd") Optional<BigDecimal> totalCostUsd,
            Usage usage,
            String subtype,
            Optional<String> result,
            Optional<List<String>> errors,
            @JsonProperty("terminal_reason") Optional<String> terminalReason,
            Optional<String> uuid)
            implements Message
    {
        ResultMessage
        {
            requireNonNull(sessionIdValue, "sessionId is null");
            requireNonNull(totalCostUsd, "totalCostUsd is null");
            requireNonNull(usage, "usage is null");
            requireNonNull(subtype, "subtype is null");
            requireNonNull(result, "result is null");
            requireNonNull(errors, "errors is null");
            errors = errors.map(ImmutableList::copyOf);
            requireNonNull(terminalReason, "terminalReason is null");
            requireNonNull(uuid, "uuid is null");
        }

        @Override
        public Optional<String> sessionId()
        {
            return Optional.of(sessionIdValue);
        }

        String errorMessage()
        {
            return errors.stream()
                    .flatMap(List::stream)
                    .filter(value -> !value.isBlank())
                    .findFirst()
                    .or(() -> result.filter(value -> !value.isBlank()))
                    .or(() -> terminalReason.filter(value -> !value.isBlank()))
                    .orElse(subtype);
        }

        boolean successful()
        {
            return !error && subtype.equals("success");
        }
    }

    record UnknownMessage()
            implements Message {}

    record ApiMessage(String role, List<ContentBlock> content, Optional<Usage> usage)
    {
        ApiMessage
        {
            requireNonNull(role, "role is null");
            requireNonNull(content, "content is null");
            content = ImmutableList.copyOf(content);
            requireNonNull(usage, "usage is null");
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = UnknownContentBlock.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(name = "text", value = TextBlock.class),
            @JsonSubTypes.Type(name = "thinking", value = ThinkingBlock.class),
            @JsonSubTypes.Type(name = "tool_use", value = ToolUseBlock.class),
            @JsonSubTypes.Type(name = "tool_result", value = ToolResultBlock.class),
    })
    sealed interface ContentBlock
            permits TextBlock, ThinkingBlock, ToolUseBlock, ToolResultBlock, UnknownContentBlock {}

    record TextBlock(String text)
            implements ContentBlock
    {
        TextBlock
        {
            requireNonNull(text, "text is null");
        }
    }

    record ThinkingBlock()
            implements ContentBlock {}

    record ToolUseBlock(String id, String name, ToolInput input)
            implements ContentBlock
    {
        ToolUseBlock
        {
            requireNonNull(id, "id is null");
            requireNonNull(name, "name is null");
            requireNonNull(input, "input is null");
        }
    }

    record ToolResultBlock(
            @JsonProperty("tool_use_id") String toolUseId,
            JsonNode content,
            @JsonProperty("is_error") boolean error)
            implements ContentBlock
    {
        ToolResultBlock
        {
            requireNonNull(toolUseId, "toolUseId is null");
            requireNonNull(content, "content is null");
        }

        String text()
        {
            return content.isTextual() ? content.textValue() : content.toString();
        }
    }

    record UnknownContentBlock()
            implements ContentBlock {}

    record ToolInput(
            Optional<String> command,
            Optional<String> description,
            @JsonProperty("file_path") Optional<String> filePath)
    {
        ToolInput
        {
            requireNonNull(command, "command is null");
            requireNonNull(description, "description is null");
            requireNonNull(filePath, "filePath is null");
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = UnknownApiEvent.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(name = "content_block_delta", value = ContentBlockDeltaEvent.class),
    })
    sealed interface ApiEvent
            permits ContentBlockDeltaEvent, UnknownApiEvent {}

    record ContentBlockDeltaEvent(int index, Delta delta)
            implements ApiEvent
    {
        ContentBlockDeltaEvent
        {
            requireNonNull(delta, "delta is null");
        }
    }

    record UnknownApiEvent()
            implements ApiEvent {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = UnknownDelta.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(name = "text_delta", value = TextDelta.class),
            @JsonSubTypes.Type(name = "input_json_delta", value = InputJsonDelta.class),
            @JsonSubTypes.Type(name = "thinking_delta", value = ThinkingDelta.class),
            @JsonSubTypes.Type(name = "signature_delta", value = SignatureDelta.class),
    })
    sealed interface Delta
            permits TextDelta, InputJsonDelta, ThinkingDelta, SignatureDelta, UnknownDelta {}

    record TextDelta(String text)
            implements Delta
    {
        TextDelta
        {
            requireNonNull(text, "text is null");
        }
    }

    record InputJsonDelta(@JsonProperty("partial_json") String partialJson)
            implements Delta
    {
        InputJsonDelta
        {
            requireNonNull(partialJson, "partialJson is null");
        }
    }

    record ThinkingDelta()
            implements Delta {}

    record SignatureDelta()
            implements Delta {}

    record UnknownDelta()
            implements Delta {}

    record Usage(
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("cache_creation_input_tokens") long cacheCreationInputTokens,
            @JsonProperty("cache_read_input_tokens") long cacheReadInputTokens,
            @JsonProperty("output_tokens") long outputTokens) {}

    record RateLimitInfo(String status, @JsonProperty("rateLimitType") Optional<String> rateLimitType)
    {
        RateLimitInfo
        {
            requireNonNull(status, "status is null");
            requireNonNull(rateLimitType, "rateLimitType is null");
        }
    }
}
