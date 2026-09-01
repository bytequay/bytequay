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
import com.bytequay.agent.cli.process.Utf8LineBuffer;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

final class CodexAppServerClient
        implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(CodexAppServerClient.class);
    private static final Duration START_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_PROTOCOL_LINE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIAGNOSTIC_LINE_BYTES = 16 * 1024;

    private final ProcessSupervisor supervisor;
    private final ProcessSpec processSpec;
    private final SecretRedactor redactor;
    private final CodexProtocolCodec codec;
    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final AtomicReference<String> codexHome = new AtomicReference<>();
    private final ConcurrentMap<Long, CompletableFuture<CodexProtocol.JsonRpcEnvelope>> pending =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Listener> listeners = new ConcurrentHashMap<>();
    private final Set<String> unroutableThreads = ConcurrentHashMap.newKeySet();
    private final Utf8LineBuffer protocolLines = new Utf8LineBuffer(MAX_PROTOCOL_LINE_BYTES, true, this::receive);
    private final Utf8LineBuffer diagnosticLines;

    private volatile ManagedProcess process;
    private volatile boolean closed;

    CodexAppServerClient(
            ProcessSupervisor supervisor,
            ProcessSpec processSpec,
            ObjectMapper objectMapper,
            SecretRedactor redactor)
    {
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.processSpec = requireNonNull(processSpec, "processSpec is null");
        this.redactor = requireNonNull(redactor, "redactor is null");
        this.codec = new CodexProtocolCodec(objectMapper);
        this.diagnosticLines = new Utf8LineBuffer(
                MAX_DIAGNOSTIC_LINE_BYTES,
                false,
                line -> log.warn("Codex App Server: {}", this.redactor.redact(line)));
    }

    <T> T request(String method, Object params, Class<T> responseType, Duration timeout)
    {
        requireNonNull(method, "method is null");
        requireNonNull(params, "params is null");
        requireNonNull(responseType, "responseType is null");
        requireNonNull(timeout, "timeout is null");
        start();
        CodexProtocol.JsonRpcEnvelope response = await(sendRequest(method, params), timeout, method);
        return codec.decodeResult(
                response.result().orElseThrow(() -> new AgentUnavailableException(
                        "Codex response has no result: " + method)),
                responseType);
    }

    void register(String threadId, Listener listener)
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(listener, "listener is null");
        if (listeners.putIfAbsent(threadId, listener) != null) {
            throw new AgentUnavailableException("Codex thread is already registered: " + threadId);
        }
    }

    void unregister(String threadId, Listener listener)
    {
        listeners.remove(threadId, listener);
    }

    private void start()
    {
        synchronized (lifecycleLock) {
            if (process != null && !process.exit().toCompletableFuture().isDone()) {
                return;
            }
            if (closed) {
                throw new AgentUnavailableException("Codex App Server is closed");
            }
            if (process != null) {
                throw new AgentUnavailableException("Codex App Server disconnected");
            }
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
            process.exit().whenComplete((result, failure) -> processExited(result, failure));

            try {
                CodexProtocol.InitializeParams initialize = new CodexProtocol.InitializeParams(
                        new CodexProtocol.ClientInfo("bytequay", "ByteQuay", "0.4.0"),
                        new CodexProtocol.InitializeCapabilities(true));
                CodexProtocol.JsonRpcEnvelope initialized =
                        await(sendRequest("initialize", initialize), START_TIMEOUT, "initialize");
                codexHome.set(initialized.result()
                        .map(result -> codec.decodeResult(result, CodexProtocol.InitializeResponse.class))
                        .map(CodexProtocol.InitializeResponse::codexHome)
                        .orElse(null));
                sendNotification("initialized", new CodexProtocol.EmptyParams());
            }
            catch (RuntimeException failure) {
                process.cancel();
                disconnect(new AgentUnavailableException("Unable to initialize Codex App Server", failure));
                throw failure;
            }
        }
    }

    private CompletableFuture<CodexProtocol.JsonRpcEnvelope> sendRequest(String method, Object params)
    {
        long id = requestIds.incrementAndGet();
        CompletableFuture<CodexProtocol.JsonRpcEnvelope> response = new CompletableFuture<>();
        pending.put(id, response);
        try {
            send(new CodexProtocol.JsonRpcRequest(id, method, params));
        }
        catch (RuntimeException failure) {
            pending.remove(id, response);
            response.completeExceptionally(failure);
        }
        return response;
    }

    private void sendNotification(String method, Object params)
    {
        send(new CodexProtocol.JsonRpcNotification(method, params));
    }

    private void send(Object message)
    {
        synchronized (writeLock) {
            ManagedProcess current = process;
            if (current == null) {
                throw new AgentUnavailableException("Codex App Server input is unavailable");
            }
            byte[] bytes = (codec.encode(message) + "\n").getBytes(StandardCharsets.UTF_8);
            try {
                await(current.write(ByteBuffer.wrap(bytes)).toCompletableFuture(), WRITE_TIMEOUT, "input write");
            }
            catch (RuntimeException failure) {
                AgentUnavailableException unavailable =
                        new AgentUnavailableException("Unable to write to Codex App Server", failure);
                disconnect(unavailable);
                throw unavailable;
            }
        }
    }

    private void receiveOutput(ProcessStream stream, ByteBuffer bytes)
    {
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
        try {
            protocolLines.finish();
            diagnosticLines.finish();
        }
        catch (RuntimeException protocolFailure) {
            failure = protocolFailure;
        }
        String message = failure == null
                ? "Codex App Server exited with code " + result.exitCode()
                : "Codex App Server process failed: " + failureMessage(failure);
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
        CodexProtocol.JsonRpcEnvelope envelope = codec.decodeEnvelope(line);
        if (envelope.method().isEmpty()) {
            receiveResponse(envelope);
            return;
        }
        if (envelope.id().isPresent()) {
            receiveRequest(codec.decodeServerRequest(line));
        }
        else {
            receiveNotification(codec.decodeNotification(line));
        }
    }

    /**
     * Routes a notification to the session that owns its thread, and never guesses.
     *
     * <p>An unregistered thread is normally a child thread created by a subagent. Its parent is
     * only discoverable through {@code thread/read}, which cannot be called from here: this runs on
     * the single output-dispatch thread, so a blocking request would wait for a response that only
     * this thread can read. Until child resolution is done off this thread, such a notification is
     * recorded rather than dropped, so an unrouted child is visible instead of silent.
     */
    private void receiveNotification(CodexProtocol.Notification notification)
    {
        Listener listener = listeners.get(notification.threadId());
        if (listener == null) {
            if (unroutableThreads.add(notification.threadId())) {
                log.warn("Codex notification for unregistered thread {} was not routed", notification.threadId());
            }
            return;
        }
        listener.onNotification(notification);
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

    /** Thread references seen on notifications that no registered session owns. */
    Set<String> unroutableThreads()
    {
        return ImmutableSet.copyOf(unroutableThreads);
    }

    private void receiveResponse(CodexProtocol.JsonRpcEnvelope envelope)
    {
        long id = responseId(envelope.id().orElseThrow(() ->
                new AgentUnavailableException("Codex response has no id")));
        CompletableFuture<CodexProtocol.JsonRpcEnvelope> response = pending.remove(id);
        if (response == null) {
            return;
        }
        if (envelope.error().isPresent()) {
            response.completeExceptionally(new AgentUnavailableException(
                    "Codex request failed: " + envelope.error().orElseThrow().message()));
        }
        else {
            response.complete(envelope);
        }
    }

    private void receiveRequest(CodexProtocol.ServerRequest request)
    {
        Listener listener = listeners.get(request.threadId());
        if (listener == null) {
            sendError(request.id(), new AgentUnavailableException("Codex request has no active thread"));
            return;
        }
        CompletionStage<?> result;
        try {
            result = requireNonNull(listener.onRequest(request), "request result is null");
        }
        catch (RuntimeException failure) {
            sendError(request.id(), failure);
            return;
        }
        result.whenComplete((value, failure) -> {
            if (failure == null) {
                send(new CodexProtocol.JsonRpcResponse(
                        request.id(),
                        requireNonNull(value, "request response is null")));
            }
            else {
                sendError(request.id(), failure);
            }
        });
    }

    private void sendError(Object id, Throwable failure)
    {
        send(new CodexProtocol.JsonRpcFailure(
                id,
                new CodexProtocol.JsonRpcError(-32603, failureMessage(failure))));
    }

    private void disconnect(AgentUnavailableException failure)
    {
        if (!disconnected.compareAndSet(false, true)) {
            return;
        }
        pending.forEach((id, response) -> response.completeExceptionally(failure));
        pending.clear();
        listeners.values().forEach(listener -> listener.onDisconnect(failure));
        listeners.clear();
    }

    @Override
    public void close()
    {
        ManagedProcess current;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            current = process;
        }
        if (current != null) {
            current.close();
        }
        disconnect(new AgentUnavailableException("Codex App Server closed"));
    }

    private static <T> T await(
            CompletableFuture<T> future,
            Duration timeout,
            String operation)
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

    private static String failureMessage(Throwable failure)
    {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static long responseId(Object id)
    {
        if (id instanceof Number number) {
            return number.longValue();
        }
        throw new AgentUnavailableException("Codex response id is not numeric");
    }

    interface Listener
    {
        void onNotification(CodexProtocol.Notification notification);

        CompletionStage<?> onRequest(CodexProtocol.ServerRequest request);

        void onDisconnect(Throwable failure);
    }
}
