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

import com.bytequay.core.backport.BackportChain;
import com.bytequay.core.domain.AssistantDay;
import com.bytequay.core.domain.AssistantItem;
import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.GoalCourse;
import com.bytequay.core.domain.GoalDefinition;
import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.domain.GoalKnowledgeNote;
import com.bytequay.core.domain.GoalLesson;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.GoalTopic;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryPickerUser;
import com.bytequay.core.domain.TeamActivityEvent;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemoryReport;
import com.bytequay.core.memory.MemorySource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.Converter;

import java.util.List;
import java.util.Map;

/** The JSON text column converters that only name their value type, error label and empty column and value. */
public final class JsonColumnConverters
{
    private JsonColumnConverters() {}

    @Converter
    public static class AssistantDayConverter
            extends JsonColumnConverter<AssistantDay>
    {
        AssistantDayConverter()
        {
            super(new TypeReference<>() {}, "assistant day", null, null);
        }
    }

    @Converter
    public static class AssistantItemConverter
            extends JsonColumnConverter<AssistantItem>
    {
        AssistantItemConverter()
        {
            super(new TypeReference<>() {}, "assistant item", null, null);
        }
    }

    @Converter
    public static class BackportChainConverter
            extends JsonColumnConverter<BackportChain>
    {
        public BackportChainConverter()
        {
            super(new TypeReference<>() {}, "backport chain", null, null);
        }
    }

    @Converter
    public static class DiffCommitListConverter
            extends JsonColumnConverter<List<DiffCommit>>
    {
        DiffCommitListConverter()
        {
            super(new TypeReference<>() {}, "pull request diff commits", "[]", ImmutableList.of());
        }
    }

    @Converter
    public static class DiffFileContentMapConverter
            extends JsonColumnConverter<Map<String, DiffFileContent>>
    {
        DiffFileContentMapConverter()
        {
            super(new TypeReference<>() {}, "pull request file contents", "{}", ImmutableMap.of());
        }
    }

    @Converter
    public static class DiffFileListConverter
            extends JsonColumnConverter<List<DiffFile>>
    {
        DiffFileListConverter()
        {
            super(new TypeReference<>() {}, "pull request diff files", "[]", ImmutableList.of());
        }
    }

    @Converter
    public static class GoalCourseConverter
            extends JsonColumnConverter<GoalCourse>
    {
        GoalCourseConverter()
        {
            super(new TypeReference<>() {}, "goal course", null, null);
        }
    }

    @Converter
    public static class GoalDefinitionConverter
            extends JsonColumnConverter<GoalDefinition>
    {
        GoalDefinitionConverter()
        {
            super(new TypeReference<>() {}, "goal definition", null, null);
        }
    }

    @Converter
    public static class GoalExchangeConverter
            extends JsonColumnConverter<GoalExchange>
    {
        GoalExchangeConverter()
        {
            super(new TypeReference<>() {}, "goal exchange", null, null);
        }
    }

    @Converter
    public static class GoalKnowledgeNoteConverter
            extends JsonColumnConverter<GoalKnowledgeNote>
    {
        GoalKnowledgeNoteConverter()
        {
            super(new TypeReference<>() {}, "goal knowledge note", null, null);
        }
    }

    @Converter
    public static class GoalLessonConverter
            extends JsonColumnConverter<GoalLesson>
    {
        GoalLessonConverter()
        {
            super(new TypeReference<>() {}, "goal lesson", null, null);
        }
    }

    @Converter
    public static class GoalRecommendationConverter
            extends JsonColumnConverter<GoalRecommendation>
    {
        GoalRecommendationConverter()
        {
            super(new TypeReference<>() {}, "goal recommendation", null, null);
        }
    }

    @Converter
    public static class GoalTopicConverter
            extends JsonColumnConverter<GoalTopic>
    {
        GoalTopicConverter()
        {
            super(new TypeReference<>() {}, "goal topic", null, null);
        }
    }

    @Converter
    public static class IssueTimelineListConverter
            extends JsonColumnConverter<List<PrTimelineEvent>>
    {
        IssueTimelineListConverter()
        {
            super(new TypeReference<>() {}, "issue timeline", "[]", ImmutableList.of());
        }
    }

    @Converter
    public static class MemoryPreviousConverter
            extends JsonColumnConverter<Memory.Previous>
    {
        MemoryPreviousConverter()
        {
            super(new TypeReference<>() {}, "memory previous version", null, null);
        }
    }

    @Converter
    public static class MemoryReportListConverter
            extends JsonColumnConverter<List<MemoryReport>>
    {
        MemoryReportListConverter()
        {
            super(new TypeReference<>() {}, "memory report list", "[]", ImmutableList.of());
        }
    }

    @Converter
    public static class MemorySourceListConverter
            extends JsonColumnConverter<List<MemorySource>>
    {
        MemorySourceListConverter()
        {
            super(new TypeReference<>() {}, "memory source list", "[]", ImmutableList.of());
        }
    }

    @Converter
    public static class PrActivitySummaryConverter
            extends JsonColumnConverter<PrActivitySummary>
    {
        public PrActivitySummaryConverter()
        {
            super(new TypeReference<>() {}, "pull request activity summary", null, null);
        }
    }

    @Converter
    public static class PrTimelinePayloadConverter
            extends JsonColumnConverter<PrTimelinePayload>
    {
        public PrTimelinePayloadConverter()
        {
            super(new TypeReference<>() {}, "PR timeline payload", null, PrTimelinePayload.empty());
        }
    }

    @Converter
    public static class RepositoryContributorListConverter
            extends JsonColumnConverter<List<RepositoryContributor>>
    {
        RepositoryContributorListConverter()
        {
            super(new TypeReference<>() {}, "repository contributor list", "[]", ImmutableList.of());
        }
    }

    @Converter
    public static class RepositoryLabelListConverter
            extends JsonColumnConverter<List<RepositoryLabel>>
    {
        RepositoryLabelListConverter()
        {
            super(new TypeReference<>() {}, "repository label list", "[]", ImmutableList.of());
        }
    }

    @Converter
    public static class RepositoryPickerUserListConverter
            extends JsonColumnConverter<List<RepositoryPickerUser>>
    {
        RepositoryPickerUserListConverter()
        {
            super(new TypeReference<>() {}, "repository picker user list", "[]", ImmutableList.of());
        }
    }

    @Converter
    public static class StringLongMapConverter
            extends JsonColumnConverter<Map<String, Long>>
    {
        StringLongMapConverter()
        {
            super(new TypeReference<>() {}, "string-long map", "{}", ImmutableMap.of());
        }
    }

    @Converter
    public static class StringListConverter
            extends JsonColumnConverter<List<String>>
    {
        StringListConverter()
        {
            super(new TypeReference<>() {}, "string list", "[]", ImmutableList.of());
        }
    }

    @Converter
    public static class StringMapConverter
            extends JsonColumnConverter<Map<String, String>>
    {
        StringMapConverter()
        {
            super(new TypeReference<>() {}, "string map", null, ImmutableMap.of());
        }
    }

    @Converter
    public static class TeamActivityEventConverter
            extends JsonColumnConverter<TeamActivityEvent>
    {
        public TeamActivityEventConverter()
        {
            super(new TypeReference<>() {}, "team activity event", null, null);
        }
    }

    @Converter
    public static class WorkItemConverter
            extends JsonColumnConverter<WorkItem>
    {
        WorkItemConverter()
        {
            super(new TypeReference<>() {}, "work item", null, null);
        }
    }

    @Converter
    public static class EpicConverter
            extends JsonColumnConverter<Epic>
    {
        EpicConverter()
        {
            super(new TypeReference<>() {}, "Epic", null, null);
        }
    }
}
