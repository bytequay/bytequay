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
package com.bytequay.core.store;

import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.PrViewState;

import java.util.Map;
import java.util.Set;

public interface PrViewStateStore
{
    Map<Long, PrViewState> findByPrIds(Set<Long> prIds);

    /**
     * Records that the user opened this pull request. Independent of
     * {@link #markReviewed}, which only fires when a verdict is submitted, so
     * this is what dates the "since you last looked" comparison.
     */
    void markViewed(long prId);

    void markReviewed(long prId, HandledAction action, String headSha);

    void markNeedsReview(long prId);
}
