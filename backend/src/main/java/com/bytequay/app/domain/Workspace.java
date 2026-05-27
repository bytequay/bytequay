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
 * The persistent project brain. A workspace holds the durable memory
 * (a small markdown blob loaded into every thread's context), spans
 * one or more repos, and owns all the threads that work on them. New
 * installs come with a single ambient workspace named "ByteQuay";
 * multi-workspace creation lands later.
 *
 * <p>{@code memoryMd} is kept intentionally small — target ~2k tokens,
 * hard cap ~4k — because it is loaded into every thread. The
 * distillation pass keeps it that way by promoting durable decisions
 * upward while discarding noise.
 *
 * @param isScratch scratch workspaces never accrue durable memory
 *                  (one-off exploration that shouldn't pollute the
 *                  shared brain). The default workspace is not scratch.
 */
public record Workspace(
        String id,
        String name,
        String memoryMd,
        boolean isScratch,
        /** The workspace's default pick on the work-model cascade. A
         *  null here means no override is set and the resolver falls
         *  back to the global default. Threads / tasks / review seats
         *  inherit this unless they declare their own (Phase 2). */
        WorkModel workModel,
        Instant createdAt,
        Instant updatedAt)
{
}
