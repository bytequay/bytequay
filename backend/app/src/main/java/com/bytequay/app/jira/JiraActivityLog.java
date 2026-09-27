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
package com.bytequay.app.jira;

import com.bytequay.core.domain.PrActivitySummary;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static com.bytequay.app.jira.JiraConnections.segment;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

/**
 * What last changed on each cached ticket, found by comparing every refreshed ticket with the one it
 * replaces. A refresh that moved a ticket's update time without changing anything ByteQuay saves is
 * most likely a comment, which the scheduled refresh then reads.
 */
@Component
public class JiraActivityLog
{
    /** Fields a change is reported for, in words. The description is left out: board and ticket reads format it differently. */
    private static final Map<String, Function<JiraIssue, Object>> FIELDS = ImmutableMap.of(
            "title", JiraIssue::title,
            "assignee", issue -> issue.assignee().map(JiraDtos.Person::accountId),
            "priority", issue -> issue.priority().map(JiraDtos.Choice::id),
            "labels", JiraIssue::labels,
            "due date", JiraIssue::dueDate,
            "type", JiraIssue::type,
            "parent", issue -> issue.parent().map(JiraDtos.Parent::key));

    private final JiraCache cache;
    // Keyed by cloud and issue id: when each unexplained ticket last changed before its refresh.
    private final Map<String, Instant> unexplained = new ConcurrentHashMap<>();

    public JiraActivityLog(JiraCache cache)
    {
        this.cache = requireNonNull(cache, "cache is null");
    }

    /** Compares a refreshed ticket with the cached one it replaces, keeping what changed; comments count only when both carry theirs. */
    public void observe(Optional<JiraIssue> previous, JiraIssue saved, boolean withComments)
    {
        if (previous.isEmpty() || !saved.updatedAt().isAfter(previous.get().updatedAt())) {
            return;
        }
        JiraIssue before = previous.get();
        Optional<JiraDtos.Comment> comment = withComments ? newest(saved.comments(), before.comments()) : Optional.empty();
        List<String> fields = FIELDS.entrySet().stream()
                .filter(field -> !Objects.equals(field.getValue().apply(before), field.getValue().apply(saved)))
                .map(Map.Entry::getKey).collect(ImmutableList.toImmutableList());
        if (!before.status().equals(saved.status())) {
            record(saved, new JiraActivity(JiraActivity.Kind.STATUS_CHANGED, saved.updatedAt(), Optional.empty(), Optional.of(before.status()),
                    Optional.of(saved.status()), comment.flatMap(value -> PrActivitySummary.excerpt(value.body())), fields));
        }
        else if (comment.isPresent()) {
            record(saved, commented(comment.get()));
        }
        else if (!fields.isEmpty()) {
            record(saved, new JiraActivity(JiraActivity.Kind.FIELDS_CHANGED, saved.updatedAt(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), fields));
        }
        else if (!withComments) {
            unexplained.put(key(saved), before.updatedAt());
        }
    }

    public Optional<JiraActivity> latest(JiraIssue issue)
    {
        return cache.read(activityKey(issue), JiraActivity.class);
    }

    /** The tickets whose refresh explained nothing, each with when it last changed before; asking clears them. */
    public Map<String, Instant> takeUnexplained()
    {
        Map<String, Instant> taken = ImmutableMap.copyOf(unexplained);
        taken.keySet().forEach(unexplained::remove);
        return taken;
    }

    /** Keeps a comment read for an unexplained ticket when it arrived after the ticket last changed. */
    public void commented(JiraIssue issue, JiraDtos.Comment comment, Instant since)
    {
        if (JiraMapping.instant(comment.created()).isAfter(since)) {
            record(issue, commented(comment));
        }
    }

    public static String key(JiraIssue issue)
    {
        return issue.cloudId() + ":" + issue.id();
    }

    private void record(JiraIssue issue, JiraActivity activity)
    {
        cache.write(activityKey(issue), activity);
    }

    private static JiraActivity commented(JiraDtos.Comment comment)
    {
        return new JiraActivity(JiraActivity.Kind.COMMENTED, JiraMapping.instant(comment.created()), Optional.of(comment.author()), Optional.empty(),
                Optional.empty(), PrActivitySummary.excerpt(comment.body()), ImmutableList.of());
    }

    private static Optional<JiraDtos.Comment> newest(List<JiraDtos.Comment> comments, List<JiraDtos.Comment> known)
    {
        Set<String> seen = known.stream().map(JiraDtos.Comment::id).collect(toImmutableSet());
        return comments.stream().filter(comment -> !seen.contains(comment.id())).max(Comparator.comparing(comment -> JiraMapping.instant(comment.created())));
    }

    private static String activityKey(JiraIssue issue)
    {
        return "activity." + segment(issue.cloudId()) + "." + segment(issue.id());
    }
}
