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
package com.bytequay.app.git;

import com.bytequay.core.domain.CommitRefreshStatus;

import java.util.List;

/** Local-first application boundary for commit history. */
public interface CommitHistoryService
{
    /**
     * Reads commits from the managed checkout and joins durable local enrichment.
     * This method never contacts a provider or enqueues refresh work.
     */
    CommitHistorySnapshot listHistory(String workspaceId, CommitHistoryRef ref, int limit);

    /** Validates and queues refresh work on the shared application executor. */
    CommitRefreshStatus requestRefresh(
            String workspaceId,
            List<CommitHistoryRef> refs,
            CommitRefreshCause cause);

    /** Reads durable refresh state without Git or provider access. */
    CommitRefreshStatus getRefreshStatus(String workspaceId);
}
