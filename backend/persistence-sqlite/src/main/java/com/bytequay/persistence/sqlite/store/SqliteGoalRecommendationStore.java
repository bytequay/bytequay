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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.store.GoalRecommendationStore;
import com.bytequay.persistence.sqlite.entity.GoalRecommendationEntity;
import com.bytequay.persistence.sqlite.repository.GoalRecommendationJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteGoalRecommendationStore
        implements GoalRecommendationStore
{
    private final GoalRecommendationJpaRepository recommendations;

    public SqliteGoalRecommendationStore(GoalRecommendationJpaRepository recommendations)
    {
        this.recommendations = requireNonNull(recommendations, "recommendations is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GoalRecommendation> find(String recommendationId)
    {
        return recommendations.findById(recommendationId).map(GoalRecommendationEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GoalRecommendation> list(String goalId)
    {
        return recommendations.findByGoalIdOrderByCreatedAtAscIdAsc(goalId).stream().map(GoalRecommendationEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional
    public void save(GoalRecommendation recommendation)
    {
        // Updates the loaded row; only a card with no row yet is persisted as new.
        GoalRecommendationEntity entity = recommendations.findById(recommendation.id()).orElseGet(() -> new GoalRecommendationEntity(recommendation));
        entity.update(recommendation);
        recommendations.saveAndFlush(entity);
    }
}
