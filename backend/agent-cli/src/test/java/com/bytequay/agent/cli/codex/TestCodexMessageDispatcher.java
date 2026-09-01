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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodexMessageDispatcher
{
    private static final CodexProtocolCodec CODEC = new CodexProtocolCodec(new ObjectMapper());

    private final Map<String, String> parents = new ConcurrentHashMap<>();
    private final CodexMessageDispatcher dispatcher =
            new CodexMessageDispatcher(threadId -> Optional.ofNullable(parents.get(threadId)));

    @AfterEach
    void stopHandlers()
    {
        dispatcher.close();
    }

    @Test
    void deliversOneThreadsMessagesInArrivalOrder()
            throws Exception
    {
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        dispatcher.register("thread-1", listener(notification -> {
            delivered.add(notification.turnId().orElse("none"));
            done.countDown();
        }));

        dispatcher.dispatch(notification("thread-1", "turn-1"));
        dispatcher.dispatch(notification("thread-1", "turn-2"));
        dispatcher.dispatch(notification("thread-1", "turn-3"));

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(delivered).containsExactly("turn-1", "turn-2", "turn-3");
    }

    @Test
    void keepsOneSlowThreadFromStallingAnother()
            throws Exception
    {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch fastDelivered = new CountDownLatch(1);
        dispatcher.register("slow", listener(notification -> {
            blocked.countDown();
            try {
                released.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        dispatcher.register("fast", listener(notification -> fastDelivered.countDown()));

        dispatcher.dispatch(notification("slow", "turn-1"));
        assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
        dispatcher.dispatch(notification("fast", "turn-1"));

        // A handler that blocks holds up only its own thread's stream.
        assertThat(fastDelivered.await(5, TimeUnit.SECONDS)).isTrue();
        released.countDown();
    }

    @Test
    void neverRunsHandlersOnTheCallingThread()
            throws Exception
    {
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        dispatcher.register("thread-1", listener(notification -> {
            handlerThread.set(Thread.currentThread());
            done.countDown();
        }));

        dispatcher.dispatch(notification("thread-1", "turn-1"));

        // This is the invariant the reader depends on: dispatch returns before the handler runs,
        // so a handler may block or call back into App Server without stalling the reader.
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(handlerThread.get()).isNotSameAs(Thread.currentThread());
    }

    @Test
    void deliversASubagentsMessagesToTheTaskThatOwnsItsAncestor()
            throws Exception
    {
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        dispatcher.register("root", listener(notification -> {
            delivered.add(notification.turnId().orElse("none"));
            done.countDown();
        }));
        parents.put("grandchild", "child");
        parents.put("child", "root");

        dispatcher.dispatch(notification("grandchild", "turn-1"));
        dispatcher.dispatch(notification("grandchild", "turn-2"));

        // A subagent two levels down still belongs to the task that owns its root, and its
        // messages keep their order behind the one resolution.
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(delivered).containsExactly("turn-1", "turn-2");
        assertThat(dispatcher.unroutableThreads()).isEmpty();
    }

    @Test
    void releasesAdoptedChildrenWhenTheirSessionGoesAway()
            throws Exception
    {
        CountDownLatch delivered = new CountDownLatch(1);
        CodexMessageDispatcher.Listener owner = listener(notification -> delivered.countDown());
        dispatcher.register("root", owner);
        parents.put("child", "root");
        dispatcher.dispatch(notification("child", "turn-1"));
        assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();

        dispatcher.unregister("root", owner);

        // A session forgets only its own thread, so an adopted child would otherwise stay
        // registered for the lifetime of the backend.
        parents.clear();
        dispatcher.dispatch(notification("child", "turn-2"));
        awaitCondition(() -> dispatcher.unroutableThreads().contains("child"));
    }

    @Test
    void leavesAThreadUnownedWhenItsAncestryReachesNoSession()
            throws Exception
    {
        dispatcher.register("root", listener(notification -> {}));
        parents.put("stranger", "someone-elses-root");

        dispatcher.dispatch(notification("stranger", "turn-1"));

        // Guessing an owner would hand one task's activity to another.
        awaitCondition(() -> !dispatcher.unroutableThreads().isEmpty());
        assertThat(dispatcher.unroutableThreads()).containsExactly("stranger");
    }

    @Test
    void refusesAnApprovalForAThreadNoSessionOwns()
    {
        AtomicReference<Throwable> failure = new AtomicReference<>();

        dispatcher.dispatch(approval("unknown-thread"), replies(failure));

        // An unanswered approval would leave the turn waiting forever.
        assertThat(failure.get()).hasMessageContaining("no active thread");
    }

    @Test
    void reportsDisconnectAfterTheMessagesThatPrecededIt()
            throws Exception
    {
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<Throwable> reported = new AtomicReference<>();
        List<String> order = new CopyOnWriteArrayList<>();
        dispatcher.register("thread-1", new CodexMessageDispatcher.Listener()
        {
            @Override
            public void onNotification(CodexProtocol.Notification notification)
            {
                order.add("notification");
            }

            @Override
            public CompletionStage<?> onRequest(CodexProtocol.ServerRequest request)
            {
                return CompletableFuture.completedFuture(new Object());
            }

            @Override
            public void onDisconnect(Throwable failure)
            {
                order.add("disconnect");
                reported.set(failure);
                disconnected.countDown();
            }
        });

        dispatcher.dispatch(notification("thread-1", "turn-1"));
        dispatcher.disconnect(new IllegalStateException("transport gone"));

        assertThat(disconnected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(reported.get()).hasMessage("transport gone");
        assertThat(order).containsExactly("notification", "disconnect");
    }

    @Test
    void shedsAndMarksAThreadThatFallsTooFarBehind()
            throws Exception
    {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        dispatcher.register("thread-1", listener(notification -> {
            blocked.countDown();
            try {
                released.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        dispatcher.dispatch(notification("thread-1", "turn-0"));
        assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
        for (int index = 0; index < 1_000; index++) {
            dispatcher.dispatch(notification("thread-1", "turn-" + index));
        }

        // Memory is bounded even when a handler stops making progress, and the loss is reported
        // rather than silent.
        assertThat(dispatcher.degradedThreads()).containsExactly("thread-1");
        released.countDown();
    }

    private static void awaitCondition(BooleanSupplier condition)
            throws InterruptedException
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition was not met before the deadline");
            }
            Thread.sleep(5);
        }
    }

    private static CodexMessageDispatcher.Listener listener(Consumer<CodexProtocol.Notification> onNotification)
    {
        return new CodexMessageDispatcher.Listener()
        {
            @Override
            public void onNotification(CodexProtocol.Notification notification)
            {
                onNotification.accept(notification);
            }

            @Override
            public CompletionStage<?> onRequest(CodexProtocol.ServerRequest request)
            {
                return CompletableFuture.completedFuture(new Object());
            }

            @Override
            public void onDisconnect(Throwable failure) {}
        };
    }

    private static CodexMessageDispatcher.Replies replies(AtomicReference<Throwable> failure)
    {
        return new CodexMessageDispatcher.Replies()
        {
            @Override
            public void respond(Object id, Object result) {}

            @Override
            public void fail(Object id, Throwable thrown)
            {
                failure.set(thrown);
            }
        };
    }

    private static CodexProtocol.Notification notification(String threadId, String turnId)
    {
        return CODEC.decodeNotification("{\"method\":\"item/agentMessage/delta\",\"params\":{"
                + "\"threadId\":\"" + threadId + "\",\"turnId\":\"" + turnId + "\","
                + "\"itemId\":\"message-1\",\"delta\":\"hello\"}}");
    }

    private static CodexProtocol.ServerRequest approval(String threadId)
    {
        return CODEC.decodeServerRequest("{\"id\":1,\"method\":\"item/commandExecution/requestApproval\","
                + "\"params\":{\"threadId\":\"" + threadId + "\",\"turnId\":\"turn-1\","
                + "\"itemId\":\"exec-1\",\"command\":\"echo hi\","
                + "\"availableDecisions\":[\"accept\",\"decline\"],\"reason\":null,"
                + "\"startedAtMs\":1}}");
    }
}
