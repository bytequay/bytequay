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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;

import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * One running (or paused) agent session. Owned by the backend for
 * the lifetime of a {@link com.bytequay.app.domain.Thread} that is in
 * a non-terminal {@link ThreadStatus}; restored on app start by
 * spawning a fresh session and replaying persisted history.
 *
 * <p>Two implementations land in later slices, one per
 * {@link ThreadKind}:
 * <ul>
 *   <li>{@code ClaudeCodeCliThreadAgent} — wraps a {@code claude code}
 *       subprocess and parses its {@code stream-json} stdout into
 *       {@link StreamEvent}s.</li>
 *   <li>{@code LogicLoopSession} — runs the loop in-JVM, calling a
 *       model API directly and synthesizing the same event shapes.</li>
 * </ul>
 *
 * <p>All implementations are responsible for persisting every event
 * via {@link com.bytequay.app.repository.ThreadStore} before fanning
 * it out to subscribers, so a refresh / restart never loses state.
 *
 * <p>Methods are intentionally synchronous from the caller's view —
 * {@link #send}, {@link #interrupt}, etc. enqueue work and return
 * immediately; observable state changes arrive through
 * {@link #subscribeToEvents}. Implementations are expected to be
 * thread-safe.
 *
 * <p>Lifecycle:
 * <pre>
 *  STATES                       TRANSITIONS
 *  ──────                       ───────────
 *  IDLE       waiting for       IDLE     ──{@link #send send()}─────▶ RUNNING
 *             the next turn     RUNNING  ──turn done──────▶ IDLE
 *  RUNNING    subprocess        RUNNING  ──{@link #interrupt interrupt()}─▶ IDLE   ☆
 *             alive; one shot
 *             per turn          IDLE     ──{@link #pause pause()}────▶ AWAITING
 *  AWAITING   paused; refuses   AWAITING ──{@link #resume resume()}──▶ IDLE
 *             new turns         ANY      ──{@link #stop stop()}──────▶ STOPPED
 *  STOPPED    terminal
 *
 *  ☆ interrupt sets the userInterrupted flag BEFORE destroy() so the
 *    exit handler classifies the result as a user-cancel and lands at
 *    IDLE — without it the destroy() path is otherwise scored as
 *    ERRORED, forcing the user to click Resume just to keep typing.
 * </pre>
 *
 * <p>Pause vs interrupt are not interchangeable: pause blocks
 * <em>future</em> turns (the "stepping away" case), interrupt kills
 * the <em>current</em> subprocess (the "this agent is going the wrong
 * way" case). Pause leaves the agent at IDLE; interrupt brings RUNNING
 * back to IDLE.
 *
 * <p>Side-effect tools (push, post_comment, ship) don't transition the
 * thread directly; they park the active <em>task</em> at
 * {@code AWAITING_REVIEW} and write a Notification. The user resolves
 * with approve/discard — see
 * {@link com.bytequay.app.service.threads.ParkedProposalService}.
 */
public interface ThreadAgent
{
    /** Stable session id; matches {@code Thread.agentSessionId} for
     *  CLI sessions, otherwise a synthetic UUID. */
    String id();

    ThreadKind kind();

    /** e.g. {@code "claude-code"}, {@code "anthropic"},
     *  {@code "deepseek"}. Free-form so we don't have to migrate
     *  schemas every time a provider lands. */
    String provider();

    String model();

    /** Working directory the loop runs against. Absolute path. */
    String workingDir();

    /** Branch sniffed at session start; {@code null} if the
     *  working directory is not a git checkout. */
    String branchName();

    ThreadStatus status();

    /** Human-readable detail of the most recent failure that drove the
     *  session to {@link ThreadStatus#ERRORED} — e.g. the CLI's exit code
     *  and stderr tail. Null when the session never failed, or failed by
     *  throwing (the thrown exception's message is used instead). The
     *  scheduler surfaces this on a failed turn that ended ERRORED without
     *  an exception, so a swallowed subprocess failure doesn't reduce to a
     *  generic "the turn failed" with no cause. */
    default String lastErrorDetail()
    {
        return null;
    }

    /** Cheap snapshot for the header strip — no I/O. */
    AgentMetrics metrics();

    /** All persisted messages in {@code seq} order. Used to seed
     *  the conversation pane on first load. */
    List<ThreadMessage> history();

    /** Send user input as the next turn. No-op if the session is in a
     *  terminal status. The returned stage completes once the turn has
     *  released its local resources. */
    CompletionStage<Void> send(String userInput);

    /** Best-effort cancel of the currently-running turn. The
     *  session moves to {@link ThreadStatus#IDLE} once the loop
     *  acknowledges. Equivalent to ESC in {@code claude code}. */
    void interrupt();

    /** Stop the loop without killing the underlying process /
     *  context — {@link #resume} can pick back up. */
    void pause();

    void resume();

    /** Terminal — releases the subprocess / loop and transitions
     *  the thread to {@link ThreadStatus#COMPLETED} (or
     *  {@link ThreadStatus#ERRORED} if a failure prompted the stop). */
    void stop();

    /** Inject a permission prompt — called by the MCP controller when
     *  Claude's {@code approval_prompt} tool fires. Surfaces a
     *  {@link StreamEvent.PermissionRequested} in the conversation
     *  pane and persists a row so the prompt survives a refresh. */
    void notifyPermissionRequested(String callId, String toolName, String summary);

    /** User's response to a {@link StreamEvent.PermissionRequested}.
     *  Idempotent for the same {@code callId}; later calls are
     *  ignored. */
    void decide(String callId, PermissionDecision decision);

    /** Pre-authorise the next {@code count} invocations of
     *  {@code toolName} so the MCP gate auto-allows them without
     *  prompting the user. {@code count == -1} means "always for this
     *  tool" until the session ends. Budgets accumulate: granting 5
     *  twice gives 10. Bound to the session lifetime — a stopped or
     *  failed thread drops the map. */
    void grantToolBudget(String toolName, int count);

    /** Consume one slot of the budget for {@code toolName}. Returns
     *  the budget left after the consumption when a slot was drained,
     *  or {@link java.util.OptionalInt#empty()} when no budget was
     *  available and the MCP gate should fall through to the normal
     *  prompt. A consumed ALWAYS grant returns {@code -1}; a finite
     *  grant returns its non-negative remainder. */
    OptionalInt tryConsumeToolBudget(String toolName);

    /** Surface a {@link StreamEvent.PermissionAutoAllowed} after the
     *  MCP gate has drained one budget slot, so the conversation pane
     *  can show "auto-approved · N left for &lt;tool&gt;" inline next
     *  to the tool call. Persists a row so the notice survives a
     *  refresh. */
    void notifyPermissionAutoAllowed(String callId, String toolName, int remaining);

    /** Subscribe to the live event stream. The returned {@link Runnable}
     *  unsubscribes when invoked. Library-neutral on purpose — the
     *  REST/WebSocket layer decides whether to wrap this in Reactor,
     *  Flow, or plain SSE. */
    Runnable subscribeToEvents(Consumer<StreamEvent> listener);
}
