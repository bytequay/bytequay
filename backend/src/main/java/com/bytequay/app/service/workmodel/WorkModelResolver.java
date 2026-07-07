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
 * Resolves the effective {@link WorkModel} for a thread or task by
 * walking the override cascade most-specific-first. The persisted
 * overrides on tasks, threads, and workspaces are nullable; the
 * resolver picks the first non-null one it encounters and falls back
 * to a curated global default when every scope is empty.
 *
 * <p>Cascade order:
 * <ol>
 *   <li>stage — {@code resolveForStage} only</li>
 *   <li>task — {@code resolveForStage} and {@code resolveForTask}</li>
 *   <li>thread</li>
 *   <li>workspace (the thread's owning workspace)</li>
 *   <li>global default (the first CLI agent in {@link WorkModelCatalog}
 *       with its catalog-default model)</li>
 * </ol>
 *
 * <p>The {@link Provenance} returned alongside the resolved choice
 * names the winning scope so the inspector and the work-model pill
 * can render "Inherited from workspace ByteQuay" without a follow-up
 * round-trip.
 */
public interface WorkModelResolver
{
    /** Resolve the effective work model for a trunk-scope turn on the
     *  given thread. Walks thread → workspace → global default; the
     *  task scope is skipped because the trunk runs above any task. */
    Resolved resolveForThread(String threadId);

    /** Resolve the effective work model for a task-scope turn. Walks
     *  task → thread → workspace → global default. The task must
     *  belong to the named thread; a mismatch is a 404. */
    Resolved resolveForTask(String threadId, String taskId);

    /** Resolve the effective work model for a stage-scope turn. Walks
     *  stage → task → thread → workspace → global default. The stage
     *  must belong to the named task; a mismatch is a 404. */
    Resolved resolveForStage(String threadId, String taskId, String stageId);

    /** Resolved cascade outcome: which {@link WorkModel} won and where
     *  it came from. */
    record Resolved(WorkModel choice, Provenance provenance) {}

    /** Audit anchor for the resolved choice. {@code scopeId} is the
     *  task / thread / workspace id, or {@code null} for
     *  {@link Source#GLOBAL_DEFAULT}. {@code scopeLabel} is a
     *  human-readable name suitable for chips in the inspector and the
     *  work-model pill (e.g. {@code "workspace ByteQuay"},
     *  {@code "ByteQuay default"}). */
    record Provenance(Source source, String scopeId, String scopeLabel) {}

    /** Tag identifying which scope the resolver picked. */
    enum Source
    {
        /** The stage carried an override. */
        STAGE,
        /** The stage (if any) had no override; the task did. */
        TASK,
        /** Neither stage nor task had an override; the thread did. */
        THREAD,
        /** Neither task nor thread had an override; the workspace did. */
        WORKSPACE,
        /** No scope carried an override — the catalog's first CLI agent
         *  + its default model is the fallback. */
        GLOBAL_DEFAULT,
    }
}
