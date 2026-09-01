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
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Objects.requireNonNull;

/**
 * Delivers inbound App Server messages to the session that owns their thread.
 *
 * <p>One App Server process serves every Codex task, and its output arrives as one interleaved
 * stream that only a single reader can parse. That reader must therefore never wait for anything,
 * because a reply it waits for can only be produced by itself. This dispatcher exists to make that
 * structural: the reader hands a decoded message over and returns immediately, and every handler
 * runs somewhere else, free to call back into App Server.
 *
 * <p>Messages for one thread stay in arrival order because each thread owns a chain of work.
 * Different threads make progress independently, so a slow handler for one task cannot stall
 * another. A thread whose chain grows past its bound is marked degraded rather than allowed to
 * consume memory without limit.
 */
final class CodexMessageDispatcher
        implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(CodexMessageDispatcher.class);

    /** Bounds how far one thread's handling may fall behind the reader before events are shed. */
    private static final int MAX_PENDING_PER_THREAD = 512;

    private final ConcurrentMap<String, Listener> listeners = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> depths = new ConcurrentHashMap<>();
    private final Set<String> unroutableThreads = ConcurrentHashMap.newKeySet();
    private final Set<String> degradedThreads = ConcurrentHashMap.newKeySet();
    private final ExecutorService handlers =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("bytequay-codex-handler-", 0).factory());

    /** Sends the outcome of one server request back to App Server. */
    interface Replies
    {
        void respond(Object id, Object result);

        void fail(Object id, Throwable failure);
    }

    /** Receives the messages of one registered Codex thread. */
    interface Listener
    {
        void onNotification(CodexProtocol.Notification notification);

        CompletionStage<?> onRequest(CodexProtocol.ServerRequest request);

        void onDisconnect(Throwable failure);
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
        if (listeners.remove(threadId, listener)) {
            // Drop the thread's bookkeeping too, or every task that ever ran would keep an entry.
            chains.remove(threadId);
            depths.remove(threadId);
        }
    }

    /**
     * Hands one notification to its owning session without waiting for the handler.
     *
     * <p>An unregistered thread is not attributed to any session. Guessing an owner would deliver
     * one task's activity to another, so the thread is recorded and reported instead.
     */
    void dispatch(CodexProtocol.Notification notification)
    {
        String threadId = notification.threadId();
        Listener listener = listeners.get(threadId);
        if (listener == null) {
            if (unroutableThreads.add(threadId)) {
                log.warn("Codex notification for unregistered thread {} was not routed", threadId);
            }
            return;
        }
        submit(threadId, () -> listener.onNotification(notification));
    }

    /**
     * Hands one server request to its owning session and answers when the session decides.
     *
     * <p>The handler may take as long as it needs, including asking App Server something first,
     * because it does not run on the reader.
     */
    void dispatch(CodexProtocol.ServerRequest request, Replies replies)
    {
        String threadId = request.threadId();
        Listener listener = listeners.get(threadId);
        if (listener == null) {
            replies.fail(request.id(), new AgentUnavailableException("Codex request has no active thread"));
            return;
        }
        boolean submitted = submit(threadId, () -> {
            CompletionStage<?> result;
            try {
                result = requireNonNull(listener.onRequest(request), "request result is null");
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
            replies.fail(request.id(), new AgentUnavailableException("Codex thread is shedding messages: " + threadId));
        }
    }

    /**
     * Appends work to one thread's chain.
     *
     * @return false when the thread is already too far behind and the message was shed
     */
    private boolean submit(String threadId, Runnable work)
    {
        AtomicInteger depth = depths.computeIfAbsent(threadId, id -> new AtomicInteger());
        if (depth.incrementAndGet() > MAX_PENDING_PER_THREAD) {
            depth.decrementAndGet();
            if (degradedThreads.add(threadId)) {
                log.warn("Codex thread {} fell too far behind; its event stream is degraded", threadId);
            }
            return false;
        }
        chains.compute(threadId, (id, previous) -> {
            CompletableFuture<Void> base = previous == null ? CompletableFuture.completedFuture(null) : previous;
            // handleAsync, not thenRunAsync: one failed handler must not poison the rest of the
            // thread's stream.
            return base.handleAsync((ignored, failure) -> {
                try {
                    work.run();
                }
                catch (RuntimeException handlerFailure) {
                    log.warn("Codex handler for thread {} failed", id, handlerFailure);
                }
                finally {
                    depth.decrementAndGet();
                }
                return null;
            }, handlers);
        });
        return true;
    }

    /**
     * Tells every session that the transport is gone, after the messages that preceded it.
     *
     * <p>The notice is appended to each thread's chain rather than run immediately, so a session
     * never learns it is disconnected before an event that arrived earlier. It bypasses the depth
     * bound because a dropped disconnect would leave a session waiting forever.
     */
    void disconnect(Throwable failure)
    {
        Map<String, Listener> current = Map.copyOf(listeners);
        listeners.clear();
        current.forEach((threadId, listener) -> chains.compute(threadId, (id, previous) -> {
            CompletableFuture<Void> base = previous == null ? CompletableFuture.completedFuture(null) : previous;
            return base.handleAsync((ignored, ignoredFailure) -> {
                listener.onDisconnect(failure);
                return null;
            }, handlers);
        }));
        depths.clear();
    }

    /** Thread references seen on notifications that no registered session owns. */
    Set<String> unroutableThreads()
    {
        return ImmutableSet.copyOf(unroutableThreads);
    }

    /** Threads whose handlers fell so far behind that messages were shed. */
    Set<String> degradedThreads()
    {
        return ImmutableSet.copyOf(degradedThreads);
    }

    @Override
    public void close()
    {
        handlers.shutdown();
    }
}
