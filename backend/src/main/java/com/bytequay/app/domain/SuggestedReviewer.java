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
 * One reviewer GitHub recommends for a PR — derived from blame on the
 * touched files plus the requestor's review history. Surfaced in the
 * Add-reviewer UI as one-click chips above the typeahead so common
 * reviewers can be picked without typing.
 *
 * <p>Source: GraphQL {@code pullRequest.suggestedReviewers}. REST has no
 * equivalent endpoint — this is the only path to the same suggestions
 * github.com shows on the conversation page's reviewers picker.
 */
public record SuggestedReviewer(
        String login,
        String avatarUrl,
        String name,
        boolean isAuthor,
        boolean isCommenter) {}
