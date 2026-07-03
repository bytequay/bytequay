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

import com.bytequay.app.domain.DistillationSignal;

import java.util.List;

/** Persistence boundary for the write-only {@link DistillationSignal} log. */
public interface DistillationSignalStore
{
    /** Append one decision signal. */
    DistillationSignal save(DistillationSignal signal);

    /** Signals of one event type, oldest-first. Exists for the (future)
     *  memory read path + tests; v1 has no production reader. */
    List<DistillationSignal> findByEventType(String eventType);

    /** Delete signals attached to a thread. Returns the count removed. */
    int deleteByThread(String threadId);

    /** Delete signals attached to a workspace. Returns the count removed. */
    int deleteByWorkspace(String workspaceId);
}
