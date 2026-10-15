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
package com.bytequay.persistence.sqlite.converter;

import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.persistence.sqlite.converter.JsonColumnConverters.GoalRecommendationConverter;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestGoalRecommendationConverter
{
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    private final GoalRecommendationConverter converter = new GoalRecommendationConverter();

    @Test
    void keepsTheWorkAStartedWorkCardBecameAndReadsCardsSavedBeforeLaterFields()
    {
        GoalRecommendation shown = new GoalRecommendation("card-1", "goal-1", "workspace-1", new GoalRecommendation.Card(GoalRecommendation.Kind.WORK, "Fix #31252",
                "You learned how checkpoints reconcile actions", "Key checkpoint actions by path and deletion vector", ImmutableList.of(), Optional.empty(),
                Optional.of(31252), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), GoalRecommendation.State.SHOWN,
                Optional.empty(), Optional.empty(), Optional.empty(), "op-1", NOW, NOW);
        GoalRecommendation started = shown.working("work-1", NOW.plusSeconds(1));
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(started))).isEqualTo(started);

        // A card saved before work, check, ticket, module, prerequisites and criteria fields existed reads without them.
        String older = converter.convertToDatabaseColumn(shown);
        for (String field : new String[] {"workItemId", "checkId", "ticket", "module", "reproductions", "prerequisites", "criteria"}) {
            older = older.replace(",\"" + field + "\":null", "");
            assertThat(older).doesNotContain(field);
        }
        assertThat(converter.convertToEntityAttribute(older)).isEqualTo(shown);
    }
}
