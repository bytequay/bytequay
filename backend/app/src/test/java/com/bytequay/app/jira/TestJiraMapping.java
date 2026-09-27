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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestJiraMapping
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesAvatarsForAssigneesAndCommentAuthors()
            throws Exception
    {
        String avatar = "https://avatar-management--avatars.us-west-2.prod.public.atl-paas.net/account/photo/48";
        ObjectNode person = mapper.createObjectNode().put("accountId", "account").put("displayName", "Alex Chen");
        ObjectNode urls = person.putObject("avatarUrls").put("48x48", avatar).put("24x24", "https://example.atlassian.net/avatar/24");
        ObjectNode issue = ticket("indeterminate");
        ((ObjectNode) issue.path("fields")).set("assignee", person);
        ObjectNode comment = mapper.createObjectNode();
        comment.set("author", person);

        assertThat(JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", issue).assignee().orElseThrow().avatarUrl()).contains(avatar);
        assertThat(JiraMapping.comment(comment).author().avatarUrl()).contains(avatar);
        urls.remove("48x48");
        assertThat(JiraMapping.person(person).avatarUrl()).contains("https://example.atlassian.net/avatar/24");
        urls.removeAll();
        assertThat(JiraMapping.person(person).avatarUrl()).isEmpty();
        person.remove("avatarUrls");
        assertThat(JiraMapping.person(person).avatarUrl()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not a URL", "javascript:alert(1)", "file:///tmp/avatar.png", "data:image/png;base64,abc", "/avatar/48",
            "http://example.atlassian.net/avatar", "https://127.0.0.1/avatar", "https://example.com/avatar", "https://evilatl-paas.net/avatar",
            "https://example.atlassian.net.evil.test/avatar", "https://user:password@example.atlassian.net/avatar", "https://example.atlassian.net:8443/avatar"})
    void skipsUnsafeAvatarsWithoutDroppingThePerson(String url)
    {
        ObjectNode person = mapper.createObjectNode().put("accountId", "account").put("displayName", "Alex Chen");
        ObjectNode urls = person.putObject("avatarUrls").put("48x48", url);
        assertThat(JiraMapping.person(person)).isEqualTo(new JiraDtos.Person("account", "Alex Chen", Optional.empty()));
        urls.put("32x32", "https://secure.gravatar.com/avatar/hash?s=32");
        assertThat(JiraMapping.person(person).avatarUrl()).contains("https://secure.gravatar.com/avatar/hash?s=32");
    }

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
