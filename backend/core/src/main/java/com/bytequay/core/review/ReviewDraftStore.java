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
package com.bytequay.core.review;

import java.util.List;
import java.util.Optional;

/** Durable storage for one pull request's local pending review. */
public interface ReviewDraftStore
{
    Optional<ReviewDraft> find(String id);

    /** Finds the hosting-scoped draft, adopting a retained GitHub.com draft when its identity is unambiguous. */
    Optional<ReviewDraft> find(long repositoryId, int number);

    /**
     * Inserts a new draft or replaces the stored one whose version matches; the returned draft
     * carries the next version.
     *
     * @throws ReviewConflictException when the stored version moved
     */
    ReviewDraft save(ReviewDraft draft);

    /** Commits confirmed publication and its source finding resolutions in one transaction. */
    ReviewDraft savePublished(ReviewDraft draft, List<ReviewFinding> findings);
}
