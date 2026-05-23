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

import java.time.Instant;

/**
 * Per-thread scope overrides — caps, permission guardrails, and
 * prompt addenda that layer on top of the workspace and global
 * defaults. Each field is nullable: {@code null} means "inherit",
 * a value means "this thread sets it explicitly". A thread with no
 * row is the zero-config default and inherits everything.
 *
 * <p>Effective config is resolved at agent-spawn time as
 * {@code merge(global, workspace, thread, task)} — task scope is
 * future work; for now the merge is global → workspace → thread.
 *
 * @param maxRunningTasks per-thread concurrency cap (number of
 *                        simultaneously-RUNNING Tasks). The scheduler
 *                        queues over-cap work via fair-share lanes.
 * @param softCostUsdMilli warn-and-continue cost threshold; the UI
 *                         surfaces a banner once crossed.
 * @param hardCostUsdMilli pause-and-ask ceiling; the scheduler
 *                        refuses to start new work past it without
 *                        an explicit OK.
 * @param promptAddendum free-form text the spawner appends to the
 *                       workspace memory on every turn. Guidance only.
 */
public record ThreadSettings(
        String threadId,
        Integer maxRunningTasks,
        Integer softCostUsdMilli,
        Integer hardCostUsdMilli,
        String promptAddendum,
        Instant updatedAt)
{
}
