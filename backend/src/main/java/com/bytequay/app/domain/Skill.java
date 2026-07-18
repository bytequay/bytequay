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
 * A ByteQuay-managed context module. ByteQuay selects a bounded set before
 * dispatch; providers do not discover or load skills themselves. Rubrics are
 * selected by the review path.
 *
 * @param scope        'global' (every workspace), 'repo' (the value of
 *                     {@code repo} is the {@code owner/name} it targets),
 *                     or 'thread' (one specific thread)
 * @param repo         {@code owner/name} when scope = 'repo'; null otherwise
 * @param threadId     the bound thread when scope = 'thread'; null otherwise
 * @param description  the trigger blurb used by ByteQuay's selector
 * @param body         the prompt body ByteQuay injects when selected
 * @param kind         'library' (general reusable context),
 *                     'persona' (always-on identity for a role), or
 *                     'rubric' (deterministic review-time rule)
 * @param roleTag      binds the skill to a specific agent role (e.g.
 *                     "reviewer") independently of scope; null when the
 *                     skill is role-agnostic
 * @param isDefault    when true the row is the default for its
 *                     (scope, repo, kind, roleTag) group; used to pick a
 *                     persona per repo when several exist
 * @param source       'authored' for hand-typed skills, 'ai_drafted' for
 *                     ones produced by the draft endpoint
 * @param provenance   free-form note on where the skill came from (the
 *                     prompt that drafted it, the brain section it was
 *                     distilled from)
 * @param contentHash  hash of the body, set on insert / update so the
 *                     runtime can detect content drift
 */
public record Skill(
        long id,
        String scope,
        String repo,
        String threadId,
        String name,
        String description,
        String body,
        String kind,
        /** Surface the skill belongs to: {@code review} rows are
         *  selectable as reviewer roles (and only there); {@code build}
         *  rows feed the build/task agents' skill selector (and only
         *  them). */
        String usage,
        String roleTag,
        boolean enabled,
        boolean isDefault,
        String source,
        String provenance,
        String contentHash,
        Instant createdAt,
        Instant updatedAt)
{
    /** Pre-usage call sites — defaults to the build surface. */
    public Skill(
            long id,
            String scope,
            String repo,
            String threadId,
            String name,
            String description,
            String body,
            String kind,
            String roleTag,
            boolean enabled,
            boolean isDefault,
            String source,
            String provenance,
            String contentHash,
            Instant createdAt,
            Instant updatedAt)
    {
        this(id, scope, repo, threadId, name, description, body, kind, "build", roleTag,
                enabled, isDefault, source, provenance, contentHash, createdAt, updatedAt);
    }
}
