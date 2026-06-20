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
 * Lifecycle state of a single {@link Stage} instance. A stage opens
 * {@link #OPEN}, flips to {@link #ACTIVE} while an operation executes,
 * may be {@link #PAUSED} by the user, and ends {@link #CLOSED} with both
 * {@code openedAt} and {@code closedAt} set.
 */
public enum StageState
{
    /** Active or polling, but no operation currently executing. */
    OPEN,

    /** An operation is currently executing in this stage. */
    ACTIVE,

    /** User paused; holds resources but won't progress. */
    PAUSED,

    /** Terminal; {@code openedAt} and {@code closedAt} both set. */
    CLOSED
}
