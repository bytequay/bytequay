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
 * One recorded user decision — the write-only signal a future "memory"
 * function will learn from. {@code eventType} names the decision
 * ({@code backlog-skip}, {@code plan-approve}, {@code followup-dismiss}, …),
 * {@code sourceId} is the thing decided on (backlog id, plan id, follow-up
 * id), {@code userDecision} is the verb ({@code skipped} / {@code started} /
 * {@code approved}), and {@code contextSnapshotJson} is a small JSON blob of
 * what was on screen at the time.
 */
public record DistillationSignal(
        String id,
        String eventType,
        String sourceId,
        String userDecision,
        String reason,
        String contextSnapshotJson,
        String threadId,
        String workspaceId,
        Instant createdAt)
{
}
