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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestJiraMapping
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void completionUsesLatestDoneCategoryEntryBeforeAnOlderResolution()
            throws Exception
    {
        ObjectNode value = ticket("done");
        ((ObjectNode) value.path("fields")).put("resolutiondate", "2026-09-21T02:00:00.000+0000")
                .put("statuscategorychangedate", "2026-09-26T01:00:00Z");
        JiraIssue issue = JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", value);
        assertThat(issue.completedAt()).contains(Instant.parse("2026-09-26T01:00:00Z"));
        assertThat(issue.withComments(ImmutableList.of(), true).withChildren(true).withMetadata(true, Optional.empty(), Optional.empty()).completedAt())
                .isEqualTo(issue.completedAt());
    }

    @Test
    void fallsBackToResolutionButNeverOrdinaryUpdatedTime()
            throws Exception
    {
        ObjectNode value = ticket("done");
        ((ObjectNode) value.path("fields")).put("resolutiondate", "2026-09-21T02:00:00.000+0000");
        assertThat(JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", value).completedAt())
                .contains(Instant.parse("2026-09-21T02:00:00Z"));
        ((ObjectNode) value.path("fields")).putNull("resolutiondate");
        assertThat(JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", value).completedAt()).isEmpty();
    }

    @Test
    void reopenedTicketHasNoCompletionDateEvenIfJiraRetainsResolution()
            throws Exception
    {
        ObjectNode value = ticket("indeterminate");
        ((ObjectNode) value.path("fields")).put("resolutiondate", "2026-09-21T02:00:00Z").put("statuscategorychangedate", "2026-09-26T01:00:00Z");
        assertThat(JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", value).completedAt()).isEmpty();
    }

    private ObjectNode ticket(String category)
            throws Exception
    {
        ObjectNode value = (ObjectNode) mapper.readTree("""
                {"id":"1001","key":"ENG-7","fields":{"summary":"A ticket","description":{"type":"doc","content":[]},
                "updated":"2026-09-26T02:00:00.000+0000","issuetype":{"name":"Story"},"subtasks":[],"labels":[],
                "status":{"id":"1","name":"Status","statusCategory":{"key":"done"}}}}
                """);
        ((ObjectNode) value.path("fields").path("status").path("statusCategory")).put("key", category);
        return value;
    }
}
