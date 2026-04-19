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
import java.util.Optional;

/**
 * Query parameters shared by the two review-comment list operations.
 * Used with GET /repos/{owner}/{repo}/pulls/{pull_number}/comments
 * and GET /repos/{owner}/{repo}/pulls/comments.
 *
 * @param sort      field to sort by: "created" or "updated" (default: "created")
 * @param direction sort direction: "asc" or "desc"
 * @param since     only return comments updated at or after this time; empty means no lower bound
 * @param perPage   page size, 1–100 (default: 30)
 * @param page      1-based page number (default: 1)
 */
public record CommentListQuery(
        String sort,
        String direction,
        Optional<Instant> since,
        int perPage,
        int page)
{
    public static CommentListQuery defaults()
    {
        return new CommentListQuery("created", "desc", Optional.empty(), 30, 1);
    }

    public static CommentListQuery since(Instant cutoff)
    {
        return new CommentListQuery("updated", "asc", Optional.of(cutoff), 100, 1);
    }
}
