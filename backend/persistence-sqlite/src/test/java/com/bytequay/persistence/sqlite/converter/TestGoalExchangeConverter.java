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

import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.domain.WorkItem;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestGoalExchangeConverter
{
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    private final GoalExchangeConverter converter = new GoalExchangeConverter();

    @Test
    void keepsACourseRevisionAndReadsCoursesSavedBeforeRevisionsExisted()
    {
        GoalCourse.Draft revision = new GoalCourse.Draft(ImmutableList.of(new GoalCourse.DraftPart("The connector", ImmutableList.of(
                new GoalCourse.DraftLesson("Plan a scan", "Explain splits", Optional.of("lesson-3")),
                new GoalCourse.DraftLesson("Read deletes", "Explain position deletes", Optional.empty())))), Optional.of("You know Iceberg tables."));
        GoalExchange revised = answered(revision);
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(revised))).isEqualTo(revised);

        GoalCourse.Draft first = new GoalCourse.Draft(ImmutableList.of(new GoalCourse.DraftPart("Iceberg tables", ImmutableList.of(
                new GoalCourse.DraftLesson("What a table is", "Explain metadata", Optional.empty())))), Optional.empty());
        String older = converter.convertToDatabaseColumn(answered(first)).replace(",\"lessonId\":null", "").replace(",\"reason\":null", "");
        assertThat(older).doesNotContain("lessonId", "reason");
        assertThat(converter.convertToEntityAttribute(older)).isEqualTo(answered(first));
    }

    @Test
    void keepsAPlanChangeAndReadsRepliesSavedBeforePlanChanges()
    {
        GoalExchange.PlanChange plan = new GoalExchange.PlanChange(ImmutableList.of(
                new GoalExchange.WorkChange("work-2", Optional.of(WorkItem.Priority.HIGH), Optional.of(ImmutableList.of())),
                new GoalExchange.WorkChange("work-3", Optional.empty(), Optional.of(ImmutableList.of("work-2")))),
                Optional.of(ImmutableList.of("work-2", "work-3")), "The test proves the fix before the backport");
        GoalExchange planned = GoalExchange.ask("op-2", "goal-1", "workspace-1", "Backport first?", 1, NOW).answered("codex/gpt",
                new GoalExchange.Reply("Test first.", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), Optional.empty(),
                        Optional.of(plan)),
                NOW);
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(planned))).isEqualTo(planned);

        GoalCourse.Draft course = new GoalCourse.Draft(ImmutableList.of(new GoalCourse.DraftPart("Iceberg tables", ImmutableList.of(
                new GoalCourse.DraftLesson("What a table is", "Explain metadata", Optional.empty())))), Optional.empty());
        String older = converter.convertToDatabaseColumn(answered(course)).replace(",\"plan\":null", "");
        assertThat(older).doesNotContain("\"plan\"");
        assertThat(converter.convertToEntityAttribute(older)).isEqualTo(answered(course));
    }

    private static GoalExchange answered(GoalCourse.Draft course)
    {
        return GoalExchange.ask("op-1", "goal-1", "workspace-1", "What next?", 1, NOW).answered("codex/gpt",
                new GoalExchange.Reply("Here is a course.", Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(course), ImmutableList.of(), Optional.empty(),
                        Optional.empty()),
                NOW);
    }
}
