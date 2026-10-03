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
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.AgentSessionContext;
import com.bytequay.agent.spi.AgentTurnCommand;
import com.bytequay.agent.spi.AgentTurnResult;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static java.util.Objects.requireNonNull;

/** Runs one tool-free model turn in disposable scratch, with no task row or Git worktree, and returns its final answer. */
@Component
public class TextOnlyHelper
{
    private static final Logger log = LoggerFactory.getLogger(TextOnlyHelper.class);
    private static final Duration MAXIMUM_RUNTIME = Duration.ofSeconds(45);

    private final AgentDriverRegistry drivers;
    private final AgentResourceService resources;

    public TextOnlyHelper(AgentDriverRegistry drivers, AgentResourceService resources)
    {
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.resources = requireNonNull(resources, "resources is null");
    }

    /**
     * Returns the model's final answer to {@code prompt}.
     *
     * @param failure what the user is told when the model cannot answer, such as "The selected model could not write the comment"
     */
    public String answer(AgentModelSetting model, String prompt, String managedInstructions, String failure)
    {
        TaskId taskId = TaskId.random();
        boolean processMayBeRunning = false;
        AgentSession session = null;
        try {
            TaskResources taskResources = resources.allocate(taskId);
            Path worktree = Files.createDirectory(taskResources.runtimeDirectory().resolve("text-only-worktree"));
            AgentDriver driver = drivers.require(new AgentLaneId(model.configId()));
            AgentSessionContext context = new AgentSessionContext(
                    taskId.toString(),
                    worktree,
                    taskResources.runtimeDirectory(),
                    taskResources.providerDirectory(),
                    AgentPermissionPolicy.AUTO,
                    Optional.empty(),
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                    Optional.of(model.model()),
                    model.effort(),
                    SessionAccess.TEXT_ONLY,
                    Optional.empty(),
                    managedInstructions);
            processMayBeRunning = true;
            session = driver.open(context, event -> {});
            AgentTurnResult result = session.start(new AgentTurnCommand(
                            TaskId.random().toString(),
                            prompt,
                            ImmutableList.of(),
                            MAXIMUM_RUNTIME))
                    .completion()
                    .toCompletableFuture()
                    .join();
            if (!closeSession(session, taskId)) {
                session = null;
                throw new AgentUnavailableException("The model provider could not be verified stopped.");
            }
            session = null;
            processMayBeRunning = false;
            if (result.state() != AgentTurnResult.State.COMPLETED) {
                throw new AgentUnavailableException(failure + ": " + result.terminalReason().orElse(result.state().name().toLowerCase()));
            }
            return result.finalResponse().orElse("");
        }
        catch (IOException e) {
            throw new AgentUnavailableException("Could not prepare the model session.", e);
        }
        catch (CompletionException e) {
            throw new AgentUnavailableException(failure + ".", e.getCause());
        }
        finally {
            if (session != null && closeSession(session, taskId)) {
                processMayBeRunning = false;
            }
            if (processMayBeRunning) {
                log.warn("Retaining text-only helper resources because the provider may still be running for {}", taskId);
            }
            else {
                resources.delete(taskId);
            }
        }
    }

    private static boolean closeSession(AgentSession session, TaskId taskId)
    {
        try {
            session.close();
            return true;
        }
        catch (RuntimeException failure) {
            log.warn("Could not verify text-only helper shutdown for {}", taskId, failure);
            return false;
        }
    }
}
