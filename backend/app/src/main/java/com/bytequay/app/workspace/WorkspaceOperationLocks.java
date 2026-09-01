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
package com.bytequay.app.workspace;

import com.google.common.util.concurrent.Striped;

import java.util.concurrent.locks.Lock;

import static java.util.Objects.requireNonNull;

/** Serializes short filesystem ownership changes for one managed workspace. */
final class WorkspaceOperationLocks
{
    private static final Striped<Lock> LOCKS = Striped.lock(64);

    private WorkspaceOperationLocks() {}

    static Lock get(String workspaceId)
    {
        return LOCKS.get(requireNonNull(workspaceId, "workspaceId is null"));
    }
}
