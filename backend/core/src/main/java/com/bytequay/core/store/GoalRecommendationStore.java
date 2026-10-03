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

import com.bytequay.core.domain.GoalRecommendation;

import java.util.List;
import java.util.Optional;

public interface GoalRecommendationStore
{
    Optional<GoalRecommendation> find(String recommendationId);

    /** The goal's cards in every state, oldest first. */
    List<GoalRecommendation> list(String goalId);

    /** Inserts the card or replaces the stored one with the same id. */
    void save(GoalRecommendation recommendation);
}
