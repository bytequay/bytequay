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
package com.bytequay.app.domain;

/**
 * Execution state of a {@link Task} (the unit of work that owns a
 * branch + worktree + PR). Distinct from {@link ThreadStatus} in
 * intent: the conversation may stay live while individual tasks roll
 * through pending → running → completed; tasks that park at
 * {@code AWAITING} are the "AI finished, waiting for the human to
 * decide" gate described in the workspace/thread/task design doc.
 *
 * <p>Values mirror {@link ThreadStatus} for now because the existing
 * status machinery maps cleanly onto a task; the two enums diverge
 * once the thread-level lifecycle (active/idle/archived) lands.
 */
public enum TaskStatus
{
    /** Created but no agent turn has started yet. */
    PENDING,

    /** Agent process is alive and producing events. */
    RUNNING,

    /** Paused waiting for the human — permission gate, AWAITING_REVIEW
     *  diff, or a question. */
    AWAITING,

    /** Agent is idle (no live process) but the task hasn't terminated. */
    IDLE,

    /** Task finished cleanly. */
    COMPLETED,

    /** Task failed (budget, timeout, exception, killed, crashed). */
    ERRORED,
}
