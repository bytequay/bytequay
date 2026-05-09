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
import java.util.List;
import java.util.Map;

/**
 * Repo-level metadata served by {@code GET /api/repos/{owner}/{repo}/meta}.
 * Drives the right-pane hero / About / language bar on the repo PR detail
 * page (per docs/design/pr-dashboard/repo-prs.png).
 *
 * Combines GitHub's {@code GET /repos/{owner}/{repo}} body with the
 * {@code /languages} byte map so the frontend can render the language bar
 * without a second round-trip.
 */
public record RepoMeta(
        String fullName,
        String htmlUrl,
        String description,
        String defaultBranch,
        String license,
        int stargazersCount,
        int forksCount,
        int watchersCount,
        int openIssuesCount,
        long sizeKb,
        Instant createdAt,
        Instant pushedAt,
        List<String> topics,
        /** Map from language name to byte-count, as returned by GitHub's
         *  {@code /repos/{owner}/{repo}/languages}. The frontend computes
         *  percentages and renders the language bar. Empty when the repo
         *  has no detectable code (rare). */
        Map<String, Long> languages,
        /** GitHub's {@code owner.avatar_url} — used by the repo avatar
         *  on the overview page. Null on legacy rows persisted before
         *  this field was added; the frontend falls back to a
         *  colour-and-letter placeholder. */
        String ownerAvatarUrl) {}
