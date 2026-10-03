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
package com.bytequay.app.service.goal;

import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.store.GoalRecommendationStore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

final class InMemoryGoalRecommendationStore
        implements GoalRecommendationStore
{
    private final Map<String, GoalRecommendation> cards = new LinkedHashMap<>();

    @Override
    public Optional<GoalRecommendation> find(String recommendationId)
    {
        return Optional.ofNullable(cards.get(recommendationId));
    }

    @Override
    public List<GoalRecommendation> list(String goalId)
    {
        return cards.values().stream().filter(card -> card.goalId().equals(goalId))
                .sorted(Comparator.comparing(GoalRecommendation::createdAt).thenComparing(GoalRecommendation::id)).collect(toImmutableList());
    }

    @Override
    public void save(GoalRecommendation recommendation)
    {
        cards.put(recommendation.id(), recommendation);
    }
}
