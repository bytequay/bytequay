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
package com.bytequay.integration.jira.impl;

import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.integration.jira.JiraActivity;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestJiraActivityLog
{
    private static final Instant BEFORE = Instant.parse("2026-09-27T08:00:00Z");
    private static final Instant AFTER = Instant.parse("2026-09-27T10:00:00Z");
    private static final JiraDtos.Status IN_PROGRESS = new JiraDtos.Status("3", "In Progress", "indeterminate");
    private static final JiraDtos.Status BLOCKED = new JiraDtos.Status("6", "Blocked", "indeterminate");
    private static final JiraDtos.Person PRIYA = new JiraDtos.Person("priya", "Priya Raman", Optional.empty());

    private final AppSettingsStore settings = mock(AppSettingsStore.class);
    private final Map<String, String> saved = new HashMap<>();
    private final JiraActivityLog log = new JiraActivityLog(new JiraCache(settings, new ObjectMapper().findAndRegisterModules()));

    @BeforeEach
    void setUp()
    {
        when(settings.get(anyString())).thenAnswer(call -> Optional.ofNullable(saved.get(call.<String>getArgument(0))));
        doAnswer(call -> {
            saved.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(settings).put(anyString(), anyString());
    }

    @Test
    void aStatusChangeKeepsWhereItMovedAndAnyCommentThatCameWithIt()
    {
        JiraIssue before = ticket(IN_PROGRESS, BEFORE, Optional.empty(), ImmutableList.of());
        JiraIssue after = ticket(BLOCKED, AFTER, Optional.empty(), ImmutableList.of(comment("c1", "Staging is mid-upgrade. Blocked by INFRA-2291 until Thursday.")));
        log.observe(Optional.of(before), after, true);

        JiraActivity activity = log.latest(after).orElseThrow();
        assertThat(activity.kind()).isEqualTo(JiraActivity.Kind.STATUS_CHANGED);
        assertThat(activity.from()).contains(IN_PROGRESS);
        assertThat(activity.to()).contains(BLOCKED);
        assertThat(activity.note()).contains("Staging is mid-upgrade. Blocked by INFRA-2291 until Thursday.");
        assertThat(activity.actor()).isEmpty();
        assertThat(activity.at()).isEqualTo(AFTER);
    }

    @Test
    void aFieldChangeNamesTheFieldsAndADetailRefreshNamesTheNewComment()
    {
        JiraIssue before = ticket(IN_PROGRESS, BEFORE, Optional.empty(), ImmutableList.of(comment("c1", "Old")));
        log.observe(Optional.of(before), ticket(IN_PROGRESS, AFTER, Optional.of("2026-10-01"), ImmutableList.of(comment("c1", "Old"))), false);
        JiraActivity fields = log.latest(before).orElseThrow();
        assertThat(fields.kind()).isEqualTo(JiraActivity.Kind.FIELDS_CHANGED);
        assertThat(fields.fields()).containsExactly("due date");

        List<JiraDtos.Comment> both = ImmutableList.of(comment("c1", "Old"), comment("c2", "Can we keep the check?"));
        JiraIssue commented = ticket(IN_PROGRESS, AFTER.plusSeconds(60), Optional.of("2026-10-01"), both);
        log.observe(Optional.of(ticket(IN_PROGRESS, AFTER, Optional.of("2026-10-01"), ImmutableList.of(comment("c1", "Old")))), commented, true);
        JiraActivity comment = log.latest(commented).orElseThrow();
        assertThat(comment.kind()).isEqualTo(JiraActivity.Kind.COMMENTED);
        assertThat(comment.actor()).contains(PRIYA);
        assertThat(comment.note()).contains("Can we keep the check?");
    }

    @Test
    void anUpdateTheSavedFieldsDoNotExplainWaitsForItsNewestCommentToBeRead()
    {
        JiraIssue before = ticket(IN_PROGRESS, BEFORE, Optional.empty(), ImmutableList.of());
        JiraIssue after = ticket(IN_PROGRESS, AFTER, Optional.empty(), ImmutableList.of());
        log.observe(Optional.of(before), after, false);
        assertThat(log.latest(after)).isEmpty();

        Map<String, Instant> unexplained = log.takeUnexplained();
        assertThat(unexplained).containsExactly(Map.entry("cloud:10412", BEFORE));
        assertThat(log.takeUnexplained()).isEmpty();

        // A comment older than the last known change is not news.
        log.commented(after, comment("c0", "Earlier", "2026-09-27T07:00:00.000+0000"), BEFORE);
        assertThat(log.latest(after)).isEmpty();
        log.commented(after, comment("c3", "New", "2026-09-27T09:00:00.000+0000"), BEFORE);
        assertThat(log.latest(after).map(JiraActivity::kind)).contains(JiraActivity.Kind.COMMENTED);
    }

    @Test
    void aFirstSightOrAnOlderReadRecordsNothing()
    {
        JiraIssue ticket = ticket(IN_PROGRESS, AFTER, Optional.empty(), ImmutableList.of());
        log.observe(Optional.empty(), ticket, false);
        log.observe(Optional.of(ticket(BLOCKED, AFTER, Optional.empty(), ImmutableList.of())), ticket, false);
        assertThat(log.latest(ticket)).isEmpty();
        assertThat(log.takeUnexplained()).isEmpty();
    }

    private static JiraDtos.Comment comment(String id, String body)
    {
        return comment(id, body, "2026-09-27T09:30:00.000+0000");
    }

    private static JiraDtos.Comment comment(String id, String body, String created)
    {
        return new JiraDtos.Comment(id, Optional.empty(), PRIYA, body, created, created, false, Optional.empty());
    }

    private static JiraIssue ticket(JiraDtos.Status status, Instant updatedAt, Optional<String> dueDate, List<JiraDtos.Comment> comments)
    {
        return new JiraIssue("connection", "cloud", "10412", "TRINO-412", "https://example.atlassian.net/browse/TRINO-412", "Validate the fix", "", "Task", status,
                Optional.empty(), ImmutableList.of(), Optional.empty(), Optional.empty(), false, false, updatedAt, dueDate, Optional.empty(), comments, false,
                Optional.empty(), Optional.empty());
    }
}
