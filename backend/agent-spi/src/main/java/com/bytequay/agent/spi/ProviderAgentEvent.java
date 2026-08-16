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
import static com.bytequay.agent.spi.AgentChecks.checkPresent;

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
            checkPresent(providerTimestamp, "providerTimestamp");
            checkPresent(phase, "phase");
            checkPresent(text, "text");
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
            checkPresent(providerTimestamp, "providerTimestamp");
            checkPresent(items, "items");
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
            checkPresent(providerTimestamp, "providerTimestamp");
            checkNotBlank(toolCallId, "toolCallId");
            checkNotBlank(name, "name");
            checkPresent(state, "state");
            checkPresent(summary, "summary");
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
            checkPresent(providerTimestamp, "providerTimestamp");
            checkNotBlank(commandId, "commandId");
            checkPresent(state, "state");
            checkPresent(argv, "argv");
            argv = ImmutableList.copyOf(argv);
            checkPresent(output, "output");
            checkPresent(exitCode, "exitCode");
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
            checkPresent(providerTimestamp, "providerTimestamp");
            checkNotBlank(relativePath, "relativePath");
            checkPresent(change, "change");
            checkPresent(diff, "diff");
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
            checkPresent(providerTimestamp, "providerTimestamp");
            checkNotBlank(providerRequestId, "providerRequestId");
            checkNotBlank(title, "title");
            checkPresent(detail, "detail");
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
            checkPresent(sample, "sample");
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
            checkPresent(providerTimestamp, "providerTimestamp");
            checkPresent(phase, "phase");
            checkPresent(message, "message");
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
            checkPresent(providerTimestamp, "providerTimestamp");
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
            checkPresent(state, "state");
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
