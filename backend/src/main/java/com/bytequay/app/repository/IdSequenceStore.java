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

/**
 * Allocates the per-workspace-per-day counter embedded in new thread ids.
 * Atomic on a per-(workspace, ymd) basis; concurrent callers serialise
 * through the underlying database transaction.
 *
 * <p>The counter is intentionally per-day-per-workspace so the value
 * stays small (rarely beyond two or three digits) and the resulting
 * thread id is short enough to read at a glance. The day key resets
 * the counter naturally; no truncation logic needed.
 */
public interface IdSequenceStore
{
    /**
     * Hand out the next thread-seq for {@code workspaceId} on date
     * {@code ymd} (YYMMDD, UTC). First call for a (workspace, ymd)
     * pair returns 1; subsequent calls return 2, 3, ... and so on.
     *
     * <p>Restart-safe: the next-value column is durable so a crash
     * between the read and the persisted increment cannot re-issue
     * a value already handed out.
     */
    int nextThreadSeq(String workspaceId, String ymd);
}
