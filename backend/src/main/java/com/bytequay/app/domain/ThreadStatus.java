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
 * Lifecycle state of a {@link Thread}. Drives the left-rail status
 * sections on the list page and the status pill on every card.
 *
 * <p>Transitions are written by the backend in response to stream
 * events; the UI reads but never sets status directly. See the
 * status state machine in {@code docs/mockups/workspace-thread-task-design.md}.
 */
public enum ThreadStatus
{
    /** Spawn requested but the agent hasn't replied with
     *  {@code session_started} yet. Visible mainly during the brief
     *  window between "+ New thread" click and the first event. */
    PENDING,

    /** Agent is actively executing — a tool call or assistant text
     *  is in flight. */
    RUNNING,

    /** Agent paused for user permission (approve a destructive
     *  command, etc.) or for a reply. The list-page card surfaces
     *  approve/reject affordances; macOS notifies on every entry. */
    AWAITING,

    /** Open session, no recent activity; the user hasn't replied. */
    IDLE,

    /** Agent emitted "I'm done" or the user clicked Stop with
     *  {@code mark as complete}. */
    COMPLETED,

    /** Failed (budget, timeout, exception, killed, crashed). */
    ERRORED,
}
