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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessOutputSink;
import com.bytequay.agent.cli.process.ProcessSpec;
import com.bytequay.agent.cli.process.ProcessStream;
import com.bytequay.agent.cli.process.ProcessSupervisor;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.core.process.Utf8LineBuffer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.agent.cli.process.ProviderCli.failureMessage;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * One connection to the App Server process serving one task.
 *
 * <p>A task owns its own process, launched in that task's worktree, so this connection carries
 * exactly one session and nothing has to be routed between tasks. Closing it terminates the
 * process, which is what releases every thread that task held.
 *
 * <p>Three message directions share the one stdio transport, and only the first is a reply to
 * something ByteQuay asked for:
 *
 * <pre>
 *   ByteQuay --request--&gt; App Server   thread/start, turn/start, turn/interrupt, model/list
 *   ByteQuay &lt;-response-- App Server   completes the pending future holding that id
 *   ByteQuay &lt;-request--- App Server   approvals; ByteQuay has to answer these
 *   ByteQuay &lt;-notify---- App Server   turn and item activity; nothing to answer
 * </pre>
 *
 * <p>A single reader parses all of it, so a handler that waited for a response while running on
 * the reader would be waiting for itself. Every inbound message is handed to
 * {@link CodexMessageDispatcher} and the reader returns immediately; {@link #request} fails fast
 * rather than stalling if it is ever called from the reader.
 *
 * <pre>
 *   stdout --&gt; Utf8LineBuffer --&gt; receive()
 *                                   |- response       --&gt; pending future completes
 *                                   |- notification   --+
 *                                   '- server request --+--&gt; dispatcher --&gt; one virtual
 *                                                                consumer --&gt; CodexSession
 * </pre>
 */
final class CodexAppServerClient
        implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(CodexAppServerClient.class);
    private static final Logger sendTiming = LoggerFactory.getLogger("com.bytequay.agent.SendTiming");
    private static final Duration START_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_PROTOCOL_LINE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIAGNOSTIC_LINE_BYTES = 16 * 1024;

    private final ProcessSupervisor supervisor;
    private final ProcessSpec processSpec;
    private final SecretRedactor redactor;
    private final Optional<DeepSeekLoopback> deepSeekLoopback;
    private final CodexProtocolCodec codec;
    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final AtomicReference<String> codexHome = new AtomicReference<>();
    private final AtomicReference<ProcessExit.TreeTermination> treeTermination = new AtomicReference<>();
    private final ConcurrentMap<Long, CompletableFuture<CodexProtocol.JsonRpcEnvelope>> pending = new ConcurrentHashMap<>();
    private final CodexMessageDispatcher dispatcher = new CodexMessageDispatcher();
    private final Utf8LineBuffer protocolLines = new Utf8LineBuffer(MAX_PROTOCOL_LINE_BYTES, true, this::receive);
    private final Utf8LineBuffer diagnosticLines;
    private final String clientId = UUID.randomUUID().toString();
    private final FirstOutputTiming firstProviderOutput = new FirstOutputTiming();

    private volatile Thread readerThread;
    private volatile String timingTaskId = "-";
    private volatile String timingThreadId = "-";
    private volatile String timingTurnId = "-";
    private volatile long timingTurnRequestId = -1;
    private volatile ManagedProcess process;
    private volatile boolean closed;
    private volatile boolean workFinished;

    CodexAppServerClient(ProcessSupervisor supervisor, ProcessSpec processSpec, ObjectMapper objectMapper, SecretRedactor redactor, Optional<DeepSeekLoopback> deepSeekLoopback)
    {
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.processSpec = requireNonNull(processSpec, "processSpec is null");
        this.redactor = requireNonNull(redactor, "redactor is null");
        this.deepSeekLoopback = requireNonNull(deepSeekLoopback, "deepSeekLoopback is null");
        this.codec = new CodexProtocolCodec(objectMapper);
        this.diagnosticLines = new Utf8LineBuffer(
                MAX_DIAGNOSTIC_LINE_BYTES,
                false,
                line -> log.atLevel(diagnosticLevel(line)).log("Codex App Server: {}", this.redactor.redact(line)));
    }

    static Level diagnosticLevel(String line)
    {
        // A successful policy denial is expected; hook failures and other provider diagnostics remain visible.
        String router = "codex_core::tools::router: error=";
        String denial = " blocked by PreToolUse hook: ByteQuay review child scope:";
        return line.contains(router + "Tool call" + denial) || line.contains(router + "Command" + denial) ? Level.DEBUG : Level.WARN;
    }

    <T> T request(String method, Object params, Class<T> responseType, Duration timeout)
    {
        requireNonNull(method, "method is null");
        requireNonNull(params, "params is null");
        requireNonNull(responseType, "responseType is null");
        requireNonNull(timeout, "timeout is null");
        // The reader is the only thread that can deliver this response, so waiting for one here
        // would be waiting on itself. Fail loudly instead of stalling until the timeout.
        checkState(Thread.currentThread() != readerThread, "Codex request %s was issued from the App Server reader thread", method);
        // These guards run before request bytes exist, so delivery-sensitive callers can safely
        // distinguish them from a failed write or a missing response.
        if (closed || disconnected.get()) {
            throw new RequestNotSent("Codex App Server is unavailable");
        }
        if (process == null) {
            throw new RequestNotSent("Codex App Server has not been started");
        }
        CodexProtocol.JsonRpcEnvelope response = awaitRequest(method, params, timeout);
        return codec.decodeResult(response.result().orElseThrow(() -> new AgentUnavailableException("Codex response has no result: " + method)), responseType);
    }

    /**
     * Attaches the session that receives this connection's inbound messages.
     *
     * <p>Called before the first thread is started, so App Server cannot emit anything this
     * connection has nowhere to put.
     *
     * @param listener the session
     */
    void listen(CodexMessageDispatcher.Listener listener)
    {
        dispatcher.listen(listener);
    }

    void timingTask(String taskId)
    {
        timingTaskId = taskId;
    }

    void timingThread(String threadId)
    {
        timingThreadId = threadId;
    }

    void timingTurn(String turnId)
    {
        firstProviderOutput.cancel();
        timingTurnId = turnId;
    }

    void timing(String phase)
    {
        timing(phase, -1, "-");
    }

    private void timing(String phase, long requestId, String providerTurnId)
    {
        if (sendTiming.isDebugEnabled()) {
            timing(phase, requestId, providerTurnId, System.currentTimeMillis());
        }
    }

    private void timing(String phase, long requestId, String providerTurnId, long epochMs)
    {
        if (sendTiming.isDebugEnabled()) {
            sendTiming.debug("send-timing phase={} epochMs={} taskId={} turnId={} clientId={} providerThreadId={} providerTurnId={} requestId={}",
                    phase, epochMs, timingTaskId, timingTurnId, clientId,
                    redactor.redact(timingThreadId), redactor.redact(providerTurnId), requestId);
        }
    }

    /**
     * Launches the App Server process and completes the initialize handshake.
     *
     * <p>One task owns one process, so this runs once when the session is opened rather than
     * lazily on the first request. A failure here leaves nothing running.
     */
    void start()
    {
        synchronized (lifecycleLock) {
            if (closed) {
                throw new AgentUnavailableException("Codex App Server is closed");
            }
            checkState(process == null, "Codex App Server was already started");
            timing("codex.process.launch.begin");
            process = supervisor.prepare(processSpec).start(new ProcessOutputSink()
            {
                @Override
                public void onOutput(ProcessStream stream, ByteBuffer bytes)
                {
                    receiveOutput(stream, bytes);
                }

                @Override
                public void onTruncated(ProcessStream stream)
                {
                    outputTruncated(stream);
                }
            });
            timing("codex.process.launch.end");
            process.exit().whenComplete((result, failure) -> processExited(result, failure));

            try {
                // Declared for one method: thread/backgroundTerminals/list is experimental and is
                // the only inventory that can prove a thread owns no live process.
                CodexProtocol.InitializeParams initialize = new CodexProtocol.InitializeParams(
                        new CodexProtocol.ClientInfo("bytequay", "ByteQuay", "0.5.1"),
                        new CodexProtocol.InitializeCapabilities(true));
                timing("codex.initialize.begin");
                CodexProtocol.JsonRpcEnvelope initialized = awaitRequest("initialize", initialize, START_TIMEOUT);
                codexHome.set(initialized.result()
                        .map(result -> codec.decodeResult(result, CodexProtocol.InitializeResponse.class))
                        .map(CodexProtocol.InitializeResponse::codexHome)
                        .orElse(null));
                sendNotification("initialized", new CodexProtocol.EmptyParams());
                timing("codex.initialize.end");
            }
            catch (RuntimeException failure) {
                process.cancel();
                disconnect(new AgentUnavailableException("Unable to initialize Codex App Server", failure));
                throw failure;
            }
        }
    }

    private CodexProtocol.JsonRpcEnvelope awaitRequest(String method, Object params, Duration timeout)
    {
        long id = requestIds.incrementAndGet();
        CompletableFuture<CodexProtocol.JsonRpcEnvelope> response = new CompletableFuture<>();
        pending.put(id, response);
        try {
            if (method.equals("turn/start")) {
                timingTurnRequestId = id;
                if (sendTiming.isDebugEnabled()) {
                    firstProviderOutput.begin();
                }
            }
            sendRequest(new CodexProtocol.JsonRpcRequest(id, method, params));
            return await(response, timeout, method);
        }
        finally {
            // A timeout must not retain its future forever. Match the exact future so a response
            // racing this cleanup cannot remove anything else.
            pending.remove(id, response);
            if (timingTurnRequestId == id) {
                timingTurnRequestId = -1;
            }
        }
    }

    int pendingRequestCount()
    {
        return pending.size();
    }

    private void sendNotification(String method, Object params)
    {
        send(new CodexProtocol.JsonRpcNotification(method, params));
    }

    private void sendRequest(Object message)
    {
        send(message, true);
    }

    private void send(Object message)
    {
        send(message, false);
    }

    private void send(Object message, boolean request)
    {
        synchronized (writeLock) {
            ManagedProcess current = process;
            if (request && (closed || disconnected.get() || current == null)) {
                throw new RequestNotSent("Codex App Server input is unavailable");
            }
            if (current == null) {
                throw new AgentUnavailableException("Codex App Server input is unavailable");
            }
            byte[] bytes;
            try {
                bytes = (codec.encode(message) + "\n").getBytes(StandardCharsets.UTF_8);
            }
            catch (RuntimeException failure) {
                if (request) {
                    throw new RequestNotSent("Unable to encode Codex request", failure);
                }
                throw failure;
            }
            try {
                if (message instanceof CodexProtocol.JsonRpcRequest rpc && rpc.method().equals("turn/start")) {
                    timing("codex.turn.write.begin", rpc.id(), "-");
                }
                await(current.write(ByteBuffer.wrap(bytes)).toCompletableFuture(), WRITE_TIMEOUT, "input write");
                if (message instanceof CodexProtocol.JsonRpcRequest rpc && rpc.method().equals("turn/start")) {
                    timing("codex.turn.write.end", rpc.id(), "-");
                }
            }
            catch (RuntimeException failure) {
                AgentUnavailableException unavailable = new AgentUnavailableException("Unable to write to Codex App Server", failure);
                disconnect(unavailable);
                throw unavailable;
            }
        }
    }

    private void receiveOutput(ProcessStream stream, ByteBuffer bytes)
    {
        if (readerThread != Thread.currentThread()) {
            readerThread = Thread.currentThread();
        }
        try {
            if (stream == ProcessStream.STDOUT) {
                protocolLines.accept(bytes);
            }
            else {
                diagnosticLines.accept(bytes);
            }
        }
        catch (RuntimeException failure) {
            ManagedProcess current = process;
            if (current != null) {
                current.cancel();
            }
            disconnect(new AgentUnavailableException("Codex App Server protocol failed", failure));
        }
    }

    private void outputTruncated(ProcessStream stream)
    {
        if (stream == ProcessStream.STDERR) {
            log.warn("Codex App Server diagnostics were truncated");
            return;
        }
        ManagedProcess current = process;
        if (current != null) {
            current.cancel();
        }
        disconnect(new AgentUnavailableException("Codex App Server protocol output was truncated"));
    }

    private void processExited(ProcessExit result, Throwable failure)
    {
        deepSeekLoopback.ifPresent(DeepSeekLoopback::close);
        if (result != null) {
            // The original exit receipt may arrive after a newer explicit stop verification.
            treeTermination.compareAndSet(null, result.treeTermination());
        }
        try {
            protocolLines.finish();
            diagnosticLines.finish();
        }
        catch (RuntimeException protocolFailure) {
            failure = protocolFailure;
        }
        String message = failure == null ? "Codex App Server exited with code " + result.exitCode() : "Codex App Server process failed: " + failureMessage(failure);
        if (result != null && result.treeTermination() == ProcessExit.TreeTermination.UNVERIFIABLE) {
            // Root exit does not prove the tool tree stopped; say so rather than reporting an
            // ordinary disconnect a caller could treat as quiescence.
            message += "; its owned process tree could not be verified stopped";
            log.error("Codex App Server process tree is unverifiable after exit");
        }
        disconnect(new AgentUnavailableException(message, failure));
    }

    private void receive(String line)
    {
        CodexProtocol.JsonRpcEnvelope envelope = codec.decode(line, CodexProtocol.JsonRpcEnvelope.class);
        if (envelope.method().isEmpty()) {
            receiveResponse(envelope);
            return;
        }
        if (envelope.id().isPresent()) {
            CodexProtocol.ServerRequest request = codec.decode(line, CodexProtocol.ServerRequest.class);
            if (request instanceof CodexProtocol.UnknownServerRequest) {
                // Identify protocol gaps without logging arguments, user input, or credentials.
                log.warn("Unsupported Codex server request: method={}, threadId={}", redactor.redact(envelope.method().orElseThrow()), redactor.redact(request.threadId()));
            }
            receiveRequest(request);
        }
        else {
            receiveNotification(codec.decode(line, CodexProtocol.Notification.class));
        }
    }

    private void receiveNotification(CodexProtocol.Notification notification)
    {
        // The dispatcher can wait behind turn/start's monitor; this marks provider arrival before that queue.
        if (sendTiming.isDebugEnabled() && notification.threadId().equals(timingThreadId) && meaningfulOutput(notification)) {
            notification.turnId().flatMap(turnId -> firstProviderOutput.receive(turnId, System.currentTimeMillis()))
                    .ifPresent(this::timingFirstOutput);
        }
        dispatcher.dispatch(notification);
    }

    static boolean meaningfulOutput(CodexProtocol.Notification notification)
    {
        return switch (notification) {
            case CodexProtocol.AgentMessageDeltaNotification value -> !value.params().delta().isBlank();
            case CodexProtocol.PlanDeltaNotification value -> !value.params().delta().isBlank();
            case CodexProtocol.CommandOutputNotification value -> !value.params().delta().isBlank();
            case CodexProtocol.ItemStartedNotification value -> meaningfulItem(value.params().item(), true);
            case CodexProtocol.ItemCompletedNotification value -> meaningfulItem(value.params().item(), false);
            case CodexProtocol.PlanUpdatedNotification value -> !value.params().plan().isEmpty();
            case CodexProtocol.AutoApprovalReviewStartedNotification ignored -> true;
            case CodexProtocol.AutoApprovalReviewCompletedNotification ignored -> true;
            default -> false;
        };
    }

    private static boolean meaningfulItem(CodexProtocol.ThreadItem item, boolean started)
    {
        return switch (item) {
            case CodexProtocol.AgentMessageItem value -> !started && !value.text().isBlank();
            case CodexProtocol.PlanTextItem value -> !started && !value.text().isBlank();
            case CodexProtocol.ReasoningItem value -> !started && value.summary().stream().flatMap(Collection::stream).anyMatch(text -> !text.isBlank());
            case CodexProtocol.FileChangeItem value -> !started && value.status() == CodexProtocol.ActivityStatus.COMPLETED && !value.changes().isEmpty();
            case CodexProtocol.UserMessageItem ignored -> false;
            case CodexProtocol.SubAgentActivityItem ignored -> false;
            case CodexProtocol.UnknownThreadItem ignored -> false;
            default -> true;
        };
    }

    /**
     * Returns the Codex home App Server reported at initialize.
     *
     * @return the home directory, or empty when App Server did not report one
     */
    Optional<String> codexHome()
    {
        return Optional.ofNullable(codexHome.get());
    }

    private void receiveResponse(CodexProtocol.JsonRpcEnvelope envelope)
    {
        long id = responseId(envelope.id().orElseThrow(() -> new AgentUnavailableException("Codex response has no id")));
        CompletableFuture<CodexProtocol.JsonRpcEnvelope> response = pending.remove(id);
        if (response == null) {
            return;
        }
        if (envelope.error().isPresent()) {
            if (id == timingTurnRequestId) {
                firstProviderOutput.cancel();
                timing("codex.turn.rejected", id, "-");
            }
            response.completeExceptionally(new RequestRefused("Codex request failed: " + redactor.redact(envelope.error().orElseThrow().message())));
        }
        else {
            if (id == timingTurnRequestId) {
                String providerTurnId = envelope.result().map(result -> result.path("turn").path("id").asText("-")).orElse("-");
                timing("codex.turn.ack", id, providerTurnId);
                firstProviderOutput.accept(providerTurnId).ifPresent(this::timingFirstOutput);
            }
            response.complete(envelope);
        }
    }

    private void timingFirstOutput(TimedProviderOutput output)
    {
        timing("codex.provider.first_output", -1, output.providerTurnId(), output.epochMs());
    }

    record TimedProviderOutput(String providerTurnId, long epochMs) {}

    /** Correlates early reader arrivals only after turn/start identifies the accepted provider turn. */
    static final class FirstOutputTiming
    {
        private final Map<String, Long> early = new HashMap<>();
        private String acceptedTurnId;
        private boolean active;

        synchronized void begin()
        {
            early.clear();
            acceptedTurnId = null;
            active = true;
        }

        synchronized void cancel()
        {
            early.clear();
            active = false;
        }

        synchronized Optional<TimedProviderOutput> accept(String turnId)
        {
            acceptedTurnId = turnId;
            Long epochMs = early.get(turnId);
            early.clear();
            return epochMs == null ? Optional.empty() : receive(turnId, epochMs);
        }

        synchronized Optional<TimedProviderOutput> receive(String turnId, long epochMs)
        {
            if (!active) {
                return Optional.empty();
            }
            if (acceptedTurnId == null) {
                // Only IDs and the first timestamp are retained; unrelated notifications cannot grow this without bound.
                if (early.size() < 16) {
                    early.putIfAbsent(turnId, epochMs);
                }
                return Optional.empty();
            }
            if (!acceptedTurnId.equals(turnId)) {
                return Optional.empty();
            }
            active = false;
            return Optional.of(new TimedProviderOutput(turnId, epochMs));
        }
    }

    private void receiveRequest(CodexProtocol.ServerRequest request)
    {
        dispatcher.dispatch(request, new CodexMessageDispatcher.Replies()
        {
            @Override
            public void respond(Object id, Object result)
            {
                send(new CodexProtocol.JsonRpcResponse(id, result));
            }

            @Override
            public void fail(Object id, Throwable failure)
            {
                sendError(id, failure);
            }
        });
    }

    private void sendError(Object id, Throwable failure)
    {
        send(new CodexProtocol.JsonRpcFailure(id, new CodexProtocol.JsonRpcError(-32603, failureMessage(failure))));
    }

    private void disconnect(AgentUnavailableException failure)
    {
        if (!disconnected.compareAndSet(false, true)) {
            return;
        }
        pending.forEach((id, response) -> response.completeExceptionally(failure));
        pending.clear();
        dispatcher.disconnect(failure);
    }

    /**
     * Whether the process tree is known to have stopped.
     *
     * @return empty until exit or explicit shutdown verification, then true only after verified
     * runtime cleanup
     */
    Optional<Boolean> processTreeVerifiedStopped()
    {
        ProcessExit.TreeTermination termination = treeTermination.get();
        return termination == null ? Optional.empty() : Optional.of(termination == ProcessExit.TreeTermination.VERIFIED_STOPPED);
    }

    /** Closes after the provider has confirmed that every agent and tool in this run finished. */
    void closeAfterWorkFinished()
    {
        synchronized (lifecycleLock) {
            // No request may start new work after the provider completion fact is recorded.
            closed = true;
            workFinished = true;
        }
        close();
    }

    /** Stops this launch, retaining its provider completion fact when cleanup is retried. */
    @Override
    public void close()
    {
        try {
            ManagedProcess current;
            synchronized (lifecycleLock) {
                current = process;
                closed = true;
            }
            if (current != null) {
                try {
                    awaitVerifiedStop(current);
                }
                finally {
                    current.close();
                }
                acknowledgeVerifiedStop(current);
            }
        }
        finally {
            disconnect(new AgentUnavailableException("Codex App Server closed"));
            dispatcher.close();
            deepSeekLoopback.ifPresent(DeepSeekLoopback::close);
        }
    }

    /**
     * Drops the launch receipt once its tree is verified stopped. It has nothing left to prove:
     * after a crash, startup interrupts a still-open turn and releases its claim without it, so
     * keeping it would only replay it at the next restart.
     */
    private void acknowledgeVerifiedStop(ManagedProcess current)
    {
        try {
            supervisor.acknowledge(current.launchId());
        }
        catch (RuntimeException failure) {
            // The stop is still verified; the next startup replays the kept receipt instead.
            log.warn("Unable to acknowledge Codex App Server launch {}", current.launchId(), failure);
        }
    }

    private void awaitVerifiedStop(ManagedProcess current)
    {
        ProcessExit.TreeTermination termination;
        try {
            termination = (workFinished ? current.stopAfterWorkFinished() : current.stopAndVerify())
                    .toCompletableFuture().get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Interrupted while stopping Codex App Server", e);
        }
        catch (ExecutionException e) {
            throw new AgentUnavailableException("Codex App Server process tree could not be stopped", e.getCause());
        }
        catch (TimeoutException e) {
            throw new AgentUnavailableException("Codex App Server did not stop within " + STOP_TIMEOUT.toSeconds() + " seconds, so its process tree is not verified stopped", e);
        }
        treeTermination.set(termination);
        if (termination != ProcessExit.TreeTermination.VERIFIED_STOPPED) {
            throw new AgentUnavailableException("Codex App Server exited but its process tree could not be verified stopped");
        }
    }

    private static <T> T await(CompletableFuture<T> future, Duration timeout, String operation)
    {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Interrupted while waiting for Codex " + operation, e);
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AgentUnavailableException("Codex " + operation + " failed", cause);
        }
        catch (TimeoutException e) {
            throw new AgentUnavailableException("Timed out waiting for Codex " + operation, e);
        }
    }

    private static long responseId(Object id)
    {
        if (id instanceof Number number) {
            return number.longValue();
        }
        throw new AgentUnavailableException("Codex response id is not numeric");
    }

    /**
     * App Server answered the request with an error, so it did not act on it.
     *
     * <p>Distinct from a timeout or a lost connection, after which nothing says whether the request
     * was acted on; a caller that must not repeat an already-applied request needs to tell the two
     * apart.
     */
    static final class RequestRefused
            extends AgentUnavailableException
    {
        RequestRefused(String message)
        {
            super(message);
        }
    }

    /** A request rejected locally before its bytes were offered to the process. */
    static final class RequestNotSent
            extends AgentUnavailableException
    {
        RequestNotSent(String message)
        {
            super(message);
        }

        RequestNotSent(String message, Throwable cause)
        {
            super(message, cause);
        }
    }
}
