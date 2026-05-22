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
 * One pending workspace-memory edit proposed by the Haiku distiller.
 * The distiller writes this instead of replacing {@code memory_md}
 * directly, so the user controls when (and whether) the proposed
 * body lands — the Phase 3 acceptance line is "distillation proposes
 * (doesn't silently overwrite)".
 *
 * <p>At most one proposal exists per workspace at any time
 * ({@code workspaceId} is the PK on the table). A subsequent
 * distillation pass upserts in place, so the user only ever sees the
 * freshest proposed body.
 *
 * @param currentMd  The {@code memory_md} value that was live when
 *                   the distiller built this proposal. Apply
 *                   compares it against the workspace's current
 *                   {@code memory_md} so a hand-edit between
 *                   proposal-time and apply-time can't be silently
 *                   clobbered.
 * @param proposedMd Haiku's proposed replacement for the workspace
 *                   memory. Applied wholesale on user confirmation.
 */
public record WorkspaceMemoryProposal(
        String workspaceId,
        String currentMd,
        String proposedMd,
        String summariserModel,
        long promptTokens,
        long completionTokens,
        long costUsdMilli,
        Instant createdAt)
{
}
