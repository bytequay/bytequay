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
package com.bytequay.app.service.brain;

import com.bytequay.app.beans.brain.BrainMessageResponse;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.ids.IdGenerator;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

@Service
public class BrainServiceImpl
        implements BrainService
{
    /** Cheap, capable default for a read-only Q&A agent. Configurable per
     *  task later; the API lane resolves this via the thread's work model. */
    private static final String DEFAULT_BRAIN_PROVIDER = "anthropic";
    private static final String DEFAULT_BRAIN_MODEL = "claude-haiku-4-5-20251001";
    private static final String DEFAULT_WORKSPACE_ID = "ws-default";

    private final TaskStore taskStore;
    private final ThreadStore threadStore;
    private final ThreadTurnScheduler scheduler;
    private final IdGenerator idGenerator;

    public BrainServiceImpl(
            TaskStore taskStore,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            IdGenerator idGenerator)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.idGenerator = requireNonNull(idGenerator, "idGenerator is null");
    }

    @Override
    @Transactional
    public BrainMessageResponse sendMessage(String taskId, String text)
    {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "text is required");
        }
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));

        Thread brain = threadStore.findBrainThreadByTask(taskId)
                .orElseGet(() -> createBrainThread(task));

        // The brain agent persists the user message and the assistant reply
        // itself when the turn runs; the reply streams via the thread SSE.
        String turnId = scheduler.enqueueTurn(brain, text.trim(), TurnInitiator.user());
        return new BrainMessageResponse(turnId, brain.id());
    }

    private Thread createBrainThread(Task task)
    {
        String workspaceId = threadStore.findThreadById(task.threadId())
                .map(Thread::workspaceId)
                .filter(w -> w != null && !w.isBlank())
                .orElse(DEFAULT_WORKSPACE_ID);
        Instant now = Instant.now();
        Thread brain = new Thread(
                idGenerator.newThreadId(workspaceId, now),
                ThreadKind.BRAIN_AGENT,
                DEFAULT_BRAIN_PROVIDER,
                /* agentSessionId */ null,
                "Brain · " + task.id(),
                ThreadStatus.IDLE,
                DEFAULT_BRAIN_MODEL,
                /* costUsdMilli */ 0L,
                /* tokensIn */ 0L,
                /* tokensOut */ 0L,
                now,
                now,
                /* endedAt */ null,
                /* errorMessage */ null,
                ThreadFlow.BUILD,
                workspaceId,
                new WorkModel(WorkModelKind.API, DEFAULT_BRAIN_PROVIDER, DEFAULT_BRAIN_MODEL, null),
                /* activeTask */ null,
                /* parentReviewPassId */ null,
                List.of(),
                /* parallelSlots */ 1,
                /* parentTaskId */ task.id());
        threadStore.saveThread(brain);
        return brain;
    }
}
