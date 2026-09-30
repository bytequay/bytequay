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
package com.bytequay.app.context;

import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.RegisteredContextSource;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * The user-accepted learning topics and questions at one recorded revision, independent of the latest task's lifecycle.
 * The accepted request fields describe the latest acceptance; a later acceptance from the same report appends its topics.
 */
public record ContextLearningPlan(
        String id, String workspaceId, String reportId, String acceptedRequestKey, String acceptedRequestDigest,
        long acceptedNotebookRevision, long acceptedBuildSequence, List<String> acceptedTopicIds,
        long repositoryId, RepoRef repository, String baselineSha, List<Topic> topics,
        List<RegisteredContextSource> documentationSources, ContextDocumentInventory documentInventory,
        List<String> gaps, Instant acceptedAt, Optional<Batch> batch)
{
    public static final int MAX_TOPICS = 20;

    public ContextLearningPlan
    {
        ContextLimits.text(id, "planId", 100);
        ContextLimits.text(workspaceId, "workspaceId", 200);
        ContextLimits.text(reportId, "reportId", 100);
        ContextLimits.text(acceptedRequestKey, "accepted request key", 100);
        checkArgument(acceptedRequestDigest.matches("sha256:[0-9a-f]{64}"), "Invalid accepted request digest");
        checkArgument(acceptedNotebookRevision >= 0 && acceptedBuildSequence >= 0, "Invalid accepted revision");
        acceptedTopicIds = ImmutableList.copyOf(acceptedTopicIds);
        ContextLimits.unique(acceptedTopicIds, "accepted topic IDs", MAX_TOPICS);
        checkArgument(!acceptedTopicIds.isEmpty(), "Select at least one learning topic");
        checkArgument(repositoryId > 0, "Invalid learning repository ID");
        requireNonNull(repository, "repository is null");
        checkArgument(baselineSha.matches("[0-9a-f]{40}|[0-9a-f]{64}"), "Invalid learning baseline");
        topics = ImmutableList.copyOf(topics);
        List<String> topicIds = topics.stream().map(Topic::id).toList();
        ContextLimits.unique(topicIds, "plan topics", MAX_TOPICS);
        checkArgument(topicIds.size() >= acceptedTopicIds.size()
                && topicIds.subList(topicIds.size() - acceptedTopicIds.size(), topicIds.size()).equals(acceptedTopicIds),
                "The latest accepted topics must end the plan");
        documentationSources = ImmutableList.copyOf(documentationSources);
        ContextLimits.unique(documentationSources.stream().map(RegisteredContextSource::id).toList(), "plan documentation", 20);
        requireNonNull(documentInventory, "documentInventory is null");
        checkArgument(documentInventory.sources().equals(documentationSources), "Documentation inventory differs from approved source snapshots");
        List<String> approvedSources = documentationSources.stream().map(RegisteredContextSource::id).toList();
        topics.forEach(topic -> checkArgument(approvedSources.containsAll(topic.sourceIds()), "Topic uses an unapproved documentation source"));
        documentInventory.suggestions().forEach(suggestion -> checkArgument(topicIds.contains(suggestion.topicId()), "Suggestion names an unknown topic"));
        gaps = ImmutableList.copyOf(gaps);
        checkArgument(gaps.size() <= 40, "Too many learning gaps");
        gaps.forEach(gap -> ContextLimits.text(gap, "learning gap", 1000));
        requireNonNull(acceptedAt, "acceptedAt is null");
        requireNonNull(batch, "batch is null");
        batch.ifPresent(value -> value.queue().forEach(target -> checkArgument(topicIds.contains(target.topicId()), "Batch names an unknown topic")));
    }

    public ContextLearningPlan withInventory(ContextDocumentInventory updated)
    {
        return new ContextLearningPlan(id, workspaceId, reportId, acceptedRequestKey, acceptedRequestDigest,
                acceptedNotebookRevision, acceptedBuildSequence, acceptedTopicIds, repositoryId, repository, baselineSha,
                topics, documentationSources, updated, gaps, acceptedAt, batch);
    }

    public ContextLearningPlan withProgress(List<Topic> updated, List<String> updatedGaps)
    {
        return new ContextLearningPlan(id, workspaceId, reportId, acceptedRequestKey, acceptedRequestDigest,
                acceptedNotebookRevision, acceptedBuildSequence, acceptedTopicIds, repositoryId, repository, baselineSha,
                updated, documentationSources, documentInventory, updatedGaps, acceptedAt, batch);
    }

    public ContextLearningPlan withSources(List<Topic> updated, ContextDocumentInventory inventory)
    {
        return new ContextLearningPlan(id, workspaceId, reportId, acceptedRequestKey, acceptedRequestDigest,
                acceptedNotebookRevision, acceptedBuildSequence, acceptedTopicIds, repositoryId, repository, baselineSha,
                updated, inventory.sources(), inventory, gaps, acceptedAt, batch);
    }

    public ContextLearningPlan withBatch(Optional<Batch> replacement)
    {
        return new ContextLearningPlan(id, workspaceId, reportId, acceptedRequestKey, acceptedRequestDigest,
                acceptedNotebookRevision, acceptedBuildSequence, acceptedTopicIds, repositoryId, repository, baselineSha,
                topics, documentationSources, documentInventory, gaps, acceptedAt, replacement);
    }

    public Topic topic(String topicId)
    {
        return topics.stream().filter(topic -> topic.id().equals(topicId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Learning topic changed"));
    }

    public Optional<Target> next()
    {
        return open().stream().findFirst();
    }

    /** Questions runs still take, in order: unfinished work first, then every topic's next open question before any topic's following one. */
    public List<Target> open()
    {
        ImmutableList.Builder<Target> open = ImmutableList.builder();
        for (Topic topic : topics) {
            for (int index = 0; index < topic.questions().size(); index++) {
                if (topic.questions().get(index).status() == Status.UNFINISHED) {
                    open.add(new Target(topic.id(), index));
                }
            }
        }
        int depth = topics.stream().mapToInt(topic -> topic.questions().size()).max().orElse(0);
        for (int index = 0; index < depth; index++) {
            for (Topic topic : topics) {
                if (index < topic.questions().size() && topic.questions().get(index).status() == Status.PENDING) {
                    open.add(new Target(topic.id(), index));
                }
            }
        }
        return open.build();
    }

    /**
     * The questions one click authorized: the runs still to start, how many the click authorized, and how many have started.
     * Batch runs use the click's key, then the key with {@code #} and their ordinal.
     */
    public record Batch(String key, List<Target> queue, int total, int started)
    {
        public static final int MAX_RUNS = 30;

        public Batch
        {
            ContextLimits.text(key, "batch key", 90);
            queue = ImmutableList.copyOf(queue);
            checkArgument(total >= 1 && total <= MAX_RUNS && started >= 1 && started <= total && queue.size() <= total - started,
                    "Invalid learning batch");
        }

        public boolean owns(String requestKey)
        {
            return requestKey.equals(key) || requestKey.startsWith(key + "#");
        }

        public String runKey(int ordinal)
        {
            return ordinal == 1 ? key : key + "#" + ordinal;
        }
    }

    /** Open questions are PENDING or UNFINISHED; the others are closed. */
    public enum Status
    {
        PENDING,
        UNFINISHED,
        COMPLETED,
        CORRECTED,
        DROPPED
    }

    public enum Origin
    {
        DISCOVERY,
        LEARNING,
        TASK,
        REVIEW,
        USER
    }

    public record Target(String topicId, int question)
    {
        public Target
        {
            ContextLimits.text(topicId, "topic id", 100);
            checkArgument(question >= 0 && question < Topic.MAX_QUESTIONS, "Invalid learning question index");
        }
    }

    /**
     * One answer unit. Progress is the agent's checkpoint of what was established and what remains; the note says why a
     * question was added, with its lead or cited lines, or why it was corrected or dropped.
     */
    public record Question(String text, Status status, String progress, Origin origin, String note)
    {
        public static final int MAX_NOTE_BYTES = 1000;

        public Question
        {
            ContextLimits.text(text, "learning question", 1000);
            requireNonNull(status, "question status is null");
            checkArgument(ContextLimits.bytes(progress) <= 2000, "Question progress exceeds 2000 UTF-8 bytes");
            checkArgument(status != Status.PENDING || progress.isEmpty(), "A question that has not started has no progress");
            requireNonNull(origin, "question origin is null");
            checkArgument(ContextLimits.bytes(note) <= MAX_NOTE_BYTES, "A question's note exceeds %s UTF-8 bytes", MAX_NOTE_BYTES);
            checkArgument(status != Status.CORRECTED || !note.isBlank(), "A corrected question says why");
        }

        public static Question open(String text)
        {
            return new Question(text, Status.PENDING, "", Origin.DISCOVERY, "");
        }

        public static Question added(String text, Origin origin, String note)
        {
            return new Question(text, Status.PENDING, "", origin, ContextText.excerpt(note, MAX_NOTE_BYTES));
        }

        public boolean open()
        {
            return status == Status.PENDING || status == Status.UNFINISHED;
        }

        public Question withProgress(Status replacement, String checkpoint)
        {
            return new Question(text, replacement, checkpoint, origin, note);
        }

        public Question closed(Status replacement, String checkpoint, String why)
        {
            return new Question(text, replacement, checkpoint, origin, ContextText.excerpt(why, MAX_NOTE_BYTES));
        }

        public Question withText(String replacement)
        {
            return new Question(replacement, status, progress, origin, note);
        }
    }

    public record Topic(String id, String title, String scopeId, String scopeMatchDigest, List<String> paths, Optional<List<String>> notePaths, List<String> repositoryFiles,
            List<String> sourceIds, List<Question> questions)
    {
        public static final int MAX_QUESTIONS = 30;

        public Topic
        {
            ContextLimits.text(id, "topic id", 100);
            ContextLimits.text(title, "topic title", 200);
            ContextLimits.text(scopeId, "topic scope", 1100);
            checkArgument(scopeMatchDigest.matches("sha256:[0-9a-f]{64}"), "Invalid accepted scope match");
            paths = ImmutableList.copyOf(paths);
            notePaths = requireNonNull(notePaths, "notePaths is null").map(ImmutableList::copyOf);
            notePaths.ifPresent(values -> {
                ContextLimits.unique(values, "note paths", 8);
                values.forEach(ContextLimits::pathPrefix);
            });
            repositoryFiles = ImmutableList.copyOf(repositoryFiles);
            sourceIds = ImmutableList.copyOf(sourceIds);
            questions = ImmutableList.copyOf(questions);
            ContextLimits.unique(paths, "topic paths", 8);
            paths.forEach(ContextLimits::pathPrefix);
            ContextLimits.unique(repositoryFiles, "topic source files", 40);
            repositoryFiles.forEach(path -> ContextLimits.pathPrefix(path + "/"));
            ContextLimits.unique(sourceIds, "topic documentation sources", 20);
            checkArgument(!questions.isEmpty() && questions.size() <= MAX_QUESTIONS, "A topic has one to %s learning questions", MAX_QUESTIONS);
        }

        public Topic withQuestion(int index, Question replacement)
        {
            List<Question> updated = new ArrayList<>(questions);
            updated.set(index, replacement);
            return withQuestions(updated);
        }

        public Topic withQuestions(List<Question> replacement)
        {
            return new Topic(id, title, scopeId, scopeMatchDigest, paths, notePaths, repositoryFiles, sourceIds, replacement);
        }

        /** Whether the topic already asks this question, ignoring case and surrounding space. */
        public boolean asks(String text)
        {
            return questions.stream().anyMatch(question -> question.text().strip().equalsIgnoreCase(text.strip()));
        }

        public Topic withSource(String sourceId)
        {
            return sourceIds.contains(sourceId) ? this : new Topic(id, title, scopeId, scopeMatchDigest, paths, notePaths, repositoryFiles,
                    ImmutableList.<String>builder().addAll(sourceIds).add(sourceId).build(), questions);
        }
    }
}
