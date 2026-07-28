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
package com.bytequay.app.developmentflow.task;

/** Durable business state for a V2 Task. Waiting and attention are projections. */
public enum TaskLifecycle
{
    PROVISIONING,
    ACTIVE,
    PAUSING,
    PAUSED,
    RESUMING,
    CANCELING,
    CLEANING,
    CANCELED,
    ARCHIVING,
    ARCHIVED,
    COMPLETED,
    REMOTE_CLOSED;

    boolean allows(TaskLifecycle target)
    {
        return switch (this) {
            case PROVISIONING -> target == ACTIVE;
            case ACTIVE -> target == PAUSING
                    || target == ARCHIVING
                    || target == CANCELING
                    || target == CLEANING;
            case PAUSING -> target == PAUSED;
            case PAUSED -> target == RESUMING
                    || target == CANCELING
                    || target == CLEANING;
            case RESUMING -> target == ACTIVE;
            case CANCELING -> target == CLEANING;
            case CLEANING -> target == CANCELED
                    || target == COMPLETED
                    || target == REMOTE_CLOSED;
            case ARCHIVING -> target == ARCHIVED;
            case ARCHIVED -> target == RESUMING
                    || target == CANCELING
                    || target == CLEANING;
            case CANCELED, COMPLETED, REMOTE_CLOSED -> false;
        };
    }

    public boolean isTerminal()
    {
        return this == CANCELED || this == COMPLETED || this == REMOTE_CLOSED;
    }
}
