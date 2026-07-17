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
package com.bytequay.app.beans.workspace;

import java.util.List;

/** One server-owned projection for the redesigned Trunk activity rail. */
public record TrunkActivityDto(
        String trunkId,
        List<Item> pinned,
        List<Item> timeline,
        int taskCount,
        int pullRequestCount,
        long costUsdMilli,
        long generatedAt)
{
    public record Item(
            String id,
            String kind,
            String title,
            String summary,
            String status,
            String itemPath,
            String taskId,
            String sessionId,
            long occurredAt,
            boolean actionable)
    {
    }
}
