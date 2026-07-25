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
 * Lifecycle state of a single {@link Stage} instance: strictly
 * {@link #OPEN} ⇄ {@link #CLOSED}. Whether an operation is executing is
 * a runtime fact (the stage's turns), not a stage state.
 */
public enum StageState
{
    /** Live — the stage owns the task's current chapter of work. */
    OPEN,

    /** Terminal until reopened; {@code openedAt} and {@code closedAt}
     *  both set. */
    CLOSED
}
