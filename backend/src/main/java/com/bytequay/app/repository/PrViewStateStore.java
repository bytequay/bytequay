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
package com.bytequay.app.repository;

import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.PrViewState;

import java.util.Map;

public interface PrViewStateStore
{
    /** Returns all view-state rows, keyed by PR id. */
    Map<Long, PrViewState> findAll();

    /** Records that the user viewed this PR for the first time. Idempotent. */
    void markViewed(long prId);

    /** Records that the user handled this PR with the given action. */
    void markReviewed(long prId, HandledAction action);

    /** Clears the reviewed timestamp and action so the PR returns to the Inbox. */
    void reopen(long prId);
}
