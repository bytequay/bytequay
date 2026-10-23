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
package com.bytequay.app.service.ai;

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.RunningTurn;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTextOnlyHelper
{
    private static final AgentModelSetting MODEL = new AgentModelSetting(AgentModelType.DEVELOPMENT, true, "codex", "Codex", "gpt", "GPT", Optional.empty());

    @TempDir
    private Path temporaryDirectory;

    private final AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
    private final AgentResourceService resources = mock(AgentResourceService.class);
    private final AgentDriver driver = mock(AgentDriver.class);
    private final AgentSession session = mock(AgentSession.class);
    private final RunningTurn turn = mock(RunningTurn.class);
    private final CompletableFuture<AgentTurnResult> completion = new CompletableFuture<>();
    private final TextOnlyHelper helper = new TextOnlyHelper(drivers, resources);

    @BeforeEach
    void setUp()
            throws Exception
    {
        Path runtime = temporaryDirectory.resolve("runtime");
        Files.createDirectories(runtime.resolve("provider"));
        when(resources.allocate(any())).thenReturn(new TaskResources(runtime, runtime.resolve("events.jsonl"), runtime.resolve("blobs"), runtime.resolve("provider")));
        when(drivers.require(new AgentLaneId("codex"))).thenReturn(driver);
        when(driver.open(any(), any())).thenReturn(session);
        when(session.reference()).thenReturn(new AgentSessionRef(new AgentLaneId("codex"), 1, "session"));
        when(session.start(any())).thenReturn(turn);
        when(turn.completion()).thenReturn(completion);
        doAnswer(call -> completion.complete(new AgentTurnResult(AgentTurnResult.State.CANCELLED, Optional.empty(), Optional.of("cancelled"), Duration.ZERO, true)))
                .when(turn).cancel();
    }

    @Test
    void cancellingAnAnswerStopsItsTurn()
            throws Exception
    {
        CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> helper.answer(MODEL, "Answer slowly", "", "No answer", Duration.ofMinutes(10), "exchange-1"));
        verify(session, timeout(5_000)).start(any());
        helper.cancel("someone-else");
        verify(turn, never()).cancel();
        helper.cancel("exchange-1");
        verify(turn).cancel();
        assertThatThrownBy(() -> answer.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(AgentUnavailableException.class).hasMessageContaining("No answer");
    }

    @Test
    void anAnswerCancelledBeforeItsTurnStartsIsCancelledAsItStarts()
            throws Exception
    {
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        when(driver.open(any(), any())).thenAnswer(call -> {
            opening.countDown();
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
            return session;
        });
        CompletableFuture<String> answer = CompletableFuture.supplyAsync(() -> helper.answer(MODEL, "Answer slowly", "", "No answer", Duration.ofMinutes(10), "exchange-1"));
        assertThat(opening.await(5, TimeUnit.SECONDS)).isTrue();
        helper.cancel("exchange-1");
        cancelled.countDown();
        assertThatThrownBy(() -> answer.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(AgentUnavailableException.class);
        verify(turn).cancel();
    }

    @Test
    void aTimedOutAnswerCancelsTheTurnAndReleasesScratch()
    {
        assertThatThrownBy(() -> helper.answer(MODEL, "Answer slowly", "", "No answer", Duration.ofMillis(10), "timeout"))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("No answer")
                .hasMessageContaining("did not answer in time");
        verify(turn).cancel();
        verify(session).close();
        verify(resources).delete(any());
    }
}
