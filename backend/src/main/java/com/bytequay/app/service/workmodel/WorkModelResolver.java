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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.WorkModel;

/**
 * Resolves the effective {@link WorkModel} for a thread, task, or stage.
 *
 * <p>Two axes resolve separately:
 *
 * <ul>
 *   <li><b>Engine</b> (CLI agent / API provider + model + account) — copied
 *       onto a new trunk for all four audiences at creation. Explicit
 *       create-dialog choices override the workspace's effective picks;
 *       untouched roles copy them. Legacy sparse trunks still fall through
 *       to the workspace settings ({@code plan} / {@code dev} /
 *       {@code review} / {@code ci-fix}), workspace default, workspace-scope
 *       override, then curated global default. Tasks and stages never choose
 *       an engine, so later workspace changes cannot switch a trunk's new
 *       sessions mid-flight. Task-brain child threads copy the parent
 *       trunk's frozen plan engine when they are created.</li>
 *   <li><b>Reasoning effort</b> — owned by the <em>session</em>. The
 *       nearest scope wins: stage → task → thread → the workspace pick's
 *       own effort. This is the only part of the axis the trunk / task /
 *       stage pickers still write.</li>
 * </ul>
 *
 * <p>The {@link Provenance} returned alongside the resolved choice names
 * where the <em>engine</em> came from, so the work-model pill can render
 * "Inherited from workspace ByteQuay · dev" without a follow-up
 * round-trip.
 */
public interface WorkModelResolver
{
    /** The engine a workspace runs {@code audience} sessions on, with no
     *  scope in play yet. Used when creating a thread, before there is a
     *  row to resolve against. */
    Resolved resolveForWorkspace(String workspaceId, String audience);

    /** Resolve for a trunk-scope turn — the {@code plan} engine row, or
     *  {@code review} on a review-flow thread. Effort cascade: thread →
     *  workspace. */
    Resolved resolveForThread(String threadId);

    /** Resolve for a task-scope turn — the {@code dev} engine row. Effort
     *  cascade: task → thread → workspace. The task must belong to the
     *  named thread; a mismatch is a 404. */
    Resolved resolveForTask(String threadId, String taskId);

    /** Resolve for a stage-scope turn — the engine row matching the
     *  stage's type. Effort cascade: stage → task → thread → workspace.
     *  The stage must belong to the named task; a mismatch is a 404. */
    Resolved resolveForStage(String threadId, String taskId, String stageId);

    /** Resolved cascade outcome: which {@link WorkModel} won and where
     *  its engine came from. */
    record Resolved(WorkModel choice, Provenance provenance) {}

    /** Audit anchor for the resolved engine. {@code scopeId} is the
     *  thread or workspace id, or {@code null} for {@link Source#GLOBAL_DEFAULT}.
     *  {@code scopeLabel} is human-readable and suitable for chips
     *  (e.g. {@code "workspace ByteQuay · dev"}). */
    record Provenance(Source source, String scopeId, String scopeLabel) {}

    /** Tag identifying which scope supplied the engine. */
    enum Source
    {
        /** Retained for wire compatibility; no longer emitted — a stage
         *  can only override reasoning effort, not the engine. */
        STAGE,
        /** Retained for wire compatibility; no longer emitted. */
        TASK,
        /** The trunk froze this engine for the session's audience when it
         *  was created, either from its explicit override or the workspace. */
        THREAD,
        /** The workspace configured the engine — a per-audience row, the
         *  workspace default, or its scope override column. */
        WORKSPACE,
        /** The workspace configured nothing — the catalog's first CLI
         *  agent + its default model is the fallback. */
        GLOBAL_DEFAULT,
    }
}
