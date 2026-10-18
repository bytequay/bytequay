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

import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemContextLink;
import com.bytequay.core.domain.WorkItemEvent;
import com.bytequay.persistence.sqlite.converter.JsonColumnConverters.WorkItemConverter;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestWorkItemConverter
{
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private final WorkItemConverter converter = new WorkItemConverter();

    @Test
    void keepsPriorityOrderBlockersAndEpicAndReadsWorkSavedBeforeThem()
    {
        WorkItem ordered = item(WorkItem.Priority.HIGH, 3, ImmutableList.of("work-0"), Optional.of("ISSUE:github.com:1"), 2);
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(ordered))).isEqualTo(ordered);

        String older = converter.convertToDatabaseColumn(item(WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.empty(), 0))
                .replace(",\"priority\":\"NORMAL\"", "").replace(",\"rank\":0", "").replace(",\"blockedBy\":[]", "")
                .replace(",\"epicId\":null", "").replace(",\"epicRank\":0", "");
        assertThat(older).doesNotContain("priority", "rank", "blockedBy", "epicId", "epicRank");
        assertThat(converter.convertToEntityAttribute(older)).isEqualTo(item(WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.empty(), 0));
    }

    @Test
    void retainsContextualReferencesAndReadsTheirAbsenceAsNoReferences()
    {
        WorkItem original = item(WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.empty(), 0);
        WorkItem linked = new WorkItem(original.id(), original.workspaceId(), original.goalId(), original.title(), original.kind(), original.acceptanceCriteria(),
                original.completionRule(), original.state(), original.revision(), original.links(), original.history(), NOW, NOW, original.priority(), original.rank(),
                original.blockedBy(), original.epicId(), original.epicRank(), ImmutableList.of(new WorkItemContextLink("ISSUE:github.com:42", "Related issue")));
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(linked))).isEqualTo(linked);
        String older = converter.convertToDatabaseColumn(original).replace(",\"contextLinks\":[]", "");
        assertThat(older).doesNotContain("contextLinks");
        assertThat(converter.convertToEntityAttribute(older)).isEqualTo(original);
    }

    private static WorkItem item(WorkItem.Priority priority, long rank, ImmutableList<String> blockedBy, Optional<String> epicId, long epicRank)
    {
        return new WorkItem("work-1", "workspace", Optional.of("goal"), "Fix", WorkItem.Kind.DEVELOP, "Covers the bug", WorkItem.CompletionRule.MERGED_DELIVERY,
                WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(new WorkItemEvent("op", "input", "CREATED", "Accepted", NOW)), NOW, NOW,
                priority, rank, blockedBy, epicId, epicRank, ImmutableList.of());
    }
}
