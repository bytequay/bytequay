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
package com.bytequay.app.web;

import com.bytequay.app.beans.trace.TaskTraceResponse;
import com.bytequay.app.service.threads.TaskTraceService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;

/**
 * Read-only lifecycle trace for a task. Task ids are globally unique, so
 * this hangs off {@code /api/tasks/{taskId}} rather than under a thread.
 * Backs the task page's flow display; the renderer polls it while the
 * phase is non-terminal.
 */
@RestController
@RequestMapping("/api/tasks/{taskId}")
public class TaskTraceController
{
    private final TaskTraceService traceService;

    public TaskTraceController(TaskTraceService traceService)
    {
        this.traceService = requireNonNull(traceService, "traceService is null");
    }

    @GetMapping("/trace")
    public TaskTraceResponse trace(@PathVariable String taskId)
    {
        return traceService.trace(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task " + taskId));
    }
}
