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
package com.bytequay.agent.core;

public enum CleanupState
{
    RETAINED,
    DELETE_DUE,
    RETRY_PENDING,
    DELETED;

    public boolean canTransitionTo(CleanupState target)
    {
        return switch (this) {
            case RETAINED -> target == DELETE_DUE;
            case DELETE_DUE -> target == RETRY_PENDING || target == DELETED;
            case RETRY_PENDING -> target == RETRY_PENDING || target == DELETED;
            case DELETED -> target == DELETED;
        };
    }
}
