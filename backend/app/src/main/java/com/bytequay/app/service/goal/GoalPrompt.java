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

import com.bytequay.core.domain.GoalExchange;
import com.bytequay.core.domain.GoalLesson;
import com.bytequay.core.domain.GoalRecommendation;
import com.bytequay.core.domain.GoalTopic;
import com.bytequay.core.domain.WorkItem;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Immutable, deliberately limited projections of the data supplied to Goal helpers. */
final class GoalPrompt
{
    private GoalPrompt() {}

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record MapData(Optional<String> repository, String goal, String objective, List<CoursePart> course, List<Note> notes,
            Optional<GoalSourceContext.Evidence> sourceContext)
    {
        MapData
        {
            requireNonNull(repository, "repository is null");
            requireNonNull(goal, "goal is null");
            requireNonNull(objective, "objective is null");
            course = ImmutableList.copyOf(course);
            notes = ImmutableList.copyOf(notes);
            requireNonNull(sourceContext, "sourceContext is null");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record LessonData(Optional<String> repository, String goal, String objective, Optional<String> startingKnowledge, Lesson lesson, List<CoursePart> course,
            Optional<GoalLesson.Diagram> courseMap, List<CitedNote> notes, Optional<GoalSourceContext.Evidence> sourceContext)
    {
        LessonData
        {
            requireNonNull(repository, "repository is null");
            requireNonNull(goal, "goal is null");
            requireNonNull(objective, "objective is null");
            requireNonNull(startingKnowledge, "startingKnowledge is null");
            requireNonNull(lesson, "lesson is null");
            course = ImmutableList.copyOf(course);
            requireNonNull(courseMap, "courseMap is null");
            notes = ImmutableList.copyOf(notes);
            requireNonNull(sourceContext, "sourceContext is null");
        }
    }

    record Lesson(String part, String title, String outcome)
    {
        Lesson
        {
            requireNonNull(part, "part is null");
            requireNonNull(title, "title is null");
            requireNonNull(outcome, "outcome is null");
        }
    }

    record CoursePart(String part, List<Chapter> chapters)
    {
        CoursePart
        {
            requireNonNull(part, "part is null");
            chapters = ImmutableList.copyOf(chapters);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record Chapter(String title, String outcome, Optional<Boolean> thisChapter)
    {
        Chapter
        {
            requireNonNull(title, "title is null");
            requireNonNull(outcome, "outcome is null");
            requireNonNull(thisChapter, "thisChapter is null");
        }
    }

    record Note(String topic, String body)
    {
        Note
        {
            requireNonNull(topic, "topic is null");
            requireNonNull(body, "body is null");
        }
    }

    record CitedNote(String topic, String body, List<String> sources)
    {
        CitedNote
        {
            requireNonNull(topic, "topic is null");
            requireNonNull(body, "body is null");
            sources = ImmutableList.copyOf(sources);
        }
    }

    record Summary(String goal, String objective, WorkSummary work)
    {
        Summary
        {
            requireNonNull(goal, "goal is null");
            requireNonNull(objective, "objective is null");
            requireNonNull(work, "work is null");
        }
    }

    record WorkSummary(String title, WorkItem.Kind kind, String acceptanceCriteria, WorkItem.CompletionRule completionRule, List<String> links, List<History> history)
    {
        WorkSummary
        {
            requireNonNull(title, "title is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(acceptanceCriteria, "acceptanceCriteria is null");
            requireNonNull(completionRule, "completionRule is null");
            links = ImmutableList.copyOf(links);
            history = ImmutableList.copyOf(history);
        }
    }

    record History(String kind, String at, String detail)
    {
        History
        {
            requireNonNull(kind, "kind is null");
            requireNonNull(at, "at is null");
            requireNonNull(detail, "detail is null");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record Facts(Optional<String> repository, List<String> dependencies, Objective goal, Optional<List<Candidate>> practiceCandidates,
            Optional<CandidateCoverage> candidateCoverage, Optional<List<Gap>> lastSpecCheckGaps, Optional<List<Module>> specCheckModules, List<Topic> topics,
            List<Question> recentQuestions, List<Card> cards, List<Knowledge> knowledgeNotes)
    {
        Facts
        {
            requireNonNull(repository, "repository is null");
            dependencies = ImmutableList.copyOf(dependencies);
            requireNonNull(goal, "goal is null");
            requireNonNull(practiceCandidates, "practiceCandidates is null");
            practiceCandidates = practiceCandidates.map(ImmutableList::copyOf);
            requireNonNull(candidateCoverage, "candidateCoverage is null");
            requireNonNull(lastSpecCheckGaps, "lastSpecCheckGaps is null");
            lastSpecCheckGaps = lastSpecCheckGaps.map(ImmutableList::copyOf);
            requireNonNull(specCheckModules, "specCheckModules is null");
            specCheckModules = specCheckModules.map(ImmutableList::copyOf);
            topics = ImmutableList.copyOf(topics);
            recentQuestions = ImmutableList.copyOf(recentQuestions);
            cards = ImmutableList.copyOf(cards);
            knowledgeNotes = ImmutableList.copyOf(knowledgeNotes);
        }

        Facts withoutNoteBodies()
        {
            return new Facts(repository, dependencies, goal, practiceCandidates, candidateCoverage, lastSpecCheckGaps, specCheckModules, topics, recentQuestions, cards,
                    knowledgeNotes.stream().map(note -> new Knowledge(note.topic(), note.stale(), Optional.empty())).collect(toImmutableList()));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record Objective(String title, String objective, Optional<Definition> acceptedDefinition, Optional<List<AcceptedPart>> acceptedCourse, List<Work> workItems)
    {
        Objective
        {
            requireNonNull(title, "title is null");
            requireNonNull(objective, "objective is null");
            requireNonNull(acceptedDefinition, "acceptedDefinition is null");
            requireNonNull(acceptedCourse, "acceptedCourse is null");
            acceptedCourse = acceptedCourse.map(ImmutableList::copyOf);
            workItems = ImmutableList.copyOf(workItems);
        }
    }

    record Definition(String id, String successDefinition, String startingKnowledge, String constraints)
    {
        Definition
        {
            requireNonNull(id, "id is null");
            requireNonNull(successDefinition, "successDefinition is null");
            requireNonNull(startingKnowledge, "startingKnowledge is null");
            requireNonNull(constraints, "constraints is null");
        }
    }

    record AcceptedPart(String part, List<AcceptedLesson> lessons)
    {
        AcceptedPart
        {
            requireNonNull(part, "part is null");
            lessons = ImmutableList.copyOf(lessons);
        }
    }

    record AcceptedLesson(String id, String title, WorkItem.State state)
    {
        AcceptedLesson
        {
            requireNonNull(id, "id is null");
            requireNonNull(title, "title is null");
            requireNonNull(state, "state is null");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record Candidate(String id, String title, List<String> labels, Optional<String> status, Optional<String> priority)
    {
        Candidate
        {
            requireNonNull(id, "id is null");
            requireNonNull(title, "title is null");
            labels = ImmutableList.copyOf(labels);
            requireNonNull(status, "status is null");
            requireNonNull(priority, "priority is null");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record CandidateCoverage(int issuesRead, long issuesOpen, int ticketsRead, Optional<String> issuesSyncedAt)
    {
        CandidateCoverage
        {
            checkArgument(issuesRead >= 0, "issuesRead is negative");
            checkArgument(issuesOpen >= 0, "issuesOpen is negative");
            checkArgument(ticketsRead >= 0, "ticketsRead is negative");
            requireNonNull(issuesSyncedAt, "issuesSyncedAt is null");
        }
    }

    record Gap(String title, String module, String work)
    {
        Gap
        {
            requireNonNull(title, "title is null");
            requireNonNull(module, "module is null");
            requireNonNull(work, "work is null");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record Module(String directory, int sections, Optional<String> page)
    {
        Module
        {
            requireNonNull(directory, "directory is null");
            checkArgument(sections >= 0, "sections is negative");
            requireNonNull(page, "page is null");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record Work(String id, String title, WorkItem.Kind kind, WorkItem.State state, WorkItem.Priority priority, Optional<List<String>> waitsOn, List<String> links,
            Optional<String> summary)
    {
        Work
        {
            requireNonNull(id, "id is null");
            requireNonNull(title, "title is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(state, "state is null");
            requireNonNull(priority, "priority is null");
            requireNonNull(waitsOn, "waitsOn is null");
            waitsOn = waitsOn.map(ImmutableList::copyOf);
            links = ImmutableList.copyOf(links);
            requireNonNull(summary, "summary is null");
        }
    }

    record Topic(String topic, GoalTopic.Level level, boolean setByUser)
    {
        Topic
        {
            requireNonNull(topic, "topic is null");
            requireNonNull(level, "level is null");
        }
    }

    record Question(String id, String text, String where)
    {
        Question
        {
            requireNonNull(id, "id is null");
            requireNonNull(text, "text is null");
            requireNonNull(where, "where is null");
        }
    }

    record Card(GoalRecommendation.Kind kind, String title, GoalRecommendation.State state)
    {
        Card
        {
            requireNonNull(kind, "kind is null");
            requireNonNull(title, "title is null");
            requireNonNull(state, "state is null");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record Knowledge(String topic, Optional<Boolean> stale, Optional<String> body)
    {
        Knowledge
        {
            requireNonNull(topic, "topic is null");
            requireNonNull(stale, "stale is null");
            requireNonNull(body, "body is null");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record Conversation(@JsonUnwrapped Facts facts, Optional<Integer> omittedEarlierExchanges, List<Exchange> earlierExchanges, Optional<OpenLesson> openLesson,
            Optional<String> messageId, Optional<String> event, Optional<String> message)
    {
        Conversation
        {
            requireNonNull(facts, "facts is null");
            requireNonNull(omittedEarlierExchanges, "omittedEarlierExchanges is null");
            earlierExchanges = ImmutableList.copyOf(earlierExchanges);
            requireNonNull(openLesson, "openLesson is null");
            requireNonNull(messageId, "messageId is null");
            requireNonNull(event, "event is null");
            requireNonNull(message, "message is null");
            checkArgument(event.isPresent() != message.isPresent(), "a conversation carries one message or event");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record Exchange(Optional<String> id, Optional<String> event, Optional<String> user, Optional<String> aboutLesson, AgentAnswer agent)
    {
        Exchange
        {
            requireNonNull(id, "id is null");
            requireNonNull(event, "event is null");
            requireNonNull(user, "user is null");
            requireNonNull(aboutLesson, "aboutLesson is null");
            requireNonNull(agent, "agent is null");
            checkArgument(event.isPresent() != user.isPresent(), "an exchange carries one message or event");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record OpenLesson(String title, String outcome, Optional<String> explanation, Optional<String> exercise, Optional<String> check)
    {
        OpenLesson
        {
            requireNonNull(title, "title is null");
            requireNonNull(outcome, "outcome is null");
            requireNonNull(explanation, "explanation is null");
            requireNonNull(exercise, "exercise is null");
            requireNonNull(check, "check is null");
        }
    }

    record Answered(@JsonValue GoalExchange.Reply value) implements AgentAnswer
    {
        Answered
        {
            requireNonNull(value, "value is null");
        }
    }

    sealed interface AgentAnswer permits Answered, Unanswered {}

    enum Unanswered implements AgentAnswer
    {
        INSTANCE;

        @JsonValue
        public String text()
        {
            return "No answer";
        }
    }
}
