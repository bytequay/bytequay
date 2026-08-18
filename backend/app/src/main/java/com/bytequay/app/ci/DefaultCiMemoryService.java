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
package com.bytequay.app.ci;

import com.bytequay.agent.core.TaskId;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Warning;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Service
public class DefaultCiMemoryService
        implements CiMemoryService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultCiMemoryService.class);
    private static final String WARNING_CODE = "CI_MEMORY_UPDATE_FAILED";
    private static final int MAX_WARNING_CHARACTERS = 2 * 1024;

    private final CiAutofixStore store;
    private final CiMemoryFile memoryFile;
    private final Clock clock;

    DefaultCiMemoryService(CiAutofixStore store, CiMemoryFile memoryFile, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.memoryFile = requireNonNull(memoryFile, "memoryFile is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public void update(CiAutofixRunId runId)
    {
        requireNonNull(runId, "runId is null");
        try {
            CiAutofixRun run = store.find(runId)
                    .orElseThrow(() -> new IllegalArgumentException("CI Autofix run not found: " + runId));
            TaskId taskId = run.agentTaskId()
                    .map(TaskId::valueOf)
                    .orElseThrow(() -> new IllegalStateException("CI Autofix run has no agent task"));
            memoryFile.write(taskId, run);
        }
        catch (RuntimeException | IOException failure) {
            warn(runId, failure);
        }
    }

    @Override
    public CiMemoryView read(ReadCiMemoryCommand command)
    {
        requireNonNull(command, "command is null");
        try {
            Optional<CiAutofixRun> run = store.findByAgentTaskId(command.taskId().toString());
            if (run.isEmpty() || !run.orElseThrow().agentTaskId().orElse("").equals(command.taskId().toString())) {
                return CiMemoryFile.unavailable();
            }
            return memoryFile.read(command.taskId(), command.query());
        }
        catch (RuntimeException | IOException failure) {
            log.warn("Cannot read CI memory for task {}: {}", command.taskId(), boundedMessage(failure));
            return CiMemoryFile.unavailable();
        }
    }

    private void warn(CiAutofixRunId runId, Exception failure)
    {
        String prefix = "CI memory could not be refreshed: ";
        String message = prefix + boundedMessage(failure);
        if (message.length() > MAX_WARNING_CHARACTERS) {
            message = message.substring(0, MAX_WARNING_CHARACTERS);
        }
        log.warn("{} for run {}", message, runId);
        try {
            Optional<CiAutofixRun> current = store.find(runId);
            if (current.isPresent() && current.orElseThrow().status() != CiAutofixRun.Status.FINISHED) {
                Instant now = Instant.now(clock);
                store.update(current.orElseThrow().addWarning(new Warning(WARNING_CODE, message, now), now));
            }
        }
        catch (RuntimeException warningFailure) {
            log.warn("Cannot record CI memory warning for run {}: {}", runId, boundedMessage(warningFailure));
        }
    }

    private static String boundedMessage(Throwable failure)
    {
        String message = Optional.ofNullable(failure.getMessage()).filter(value -> !value.isBlank())
                .orElse(failure.getClass().getSimpleName());
        return message.length() <= MAX_WARNING_CHARACTERS
                ? message
                : message.substring(0, MAX_WARNING_CHARACTERS);
    }
}
