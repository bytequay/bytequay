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
package com.bytequay.agent.spi;

import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static java.util.Objects.requireNonNull;

/**
 * Closed provider-neutral event model shared by every agent driver.
 *
 * <p>Drivers translate native Codex or Claude protocol messages into these
 * values before calling {@link AgentEventSink}. Events may contain supported
 * user-visible activity only; secrets, raw provider payloads, and hidden
 * chain-of-thought must not enter this model.
 */
public sealed interface ProviderAgentEvent
        permits ProviderAgentEvent.Message, ProviderAgentEvent.Plan, ProviderAgentEvent.Tool,
        ProviderAgentEvent.Command, ProviderAgentEvent.FileChange, ProviderAgentEvent.Approval,
        ProviderAgentEvent.Usage, ProviderAgentEvent.Lifecycle, ProviderAgentEvent.Error
{
    /**
     * Returns the normalized event category used by logs and SSE.
     *
     * @return provider-neutral event category
     */
    Kind kind();

    /**
     * Returns a provider identity suitable for duplicate suppression when one was
     * supplied by the provider.
     *
     * @return optional provider event identifier
     */
    Optional<String> providerEventId();

    /**
     * Returns provider event time when the protocol supplies it.
     *
     * <p>The backend still assigns its own receipt time to the persisted envelope.
     *
     * @return optional provider timestamp
     */
    Optional<Instant> providerTimestamp();

    record Message(
            Optional<String> providerEventId,
            Optional<Instant> providerTimestamp,
            MessagePhase phase,
            String text,
            Optional<String> parentToolUseId)
            implements ProviderAgentEvent
    {
        public Message
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            requireNonNull(phase, "phase is null");
            requireNonNull(text, "text is null");
            checkOptionalText(parentToolUseId, "parentToolUseId");
        }

        @Override
        public Kind kind()
        {
            return Kind.MESSAGE;
        }
    }

    record Plan(
            Optional<String> providerEventId,
            Optional<Instant> providerTimestamp,
            List<PlanItem> items)
            implements ProviderAgentEvent
    {
        public Plan
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            requireNonNull(items, "items is null");
            items = ImmutableList.copyOf(items);
        }

        @Override
        public Kind kind()
        {
            return Kind.PLAN;
        }
    }

    record Tool(
            Optional<String> providerEventId,
            Optional<Instant> providerTimestamp,
            String toolCallId,
            String name,
            ActivityState state,
            String summary,
            Optional<String> parentToolUseId)
            implements ProviderAgentEvent
    {
        public Tool
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            checkNotBlank(toolCallId, "toolCallId");
            checkNotBlank(name, "name");
            requireNonNull(state, "state is null");
            requireNonNull(summary, "summary is null");
            checkOptionalText(parentToolUseId, "parentToolUseId");
        }

        @Override
        public Kind kind()
        {
            return Kind.TOOL;
        }
    }

    record Command(
            Optional<String> providerEventId,
            Optional<Instant> providerTimestamp,
            String commandId,
            ActivityState state,
            List<String> argv,
            Optional<String> output,
            Optional<Integer> exitCode,
            boolean truncated)
            implements ProviderAgentEvent
    {
        public Command
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            checkNotBlank(commandId, "commandId");
            requireNonNull(state, "state is null");
            requireNonNull(argv, "argv is null");
            argv = ImmutableList.copyOf(argv);
            requireNonNull(output, "output is null");
            requireNonNull(exitCode, "exitCode is null");
            if (argv.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("argv contains a blank value");
            }
        }

        @Override
        public Kind kind()
        {
            return Kind.COMMAND;
        }
    }

    record FileChange(
            Optional<String> providerEventId,
            Optional<Instant> providerTimestamp,
            String relativePath,
            ChangeKind change,
            Optional<String> diff)
            implements ProviderAgentEvent
    {
        public FileChange
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            checkNotBlank(relativePath, "relativePath");
            requireNonNull(change, "change is null");
            requireNonNull(diff, "diff is null");
            Path path = Path.of(relativePath);
            if (path.isAbsolute() || path.normalize().startsWith("..")) {
                throw new IllegalArgumentException("relativePath is unsafe");
            }
        }

        @Override
        public Kind kind()
        {
            return Kind.FILE;
        }
    }

    record Approval(
            Optional<String> providerEventId,
            Optional<Instant> providerTimestamp,
            String providerRequestId,
            String title,
            String detail)
            implements ProviderAgentEvent
    {
        public Approval
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            checkNotBlank(providerRequestId, "providerRequestId");
            checkNotBlank(title, "title");
            requireNonNull(detail, "detail is null");
        }

        @Override
        public Kind kind()
        {
            return Kind.APPROVAL;
        }
    }

    record Usage(ProviderUsageSample sample)
            implements ProviderAgentEvent
    {
        public Usage
        {
            requireNonNull(sample, "sample is null");
        }

        @Override
        public Kind kind()
        {
            return Kind.USAGE;
        }

        @Override
        public Optional<String> providerEventId()
        {
            return sample.providerEventId();
        }

        @Override
        public Optional<Instant> providerTimestamp()
        {
            return Optional.of(sample.observedAt());
        }
    }

    record Lifecycle(
            Optional<String> providerEventId,
            Optional<Instant> providerTimestamp,
            LifecyclePhase phase,
            String message)
            implements ProviderAgentEvent
    {
        public Lifecycle
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            requireNonNull(phase, "phase is null");
            requireNonNull(message, "message is null");
        }

        @Override
        public Kind kind()
        {
            return Kind.LIFECYCLE;
        }
    }

    record Error(
            Optional<String> providerEventId,
            Optional<Instant> providerTimestamp,
            String code,
            String message,
            boolean retryable)
            implements ProviderAgentEvent
    {
        public Error
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            checkNotBlank(code, "code");
            checkNotBlank(message, "message");
        }

        @Override
        public Kind kind()
        {
            return Kind.ERROR;
        }
    }

    record PlanItem(String text, PlanItemState state)
    {
        public PlanItem
        {
            checkNotBlank(text, "text");
            requireNonNull(state, "state is null");
        }
    }

    enum Kind
    {
        MESSAGE,
        PLAN,
        TOOL,
        COMMAND,
        FILE,
        APPROVAL,
        USAGE,
        LIFECYCLE,
        ERROR
    }

    enum MessagePhase
    {
        PARTIAL,
        FINAL
    }

    enum ActivityState
    {
        STARTED,
        PROGRESS,
        COMPLETED,
        FAILED
    }

    enum ChangeKind
    {
        ADDED,
        MODIFIED,
        DELETED,
        RENAMED
    }

    enum PlanItemState
    {
        PENDING,
        IN_PROGRESS,
        COMPLETED
    }

    enum LifecyclePhase
    {
        SESSION_OPENED,
        TURN_STARTED,
        TURN_COMPLETED,
        RETRYING,
        RATE_LIMITED,
        INTERRUPTED
    }
}
