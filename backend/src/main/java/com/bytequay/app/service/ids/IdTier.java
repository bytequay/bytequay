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
package com.bytequay.app.service.ids;

/**
 * The tiers in the human-readable id hierarchy and the marker each
 * one stamps into a generated id. The single source of truth for the
 * {@code "ws-"} / {@code "t"} / {@code ".k"} literals — anyone reading
 * or producing ids should reach for {@link #marker()} rather than
 * inlining the string.
 *
 * <p>Composition rules (see {@link IdGenerator}):
 * <pre>
 *   Workspace   {WORKSPACE.marker()}{slug}
 *                 e.g.  ws-bytequay
 *   Thread      {THREAD.marker()}{ymd}-{seq}-{rand2}
 *                 e.g.  t260603-3-a1
 *   Task        {threadId}{TASK.marker()}{seq}
 *                 e.g.  t260603-3-a1.k2
 * </pre>
 *
 * <p>A thread id carries no workspace slug — a thread already has its
 * own {@code workspace_id} column, so repeating it inside the id would
 * only make the id longer without adding information.
 */
public enum IdTier
{
    /** Prefix for workspace slugs. */
    WORKSPACE("ws-"),

    /** Leading marker on a thread id, before its date + seq + rand. */
    THREAD("t"),

    /** Separator between a thread id and the task's per-thread seq. */
    TASK(".k");

    private final String marker;

    IdTier(String marker)
    {
        this.marker = marker;
    }

    /**
     * The literal that appears in a generated id immediately before the
     * tier's distinguishing payload. Stable across the codebase — any
     * code reading or writing ids should reach for this rather than
     * inlining the {@code "ws-"} / {@code ".t"} / {@code ".k"} string.
     */
    public String marker()
    {
        return marker;
    }
}
