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
package com.bytequay.agent.core;

import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentSessionRef;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.RunningTurn;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One prompt in a disposable provider session: scratch resources, a session, one turn, and a close that must show the
 * provider stopped. When it cannot, the answer is refused and the scratch kept, because the provider may still write there.
 */
public final class OneShotSession
{
    private static final System.Logger log = System.getLogger(OneShotSession.class.getName());

    private OneShotSession() {}

    /** The turn's result, and the conversation a later prompt can resume. */
    public record Answer(AgentTurnResult result, AgentSessionRef reference)
    {
        public Answer
        {
            requireNonNull(result, "result is null");
            requireNonNull(reference, "reference is null");
        }
    }

    /**
     * @param context the session's context, given the scratch's ID and resources
     * @param started receives the running turn, so another thread can cancel it
     * @throws AgentUnavailableException when the turn cannot run, outlives its runtime, or its provider cannot be shown stopped
     */
    public static Answer run(AgentResourceService resources, AgentDriver driver, BiFunction<TaskId, TaskResources, AgentSessionContext> context,
            String prompt, Duration maximumRuntime, Consumer<RunningTurn> started)
    {
        checkArgument(!maximumRuntime.isZero() && !maximumRuntime.isNegative(), "One-shot sessions require a positive maximum runtime");
        TaskId scratchId = TaskId.random();
        boolean processMayBeRunning = false;
        AgentSession session = null;
        try {
            AgentSessionContext sessionContext = context.apply(scratchId, resources.allocate(scratchId));
            processMayBeRunning = true;
            session = driver.open(sessionContext, event -> {});
            RunningTurn turn = session.start(new AgentTurnCommand(TaskId.random().toString(), prompt, ImmutableList.of(), maximumRuntime));
            started.accept(turn);
            AgentTurnResult result;
            try {
                result = turn.completion().toCompletableFuture().get(maximumRuntime.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException timeout) {
                turn.cancel();
                throw new AgentUnavailableException("The model did not answer in time", timeout);
            }
            AgentSessionRef reference = session.reference();
            AgentSession opened = session;
            session = null;
            try {
                opened.close();
            }
            catch (RuntimeException failure) {
                throw new AgentUnavailableException("The model provider could not be verified stopped", failure);
            }
            processMayBeRunning = false;
            return new Answer(result, reference);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AgentUnavailableException("Interrupted while the model answered", interrupted);
        }
        catch (ExecutionException failure) {
            throw new AgentUnavailableException("The model session failed", failure.getCause());
        }
        finally {
            if (session != null) {
                try {
                    session.close();
                    processMayBeRunning = false;
                }
                catch (RuntimeException failure) {
                    log.log(System.Logger.Level.WARNING, "Could not verify one-shot provider shutdown for " + scratchId, failure);
                }
            }
            if (processMayBeRunning) {
                log.log(System.Logger.Level.WARNING, "Retaining one-shot resources because the provider may still be running for " + scratchId);
            }
            else {
                try {
                    resources.delete(scratchId);
                }
                catch (RuntimeException failure) {
                    log.log(System.Logger.Level.WARNING, "Could not remove one-shot resources for " + scratchId, failure);
                }
            }
        }
    }

    /** An empty worktree inside the scratch, for a session that reads no repository. */
    public static Path scratchWorktree(TaskResources scratch, String name)
    {
        try {
            return Files.createDirectory(scratch.runtimeDirectory().resolve(name));
        }
        catch (IOException failure) {
            throw new AgentUnavailableException("Could not prepare the model session", failure);
        }
    }
}
