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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

/**
 * Hands inbound App Server messages to the session that owns this connection.
 *
 * <p>The reader is the only thread that can parse a reply, so a handler that waits for one while
 * running on the reader waits for itself. This exists to make that impossible: the reader hands a
 * decoded message over and returns immediately, and the handler runs elsewhere, free to call back
 * into App Server.
 *
 * <p>One task owns one App Server process, so a connection carries exactly one listener and nothing
 * has to be routed. Handlers run on a single consumer, which is what keeps them in arrival order.
 * Streamed text is shed once the backlog passes its bound, because that is what a slow handler
 * falls behind on; everything else is always queued, because dropping a turn's completion would
 * cost more than the memory it saves.
 */
final class CodexMessageDispatcher
        implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(CodexMessageDispatcher.class);

    /** Bounds how far handling may fall behind the reader before messages are shed. */
    private static final int MAX_PENDING = 512;

    private final ExecutorService handlers = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("bytequay-codex-handler-", 0).factory());
    private final AtomicReference<Listener> listener = new AtomicReference<>();
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean degraded;

    /** Sends the outcome of one server request back to App Server. */
    interface Replies
    {
        void respond(Object id, Object result);

        void fail(Object id, Throwable failure);
    }

    /** Receives the inbound messages of one Codex connection. */
    interface Listener
    {
        void onNotification(CodexProtocol.Notification notification);

        CompletionStage<?> onRequest(CodexProtocol.ServerRequest request);

        void onDisconnect(Throwable failure);
    }

    /**
     * Attaches the session that will receive this connection's messages.
     *
     * <p>Called before the first thread is started, so no message can arrive with nowhere to go.
     *
     * @param target the session
     */
    void listen(Listener target)
    {
        requireNonNull(target, "target is null");
        if (!listener.compareAndSet(null, target)) {
            throw new AgentUnavailableException("Codex connection already has a listener");
        }
    }

    /**
     * Hands one notification to the listener without waiting for the handler.
     *
     * <p>Only streamed text is shed under backlog pressure. A turn emits thousands of deltas and a
     * handful of lifecycle messages, so the deltas are both the cause of a backlog and the only
     * thing safe to drop: losing one truncates displayed text, while losing a {@code turn/completed}
     * would leave the turn waiting forever.
     */
    void dispatch(CodexProtocol.Notification notification)
    {
        Listener target = listener.get();
        if (target == null) {
            // The model catalog connection starts no thread and attaches no session.
            return;
        }
        Runnable work = () -> target.onNotification(notification);
        if (streamedText(notification)) {
            submit(work);
            return;
        }
        execute(work);
    }

    private static boolean streamedText(CodexProtocol.Notification notification)
    {
        return notification instanceof CodexProtocol.AgentMessageDeltaNotification
                || notification instanceof CodexProtocol.CommandOutputNotification;
    }

    /**
     * Hands one server request to the listener and answers when it decides.
     *
     * <p>The handler may take as long as it needs, including asking App Server something first,
     * because it does not run on the reader.
     */
    void dispatch(CodexProtocol.ServerRequest request, Replies replies)
    {
        Listener target = listener.get();
        if (target == null) {
            replies.fail(request.id(), new AgentUnavailableException("Codex request has no active session"));
            return;
        }
        boolean submitted = submit(() -> {
            CompletionStage<?> result;
            try {
                result = requireNonNull(target.onRequest(request), "request result is null");
            }
            catch (RuntimeException failure) {
                replies.fail(request.id(), failure);
                return;
            }
            result.whenComplete((value, failure) -> {
                if (failure == null) {
                    replies.respond(request.id(), requireNonNull(value, "request response is null"));
                }
                else {
                    replies.fail(request.id(), failure);
                }
            });
        });
        if (!submitted) {
            // A shed approval must be refused rather than left unanswered, or the turn would wait
            // on a decision that is never coming.
            replies.fail(request.id(), new AgentUnavailableException("Codex message handling is degraded"));
        }
    }

    /**
     * Tells the session that the transport is gone, after the messages that preceded it.
     *
     * <p>The notice is appended rather than run immediately, so a session never learns it is
     * disconnected before an event that arrived earlier. It bypasses the backlog bound because a
     * dropped disconnect would leave a turn waiting forever.
     */
    void disconnect(Throwable failure)
    {
        Listener target = listener.getAndSet(null);
        if (target == null) {
            return;
        }
        execute(() -> target.onDisconnect(failure));
    }

    /** Whether the backlog bound was reached and messages were dropped. */
    boolean degraded()
    {
        return degraded;
    }

    /**
     * Appends work to the consumer.
     *
     * @return false when handling is already too far behind and the message was shed
     */
    private boolean submit(Runnable work)
    {
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet();
            if (!degraded) {
                degraded = true;
                log.warn("Codex message handling fell too far behind; the event stream is degraded");
            }
            return false;
        }
        if (!execute(() -> {
            try {
                work.run();
            }
            finally {
                pending.decrementAndGet();
            }
        })) {
            // The consumer is shut down, so the runnable that would have released the slot will
            // never run.
            pending.decrementAndGet();
            return false;
        }
        return true;
    }

    private boolean execute(Runnable work)
    {
        try {
            handlers.execute(() -> {
                try {
                    work.run();
                }
                catch (RuntimeException failure) {
                    // One failed handler must not stop the messages behind it.
                    log.warn("Codex message handler failed", failure);
                }
            });
            return true;
        }
        catch (RejectedExecutionException closed) {
            return false;
        }
    }

    @Override
    public void close()
    {
        handlers.shutdown();
    }
}
