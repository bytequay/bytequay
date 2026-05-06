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

import java.util.List;

/**
 * One page of historic (closed/merged) pull requests served by
 * {@code GET /prs/history}. Driven by GitHub search, so {@code totalCount}
 * is the server-reported total (capped at 1000 by GitHub) and
 * {@code hasMore} signals whether the next page is worth fetching.
 */
public record PullRequestHistoryPage(
        List<PullRequest> items,
        int page,
        int perPage,
        int totalCount,
        boolean hasMore) {}
