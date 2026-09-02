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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCodexMessageDispatcher
{
    private static final CodexProtocolCodec CODEC = new CodexProtocolCodec(new ObjectMapper());

    private final CodexMessageDispatcher dispatcher = new CodexMessageDispatcher();

    @AfterEach
    void stopHandlers()
    {
        dispatcher.close();
    }

    @Test
    void deliversMessagesInArrivalOrder()
            throws Exception
    {
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        dispatcher.listen(listener(notification -> {
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
    void neverRunsHandlersOnTheCallingThread()
            throws Exception
    {
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        dispatcher.listen(listener(notification -> {
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
    void keepsDeliveringAfterAHandlerFails()
            throws Exception
    {
        List<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        dispatcher.listen(listener(notification -> {
            String turnId = notification.turnId().orElse("none");
            done.countDown();
            if (turnId.equals("turn-1")) {
                throw new IllegalStateException("handler failed");
            }
            delivered.add(turnId);
        }));

        dispatcher.dispatch(notification("thread-1", "turn-1"));
        dispatcher.dispatch(notification("thread-1", "turn-2"));

        // One failed handler must not stop the messages queued behind it.
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(delivered).containsExactly("turn-2");
    }

    @Test
    void refusesAnApprovalWhenNoSessionIsListening()
    {
        AtomicReference<Throwable> failure = new AtomicReference<>();

        dispatcher.dispatch(approval("thread-1"), replies(failure));

        // An unanswered approval would leave the turn waiting forever.
        assertThat(failure.get()).hasMessageContaining("no active session");
    }

    @Test
    void refusesASecondListenerOnOneConnection()
    {
        dispatcher.listen(listener(notification -> {}));

        // One connection serves one task. A second session here would mean two tasks sharing a
        // process, which is the topology this design removes.
        assertThatThrownBy(() -> dispatcher.listen(listener(notification -> {})))
                .hasMessageContaining("already has a listener");
    }

    @Test
    void reportsDisconnectAfterTheMessagesThatPrecededIt()
            throws Exception
    {
        CountDownLatch disconnected = new CountDownLatch(1);
        AtomicReference<Throwable> reported = new AtomicReference<>();
        List<String> order = new CopyOnWriteArrayList<>();
        dispatcher.listen(new CodexMessageDispatcher.Listener()
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
    void reportsDisconnectEvenWhenHandlingIsDegraded()
            throws Exception
    {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        dispatcher.listen(new CodexMessageDispatcher.Listener()
        {
            @Override
            public void onNotification(CodexProtocol.Notification notification)
            {
                blocked.countDown();
                try {
                    released.await();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public CompletionStage<?> onRequest(CodexProtocol.ServerRequest request)
            {
                return CompletableFuture.completedFuture(new Object());
            }

            @Override
            public void onDisconnect(Throwable failure)
            {
                disconnected.countDown();
            }
        });

        dispatcher.dispatch(notification("thread-1", "turn-0"));
        assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
        for (int index = 0; index < 1_000; index++) {
            dispatcher.dispatch(notification("thread-1", "turn-" + index));
        }
        dispatcher.disconnect(new IllegalStateException("transport gone"));
        released.countDown();

        // A shed storm immediately before a disconnect must not swallow the disconnect, or the
        // turn waits on a failure notice that never arrives.
        assertThat(dispatcher.degraded()).isTrue();
        assertThat(disconnected.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void neverShedsATurnsCompletion()
            throws Exception
    {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        dispatcher.listen(listener(notification -> {
            if (notification instanceof CodexProtocol.TurnCompletedNotification) {
                completed.countDown();
                return;
            }
            if (blocked.getCount() > 0) {
                blocked.countDown();
                try {
                    released.await();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }));

        dispatcher.dispatch(notification("thread-1", "turn-0"));
        assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
        for (int index = 0; index < 1_000; index++) {
            dispatcher.dispatch(notification("thread-1", "turn-" + index));
        }
        dispatcher.dispatch(turnCompleted("thread-1"));
        released.countDown();

        // Streamed text is what floods the backlog and losing a delta only truncates displayed
        // text. Losing the turn's completion would leave the turn waiting forever, so it is queued
        // even when the bound has already been reached.
        assertThat(dispatcher.degraded()).isTrue();
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void shedsMessagesWhenHandlingFallsTooFarBehind()
            throws Exception
    {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        dispatcher.listen(listener(notification -> {
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
        assertThat(dispatcher.degraded()).isTrue();
        released.countDown();
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

    private static CodexProtocol.Notification turnCompleted(String threadId)
    {
        return CODEC.decodeNotification("{\"method\":\"turn/completed\",\"params\":{"
                + "\"threadId\":\"" + threadId + "\",\"turn\":{\"id\":\"turn-1\","
                + "\"status\":\"completed\",\"error\":null,\"durationMs\":1,\"items\":[]}}}");
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
