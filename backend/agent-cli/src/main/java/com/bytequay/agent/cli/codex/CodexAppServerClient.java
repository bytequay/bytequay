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

import com.bytequay.agent.spi.AgentUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

import static java.util.Objects.requireNonNull;

final class CodexAppServerClient
        implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(CodexAppServerClient.class);
    private static final Duration START_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_PROTOCOL_LINE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIAGNOSTIC_LINE_BYTES = 16 * 1024;

    private final String executable;
    private final CodexProtocolCodec codec;
    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final ConcurrentMap<Long, CompletableFuture<CodexProtocol.JsonRpcEnvelope>> pending =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Listener> listeners = new ConcurrentHashMap<>();

    private volatile Process process;
    private volatile BufferedWriter input;
    private volatile boolean closed;

    CodexAppServerClient(String executable, ObjectMapper objectMapper)
    {
        this.executable = requireNonNull(executable, "executable is null");
        this.codec = new CodexProtocolCodec(objectMapper);
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
            if (process != null && process.isAlive()) {
                return;
            }
            if (closed) {
                throw new AgentUnavailableException("Codex App Server is closed");
            }
            if (process != null) {
                throw new AgentUnavailableException("Codex App Server disconnected");
            }
            try {
                process = new ProcessBuilder(executable, "app-server", "--listen", "stdio://")
                        .redirectErrorStream(false)
                        .start();
                input = new BufferedWriter(new OutputStreamWriter(
                        process.getOutputStream(),
                        StandardCharsets.UTF_8));
                Thread.ofVirtual().name("bytequay-codex-stdout").start(this::readOutput);
                Thread.ofVirtual().name("bytequay-codex-stderr").start(this::readDiagnostics);
            }
            catch (IOException e) {
                throw new AgentUnavailableException("Unable to start Codex App Server", e);
            }

            CodexProtocol.InitializeParams initialize = new CodexProtocol.InitializeParams(
                    new CodexProtocol.ClientInfo("bytequay", "ByteQuay", "0.4.0"),
                    ImmutableMap.of());
            await(sendRequest("initialize", initialize), START_TIMEOUT, "initialize");
            sendNotification("initialized", new CodexProtocol.EmptyParams());
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
            BufferedWriter writer = input;
            if (writer == null) {
                throw new AgentUnavailableException("Codex App Server input is unavailable");
            }
            try {
                writer.write(codec.encode(message));
                writer.newLine();
                writer.flush();
            }
            catch (IOException e) {
                disconnect(new AgentUnavailableException("Unable to write to Codex App Server", e));
                throw new AgentUnavailableException("Unable to write to Codex App Server", e);
            }
        }
    }

    private void readOutput()
    {
        Process current = process;
        try (InputStream output = current.getInputStream()) {
            String line;
            while ((line = readLine(output, MAX_PROTOCOL_LINE_BYTES, true)) != null) {
                receive(line);
            }
            disconnect(new AgentUnavailableException("Codex App Server closed its output"));
        }
        catch (IOException | RuntimeException failure) {
            disconnect(new AgentUnavailableException("Codex App Server protocol failed", failure));
        }
    }

    private void readDiagnostics()
    {
        Process current = process;
        try (InputStream diagnostics = current.getErrorStream()) {
            String line;
            while ((line = readLine(diagnostics, MAX_DIAGNOSTIC_LINE_BYTES, false)) != null) {
                log.warn("Codex App Server: {}", line);
            }
        }
        catch (IOException e) {
            if (!closed) {
                log.warn("Unable to read Codex App Server diagnostics", e);
            }
        }
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

    private void receiveNotification(CodexProtocol.Notification notification)
    {
        Listener listener = listeners.get(notification.threadId());
        if (listener != null) {
            listener.onNotification(notification);
        }
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
        Process current;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            current = process;
        }
        if (current == null) {
            return;
        }
        current.descendants().forEach(ProcessHandle::destroy);
        current.destroy();
        try {
            if (!current.waitFor(3, TimeUnit.SECONDS)) {
                current.descendants().forEach(ProcessHandle::destroyForcibly);
                current.destroyForcibly();
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.descendants().forEach(ProcessHandle::destroyForcibly);
            current.destroyForcibly();
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

    private static String readLine(InputStream input, int maximumBytes, boolean rejectOverflow)
            throws IOException
    {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(4096, maximumBytes));
        boolean overflow = false;
        int next;
        while ((next = input.read()) != -1) {
            if (next == '\n') {
                break;
            }
            if (next != '\r') {
                if (buffer.size() < maximumBytes) {
                    buffer.write(next);
                }
                else {
                    overflow = true;
                }
            }
        }
        if (next == -1 && buffer.size() == 0 && !overflow) {
            return null;
        }
        if (overflow && rejectOverflow) {
            throw new IOException("Codex protocol line exceeds " + maximumBytes + " bytes");
        }
        String line = buffer.toString(StandardCharsets.UTF_8);
        return overflow ? line + " [truncated]" : line;
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
