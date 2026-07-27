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
 * Where a turn or message sits in the thread hierarchy, made explicit on
 * the row rather than inferred from {@code task_id IS NULL} or a stage
 * time window:
 *
 * <ul>
 *   <li>{@link #TRUNK} — the thread-level planning plane; no task focused
 *       ({@code task_id} and {@code stage_id} both null).</li>
 *   <li>{@link #TASK} — task-level work with no specific stage in flight
 *       ({@code task_id} set, {@code stage_id} null).</li>
 *   <li>{@link #STAGE} — a specific stage's work ({@code task_id} and
 *       {@code stage_id} both set); the stage's kind is read from
 *       {@code task_stage.type}, not copied here.</li>
 * </ul>
 */
public enum ThreadScope
{
    TRUNK,
    TASK,
    STAGE
}
