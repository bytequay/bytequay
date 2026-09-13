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
package com.bytequay.core.localreview;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Immutable explanation evidence copied from a verified task summary, never supplied by the renderer. */
public record LocalReviewLearningAnchor(String turnId, String cardId, String title, String body)
{
    public LocalReviewLearningAnchor
    {
        requireNonNull(turnId, "turnId is null");
        requireNonNull(cardId, "cardId is null");
        requireNonNull(title, "title is null");
        requireNonNull(body, "body is null");
        checkArgument(!turnId.isBlank() && turnId.length() <= 100, "Invalid summary turn id");
        checkArgument(!cardId.isBlank() && cardId.length() <= 100, "Invalid learning card id");
        checkArgument(!title.isBlank() && title.length() <= 510, "Invalid learning card title");
        checkArgument(!body.isBlank() && body.length() <= 60_000, "Invalid learning card body");
    }
}
