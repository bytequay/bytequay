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
package com.bytequay.app.repository;

import com.bytequay.app.domain.TaskTurnEvent;

import java.util.List;

/**
 * Persistence boundary for scheduler event rows.
 */
public interface TaskTurnEventStore
{
    /** Append one immutable scheduler event. */
    void appendEvent(TaskTurnEvent event);

    /** Scheduler events for one task, newest-first by creation time. */
    List<TaskTurnEvent> listEventsByTaskId(String taskId, int limit);
}
