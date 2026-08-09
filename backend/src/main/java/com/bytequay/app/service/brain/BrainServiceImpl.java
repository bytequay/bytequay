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
import com.bytequay.app.developmentflow.task.TaskBrainConversationRuntime;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Routes Task Brain messages to the typed V2 runtime. */
@Service
public class BrainServiceImpl
{
    private final TaskStore tasks;
    private TaskBrainConversationRuntime v2Brain;

    public BrainServiceImpl(TaskStore tasks)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
    }

    public BrainMessageResponse sendMessage(String taskId, String text, List<String> images)
    {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text is required");
        }
        if (v2Brain != null && v2Brain.isV2Task(taskId)) {
            return v2Brain.sendMessage(taskId, text, images);
        }
        Task task = tasks.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no task: " + taskId));
        if (tasks.isV2Task(task.id())) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "V2 Task Brain runtime is unavailable");
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "LEGACY Task Brain turns are read-only; use a typed V2 Task control");
    }

    @Autowired(required = false)
    void setV2Brain(TaskBrainConversationRuntime v2Brain)
    {
        this.v2Brain = requireNonNull(v2Brain, "v2Brain is null");
    }
}
