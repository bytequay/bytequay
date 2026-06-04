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
package com.bytequay.app.service.tools;

/**
 * Coarse-grained capability axis a tool exercises. The
 * {@link com.bytequay.app.service.tools.PermissionResolver} resolves
 * a {@code Set<SecurityType>} for the caller, and the registry refuses
 * any tool whose {@code security} isn't in that set.
 *
 * <p>Capability ≠ permission: this enum says <em>what kind of
 * operation</em> a tool performs; whether the caller is allowed to
 * perform it lives in the resolver. The role-map resolver landing
 * in Phase B is a first cut; the global → workspace → thread → task
 * cascade replaces it in Phase D / the permissions axis.
 */
public enum SecurityType
{
    /** Read source code, file metadata, blobs from the local clone or
     *  GitHub. No mutations. */
    CODE_READ,
    /** Stage / unstage / write files in a worktree. */
    CODE_WRITE,
    /** Execute code (build, run scripts, exec tests). */
    CODE_EXEC,
    /** Local git operations that don't reach the remote (commit,
     *  worktree add, status, diff). */
    GIT_LOCAL,
    /** Operations that reach the git remote (push). */
    GIT_PUSH,
    /** Read GitHub / forge metadata: PRs, issues, CI, reviews, comments. */
    VCS_READ,
    /** Publish to GitHub / forge: open PR, approve, merge, comment,
     *  request review, set state. */
    VCS_PUBLISH,
    /** Read thread / task state from the local DB. */
    TASK_READ,
    /** Mutate thread / task state: create_task, ship_task, park, etc. */
    TASK_MANAGE,
    /** Read workspace memory / brain. */
    MEMORY_READ,
    /** Write workspace memory / brain. */
    MEMORY_WRITE,
    /** Load and apply skill bodies at turn time. */
    SKILL_USE,
    /** Enumerate the tool catalog itself. */
    TOOL_DISCOVER,
    /** Enumerate / look up domain terms via the concept axis
     *  ({@code list_terms} / {@code lookup_term}). Read-only and
     *  granted broadly — every role that can call tools at all
     *  should have it. */
    CONCEPT_USE,
    /** Interact with external MCP servers (sampling, prompts, etc.).
     *  Today only the approval_prompt tool uses this. */
    MCP,
}
