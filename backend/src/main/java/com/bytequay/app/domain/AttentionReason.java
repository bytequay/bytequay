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

/**
 * Why a PR was promoted into the "Needs attention" Kanban column. Drives
 * the colored banner across the card top. See
 * {@code docs/design/settings-redesign.md} §6.5 for the v1 promotion rules.
 *
 * <p>NULL on a PR record means it is not promoted.
 */
public enum AttentionReason
{
    /** A failing check run on a PR you're reviewing or have authored. */
    CI_FAILING,
    /** Your authored PR no longer cleanly merges into its base (typically
     *  because base advanced and now collides with your changes). */
    MERGE_CONFLICT,
    /** New @-mention of you in a comment, since you last viewed the PR. */
    MENTIONED,
    /** New comment or review activity on your authored PR by someone other
     *  than you, since you last viewed the PR. */
    NEW_COMMENT,
    /** Author flagged the PR as blocking (label or "blocking:" prefix). */
    BLOCKING,
    /** Hasn't progressed in 7+ days. */
    STALE,
    /** Catch-all for PRs you authored — surfaces every authored PR even
     *  when no other rule fires, so the user has a single "my open PRs"
     *  view from the attention column. Lowest precedence — any of the
     *  rules above wins for the colored banner. */
    MINE
}
