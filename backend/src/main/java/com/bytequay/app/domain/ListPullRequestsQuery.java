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

import java.util.Optional;

/**
 * Query parameters for listing pull requests in a repository.
 * Maps to the query string of GET /repos/{owner}/{repo}/pulls.
 *
 * @param state     filter by state: "open" (default), "closed", or "all"
 * @param head      filter by head branch in the form "user:ref-name"; empty means no filter
 * @param base      filter by base branch name; empty means no filter
 * @param sort      sort field: "created" (default), "updated", "popularity", or "long-running"
 * @param direction sort direction: "asc" or "desc" (default: desc for created/updated, asc otherwise)
 * @param perPage   results per page, 1–100 (default 30)
 * @param page      1-based page number (default 1)
 */
public record ListPullRequestsQuery(
        String state,
        Optional<String> head,
        Optional<String> base,
        String sort,
        String direction,
        int perPage,
        int page)
{
    public static ListPullRequestsQuery openPullRequests()
    {
        return new ListPullRequestsQuery("open", Optional.empty(), Optional.empty(), "created", "desc", 30, 1);
    }

    public static ListPullRequestsQuery allPullRequests()
    {
        return new ListPullRequestsQuery("all", Optional.empty(), Optional.empty(), "updated", "desc", 30, 1);
    }
}
