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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.bytequay.agent.spi.ProviderAgentEventSource.root;
import static com.google.common.base.Preconditions.checkArgument;
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

    /**
     * Returns the opaque provider routing and item identity supplied with the event.
     *
     * @return provider event source, with absent fields left absent
     */
    ProviderAgentEventSource source();

    record Message(
            Optional<String> providerEventId,
            Optional<Instant> providerTimestamp,
            MessageKind messageKind,
            MessagePhase phase,
            String text,
            Optional<String> parentToolUseId,
            ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public Message(
                Optional<String> providerEventId,
                Optional<Instant> providerTimestamp,
                MessagePhase phase,
                String text,
                Optional<String> parentToolUseId)
        {
            this(providerEventId, providerTimestamp, MessageKind.ANSWER, phase, text, parentToolUseId, root());
        }

        /** An answer, which is what every message was before reasoning summaries and plan text were mapped. */
        public Message(
                Optional<String> providerEventId,
                Optional<Instant> providerTimestamp,
                MessagePhase phase,
                String text,
                Optional<String> parentToolUseId,
                ProviderAgentEventSource source)
        {
            this(providerEventId, providerTimestamp, MessageKind.ANSWER, phase, text, parentToolUseId, source);
        }

        public Message
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            // A record written before message kinds existed carries none, and every one was an answer.
            messageKind = messageKind == null ? MessageKind.ANSWER : messageKind;
            requireNonNull(phase, "phase is null");
            requireNonNull(text, "text is null");
            checkOptionalText(parentToolUseId, "parentToolUseId");
            source = legacySource(source);
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
            List<PlanItem> items,
            ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public Plan(
                Optional<String> providerEventId,
                Optional<Instant> providerTimestamp,
                List<PlanItem> items)
        {
            this(providerEventId, providerTimestamp, items, root());
        }

        public Plan
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            items = ImmutableList.copyOf(items);
            source = legacySource(source);
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
            Optional<String> parentToolUseId,
            ProviderAgentEventSource source,
            Optional<Duration> duration)
            implements ProviderAgentEvent
    {
        public Tool(
                Optional<String> providerEventId,
                Optional<Instant> providerTimestamp,
                String toolCallId,
                String name,
                ActivityState state,
                String summary,
                Optional<String> parentToolUseId)
        {
            this(providerEventId, providerTimestamp, toolCallId, name, state, summary, parentToolUseId, ProviderAgentEventSource.item(toolCallId));
        }

        public Tool(
                Optional<String> providerEventId,
                Optional<Instant> providerTimestamp,
                String toolCallId,
                String name,
                ActivityState state,
                String summary,
                Optional<String> parentToolUseId,
                ProviderAgentEventSource source)
        {
            this(providerEventId, providerTimestamp, toolCallId, name, state, summary, parentToolUseId, source, Optional.empty());
        }

        public Tool
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            checkNotBlank(toolCallId, "toolCallId");
            checkNotBlank(name, "name");
            requireNonNull(state, "state is null");
            requireNonNull(summary, "summary is null");
            checkOptionalText(parentToolUseId, "parentToolUseId");
            source = source == null ? ProviderAgentEventSource.item(toolCallId) : source;
            requireNonNull(duration, "duration is null");
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
            boolean truncated,
            ProviderAgentEventSource source,
            Optional<String> cwd,
            Optional<Duration> duration,
            List<CommandAction> actions)
            implements ProviderAgentEvent
    {
        public Command(
                Optional<String> providerEventId,
                Optional<Instant> providerTimestamp,
                String commandId,
                ActivityState state,
                List<String> argv,
                Optional<String> output,
                Optional<Integer> exitCode,
                boolean truncated)
        {
            this(providerEventId, providerTimestamp, commandId, state, argv, output, exitCode, truncated, ProviderAgentEventSource.item(commandId));
        }

        public Command(
                Optional<String> providerEventId,
                Optional<Instant> providerTimestamp,
                String commandId,
                ActivityState state,
                List<String> argv,
                Optional<String> output,
                Optional<Integer> exitCode,
                boolean truncated,
                ProviderAgentEventSource source)
        {
            this(providerEventId, providerTimestamp, commandId, state, argv, output, exitCode, truncated, source,
                    Optional.empty(), Optional.empty(), ImmutableList.of());
        }

        public Command
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            checkNotBlank(commandId, "commandId");
            requireNonNull(state, "state is null");
            argv = ImmutableList.copyOf(argv);
            requireNonNull(output, "output is null");
            requireNonNull(exitCode, "exitCode is null");
            checkArgument(argv.stream().noneMatch(String::isBlank), "argv contains a blank value");
            source = source == null ? ProviderAgentEventSource.item(commandId) : source;
            checkOptionalText(cwd, "cwd");
            requireNonNull(duration, "duration is null");
            // A record written before actions were carried has no field for them, which reads as none.
            actions = actions == null ? ImmutableList.of() : ImmutableList.copyOf(actions);
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
            Optional<String> diff,
            ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public FileChange(
                Optional<String> providerEventId,
                Optional<Instant> providerTimestamp,
                String relativePath,
                ChangeKind change,
                Optional<String> diff)
        {
            this(providerEventId, providerTimestamp, relativePath, change, diff, root());
        }

        public FileChange
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            checkNotBlank(relativePath, "relativePath");
            requireNonNull(change, "change is null");
            requireNonNull(diff, "diff is null");
            Path path = Path.of(relativePath);
            checkArgument(!path.isAbsolute() && !path.normalize().startsWith(".."), "relativePath is unsafe");
            source = legacySource(source);
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
            String approvalId,
            String title,
            String detail,
            ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public Approval(
                Optional<String> providerEventId,
                Optional<Instant> providerTimestamp,
                String approvalId,
                String title,
                String detail)
        {
            this(providerEventId, providerTimestamp, approvalId, title, detail, root());
        }

        public Approval
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            checkNotBlank(approvalId, "approvalId");
            checkNotBlank(title, "title");
            requireNonNull(detail, "detail is null");
            source = legacySource(source);
        }

        @Override
        public Kind kind()
        {
            return Kind.APPROVAL;
        }
    }

    record Usage(ProviderUsageSample sample, ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public Usage(ProviderUsageSample sample)
        {
            this(sample, root());
        }

        public Usage
        {
            requireNonNull(sample, "sample is null");
            source = legacySource(source);
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
            String message,
            ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public Lifecycle(
                Optional<String> providerEventId,
                Optional<Instant> providerTimestamp,
                LifecyclePhase phase,
                String message)
        {
            this(providerEventId, providerTimestamp, phase, message, root());
        }

        public Lifecycle
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            requireNonNull(phase, "phase is null");
            requireNonNull(message, "message is null");
            source = legacySource(source);
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
            boolean retryable,
            ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public Error(
                Optional<String> providerEventId,
                Optional<Instant> providerTimestamp,
                String code,
                String message,
                boolean retryable)
        {
            this(providerEventId, providerTimestamp, code, message, retryable, root());
        }

        public Error
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(providerTimestamp, "providerTimestamp is null");
            checkNotBlank(code, "code");
            checkNotBlank(message, "message");
            source = legacySource(source);
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

    enum MessageKind
    {
        ANSWER,
        REASONING_SUMMARY,
        PLAN_TEXT
    }

    /** What a shell command did to one worktree-relative path, as the provider parsed it. */
    record CommandAction(CommandActionKind kind, Optional<String> path, Optional<String> query)
    {
        public CommandAction
        {
            requireNonNull(kind, "kind is null");
            checkOptionalText(path, "path");
            checkOptionalText(query, "query");
        }
    }

    enum CommandActionKind
    {
        READ,
        LIST_FILES,
        SEARCH,
        OTHER
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

    private static ProviderAgentEventSource legacySource(ProviderAgentEventSource source)
    {
        // Schema-v1 task logs predate explicit source metadata.
        return source == null ? root() : source;
    }
}
