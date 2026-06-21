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
 * The event kinds recorded in {@code task_stage_event} for measurement and
 * audit. Only {@link #OPENED} and {@link #CLOSED} are written today; the
 * rest are declared so the loop, notify, and operation write sites that
 * land later have their vocabulary ready.
 */
public enum StageEventType
{
    OPENED,
    CLOSED,
    PAUSED,
    RESUMED,
    LOOP_ITERATION_STARTED,
    NOTIFY_FIRED,
    NOTIFY_SKIPPED,
    OPERATION_STARTED,
    OPERATION_COMPLETED,
    OPERATION_FAILED,
    BUDGET_EXHAUSTED,
    BUDGET_EXHAUSTED_DECISION
}
