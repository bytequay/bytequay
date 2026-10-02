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
package com.bytequay.app.review;

import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftItem;

import java.util.List;
import java.util.Optional;

/**
 * The user's local pending review for one pull request, and the only path from it to GitHub.
 *
 * <p>Nothing here publishes by itself: every send is one explicit user action that revalidates the
 * pull request and the exact saved item payload before ByteQuay's existing GitHub
 * write path is called.
 */
public interface ReviewPublicationService
{
    ReviewDraft draft(String repo, int number);

    /** Adds a manual inline comment to the draft. */
    ReviewDraft addManual(String repo, int number, long expectedVersion, ReviewDraftItem item);

    /**
     * Snapshots a finding into the draft as an editable outgoing comment. The finding keeps its own
     * identity and body; edits and resolution on either side never change the other.
     */
    ReviewDraft addFinding(String repo, int number, long expectedVersion, String reviewId, String findingId);

    ReviewDraft editItem(String repo, int number, long expectedVersion, String itemId, String body, boolean included);

    ReviewDraft removeItem(String repo, int number, long expectedVersion, String itemId);

    /** Persists the review text and event the sheet holds; new drafts default to COMMENT. */
    ReviewDraft saveReview(String repo, int number, long expectedVersion, String body, String event);

    /** Publishes exactly one previewed item, reserving it first so no other route can send it twice. */
    Publication publishItem(String repo, int number, long expectedVersion, String itemId);

    /** Publishes the included, eligible items with the user's chosen review text and event. */
    Publication submit(String repo, int number, long expectedVersion, String body, String event);

    /** Why each draft item cannot be sent right now, by item id; an eligible item is absent. */
    List<Ineligible> ineligible(String repo, int number);

    record Publication(ReviewDraft draft, List<String> publishedItemIds, Optional<String> uncertainReason) {}

    record Ineligible(String itemId, String reason) {}
}
