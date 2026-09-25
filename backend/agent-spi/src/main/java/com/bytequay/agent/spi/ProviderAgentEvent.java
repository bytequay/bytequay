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
        ProviderAgentEvent.Command, ProviderAgentEvent.FileChange,
        ProviderAgentEvent.Usage, ProviderAgentEvent.Lifecycle, ProviderAgentEvent.Error, ProviderAgentEvent.DevelopmentResult
{
    /**
     * Returns a provider identity suitable for duplicate suppression when one was
     * supplied by the provider.
     *
     * @return optional provider event identifier
     */
    Optional<String> providerEventId();

    /**
     * Returns the opaque provider routing and item identity supplied with the event.
     *
     * @return provider event source, with absent fields left absent
     */
    ProviderAgentEventSource source();

    record DevelopmentResult(
            Optional<String> providerEventId,
            DevelopmentResultReport report,
            ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public DevelopmentResult
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(report, "report is null");
            requireNonNull(source, "source is null");
        }
    }

    record Message(
            Optional<String> providerEventId,
            MessageKind messageKind,
            MessagePhase phase,
            String text,
            ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public Message(
                Optional<String> providerEventId,
                MessagePhase phase,
                String text)
        {
            this(providerEventId, MessageKind.ANSWER, phase, text, root());
        }

        /** An answer, which is what every message was before reasoning summaries and plan text were mapped. */
        public Message(
                Optional<String> providerEventId,
                MessagePhase phase,
                String text,
                ProviderAgentEventSource source)
        {
            this(providerEventId, MessageKind.ANSWER, phase, text, source);
        }

        public Message
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(messageKind, "messageKind is null");
            requireNonNull(phase, "phase is null");
            requireNonNull(text, "text is null");
            requireNonNull(source, "source is null");
        }
    }

    record Plan(
            Optional<String> providerEventId,
            List<PlanItem> items,
            ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public Plan(
                Optional<String> providerEventId,
                List<PlanItem> items)
        {
            this(providerEventId, items, root());
        }

        public Plan
        {
            checkOptionalText(providerEventId, "providerEventId");
            items = ImmutableList.copyOf(items);
            requireNonNull(source, "source is null");
        }
    }

    record Tool(
            Optional<String> providerEventId,
            String name,
            ActivityState state,
            String summary,
            ProviderAgentEventSource source,
            Optional<Duration> duration)
            implements ProviderAgentEvent
    {
        public Tool(
                Optional<String> providerEventId,
                String name,
                ActivityState state,
                String summary,
                ProviderAgentEventSource source)
        {
            this(providerEventId, name, state, summary, source, Optional.empty());
        }

        public Tool
        {
            checkOptionalText(providerEventId, "providerEventId");
            checkNotBlank(name, "name");
            requireNonNull(state, "state is null");
            requireNonNull(summary, "summary is null");
            requireNonNull(source, "source is null");
            requireNonNull(duration, "duration is null");
        }
    }

    record Command(
            Optional<String> providerEventId,
            ActivityState state,
            List<String> argv,
            Optional<String> output,
            Optional<Integer> exitCode,
            boolean truncated,
            ProviderAgentEventSource source,
            Optional<String> cwd,
            Optional<Duration> duration,
            List<CommandAction> actions,
            boolean background,
            Optional<String> description)
            implements ProviderAgentEvent
    {
        public Command(
                Optional<String> providerEventId,
                ActivityState state,
                List<String> argv,
                Optional<String> output,
                Optional<Integer> exitCode,
                boolean truncated,
                ProviderAgentEventSource source)
        {
            this(providerEventId, state, argv, output, exitCode, truncated, source, Optional.empty(), Optional.empty(), ImmutableList.of());
        }

        public Command(
                Optional<String> providerEventId,
                ActivityState state,
                List<String> argv,
                Optional<String> output,
                Optional<Integer> exitCode,
                boolean truncated,
                ProviderAgentEventSource source,
                Optional<String> cwd,
                Optional<Duration> duration,
                List<CommandAction> actions)
        {
            this(providerEventId, state, argv, output, exitCode, truncated, source, cwd, duration, actions, false, Optional.empty());
        }

        public Command
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(state, "state is null");
            argv = ImmutableList.copyOf(argv);
            requireNonNull(output, "output is null");
            requireNonNull(exitCode, "exitCode is null");
            checkArgument(argv.stream().noneMatch(String::isBlank), "argv contains a blank value");
            requireNonNull(source, "source is null");
            checkOptionalText(cwd, "cwd");
            requireNonNull(duration, "duration is null");
            // A record written before actions were carried has no field for them, which reads as none.
            actions = actions == null ? ImmutableList.of() : ImmutableList.copyOf(actions);
            checkOptionalText(description, "description");
        }
    }

    record FileChange(
            Optional<String> providerEventId,
            String relativePath,
            ChangeKind change,
            Optional<String> diff,
            ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public FileChange(
                Optional<String> providerEventId,
                String relativePath,
                ChangeKind change,
                Optional<String> diff)
        {
            this(providerEventId, relativePath, change, diff, root());
        }

        public FileChange
        {
            checkOptionalText(providerEventId, "providerEventId");
            checkNotBlank(relativePath, "relativePath");
            requireNonNull(change, "change is null");
            requireNonNull(diff, "diff is null");
            Path path = Path.of(relativePath);
            checkArgument(!path.isAbsolute() && !path.normalize().startsWith(".."), "relativePath is unsafe");
            requireNonNull(source, "source is null");
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
            requireNonNull(source, "source is null");
        }

        @Override
        public Optional<String> providerEventId()
        {
            return sample.providerEventId();
        }
    }

    record Lifecycle(
            Optional<String> providerEventId,
            LifecyclePhase phase,
            String message,
            ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public Lifecycle(
                Optional<String> providerEventId,
                LifecyclePhase phase,
                String message)
        {
            this(providerEventId, phase, message, root());
        }

        public Lifecycle
        {
            checkOptionalText(providerEventId, "providerEventId");
            requireNonNull(phase, "phase is null");
            requireNonNull(message, "message is null");
            requireNonNull(source, "source is null");
        }
    }

    record Error(
            Optional<String> providerEventId,
            String code,
            String message,
            boolean retryable,
            ProviderAgentEventSource source)
            implements ProviderAgentEvent
    {
        public Error(
                Optional<String> providerEventId,
                String code,
                String message,
                boolean retryable)
        {
            this(providerEventId, code, message, retryable, root());
        }

        public Error
        {
            checkOptionalText(providerEventId, "providerEventId");
            checkNotBlank(code, "code");
            checkNotBlank(message, "message");
            requireNonNull(source, "source is null");
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

    enum MessagePhase
    {
        PARTIAL,
        FINAL
    }

    enum MessageKind
    {
        ANSWER,
        /** An assistant observation that is not the turn's terminal answer. */
        COMMENTARY,
        REASONING_SUMMARY,
        PLAN_TEXT,
        /** The message an agent was given; on a subagent, the prompt its parent spawned it with. */
        PROMPT
    }

    /** What a shell command did, with the provider's display name and any worktree-relative path. */
    record CommandAction(CommandActionKind kind, Optional<String> name, Optional<String> path, Optional<String> query)
    {
        public CommandAction
        {
            requireNonNull(kind, "kind is null");
            checkOptionalText(name, "name");
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
        INTERRUPTED,
        /** A subagent became known; the source names it and its parent, the message is its label. */
        AGENT_STARTED,
        AGENT_COMPLETED,
        AGENT_FAILED,
        /** Brackets events recovered from a thread's history rather than seen live. */
        BACKFILL_STARTED,
        BACKFILL_COMPLETED
    }
}
