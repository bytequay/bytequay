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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class TestJiraMapping
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesRichDescriptionsAndComments()
            throws Exception
    {
        try (var document = getClass().getResourceAsStream("/jira/rich-description.json");
                var expected = getClass().getResourceAsStream("/jira/rich-description.md")) {
            var body = mapper.readTree(document);
            String markdown = new String(expected.readAllBytes(), UTF_8).strip();
            ObjectNode issue = ticket("done");
            ((ObjectNode) issue.path("fields")).set("description", body);
            ObjectNode comment = mapper.createObjectNode();
            comment.set("body", body);

            assertThat(JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", issue).body()).isEqualTo(markdown);
            assertThat(JiraMapping.comment("https://example.atlassian.net", Optional.empty(), comment).body()).isEqualTo(markdown);
        }
    }

    @Test
    void preservesCodeDelimitersAndLiteralTablePipes()
            throws Exception
    {
        assertThat(JiraMapping.markdown("https://example.atlassian.net", mapper.readTree("""
                {"type":"doc","content":[
                  {"type":"paragraph","content":[{"type":"text","text":"`a_b`","marks":[{"type":"code"}]}]},
                  {"type":"paragraph","content":[{"type":"text","text":" a_b ","marks":[{"type":"code"}]}]},
                  {"type":"codeBlock","attrs":{"language":"markdown"},"content":[{"type":"text","text":"```java\\nx();\\n```"}]},
                  {"type":"table","content":[
                    {"type":"tableRow","content":[{"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"Value"}]}]}]},
                    {"type":"tableRow","content":[{"type":"tableCell","content":[{"type":"paragraph","content":[
                      {"type":"text","text":"a|b","marks":[{"type":"code"}]}, {"type":"hardBreak"}, {"type":"text","text":"or c|d"}]}]}]}
                  ]}
                ]}
                """)).strip()).isEqualTo("""
                `` `a_b` ``

                `  a_b  `

                ````markdown
                ```java
                x();
                ```
                ````

                | Value |
                | --- |
                | `a\\|b` or c\\|d |
                """.strip());
    }

    @Test
    void editsOnlyPlainOwnCommentsAndRestoresTheirMentions()
            throws Exception
    {
        ObjectNode comment = (ObjectNode) mapper.readTree("""
                {"id":"5","author":{"accountId":"me"},"body":{"type":"doc","version":1,"content":[
                  {"type":"paragraph","content":[{"type":"mention","attrs":{"id":"alex","text":"@Alex Kim"}},{"type":"text","text":" please check"}]},
                  {"type":"paragraph","content":[]},
                  {"type":"paragraph","content":[{"type":"text","text":"Thanks"}]}
                ]}}
                """);

        JiraDtos.Comment mine = JiraMapping.comment("https://example.atlassian.net", Optional.of("me"), comment);
        assertThat(mine.mine()).isTrue();
        assertThat(mine.editableText()).contains("@Alex Kim please check\n\nThanks");
        JsonNode saved = mapper.valueToTree(JiraMapping.document(mine.editableText().orElseThrow(), JiraMapping.mentions(comment)));
        assertThat(saved).isEqualTo(comment.path("body"));
        assertThat(JiraMapping.comment("https://example.atlassian.net", Optional.of("someone else"), comment).editableText()).isEmpty();

        ((ObjectNode) comment.at("/body/content/2")).putArray("content").add(mapper.readTree("{\"type\":\"mention\",\"attrs\":{\"id\":\"other\",\"text\":\"@Alex Kim\"}}"));
        assertThat(JiraMapping.editableText(comment)).isEmpty();
        ((ObjectNode) comment.at("/body/content/2")).putArray("content").addObject().put("type", "text").put("text", "Thanks").putArray("marks").addObject().put("type", "strong");
        assertThat(JiraMapping.editableText(comment)).isEmpty();
        ((ObjectNode) comment.at("/body/content/2")).putArray("content");
        comment.putObject("visibility").put("type", "role").put("value", "Developers");
        assertThat(JiraMapping.editableText(comment)).isEmpty();
    }

    @Test
    void keepsWhitespaceOutsideEmphasisMarkers()
            throws Exception
    {
        assertThat(JiraMapping.markdown("https://example.atlassian.net", mapper.readTree("""
                {"type":"paragraph","content":[
                  {"type":"text","text":"updated the PR: ","marks":[{"type":"em"}]},
                  {"type":"inlineCard","attrs":{"url":"https://github.com/trinodb/trino/pull/30205"}},
                  {"type":"text","text":" ","marks":[{"type":"em"}]},
                  {"type":"text","text":"\\u00a0really ","marks":[{"type":"strong"},{"type":"em"}]},
                  {"type":"text","text":"done"}
                ]}
                """)).strip()).isEqualTo("_updated the PR:_ [https://github\\.com/trinodb/trino/pull/30205](https://github.com/trinodb/trino/pull/30205) \u00a0_**really**_ done");
    }

    @Test
    void preservesQuotesListsAndEmphasisWithoutInterpretingLiteralMarkup()
            throws Exception
    {
        assertThat(JiraMapping.markdown("https://example.atlassian.net", mapper.readTree("""
                {"type":"doc","content":[
                  {"type":"blockquote","content":[{"type":"paragraph","content":[{"type":"text","text":"Quoted"}]}]},
                  {"type":"orderedList","attrs":{"order":3},"content":[
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"First","marks":[{"type":"em"}]}]}]},
                    {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Next","marks":[
                      {"type":"link","attrs":{"href":"https://example.com/a(b)"}},{"type":"strong"}]}]}]}
                  ]},
                  {"type":"paragraph","content":[{"type":"text","text":"# **literal** [label](javascript:alert(1)) <details> &lt;tag&gt;"}]}
                ]}
                """)).strip()).isEqualTo("""
                > Quoted

                3. _First_
                4. [**Next**](https://example.com/a%28b%29)

                \\# \\*\\*literal\\*\\* \\[label\\]\\(javascript:alert\\(1\\)\\) &lt;details&gt; &amp;lt;tag&amp;gt;
                """.strip());
    }

    @Test
    void keepsCardDestinationsUnescapedAndRejectsUnsafeLinks()
            throws Exception
    {
        assertThat(JiraMapping.markdown("https://example.atlassian.net", mapper.readTree("""
                {"type":"doc","content":[
                  {"type":"paragraph","content":[{"type":"inlineCard","attrs":{"url":"https://example.atlassian.net/browse/ENG-7"}}]},
                  {"type":"paragraph","content":[{"type":"text","text":"Unsafe","marks":[
                    {"type":"code"},{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}]}
                ]}
                """)).strip()).isEqualTo("""
                [https://example\\.atlassian\\.net/browse/ENG\\-7](https://example.atlassian.net/browse/ENG-7)

                `Unsafe`
                """.strip());
    }

    @Test
    void preservesFullMentionNamesAndSiteProfilesInDescriptionsAndComments()
            throws Exception
    {
        var body = mapper.readTree("""
                {"type":"doc","content":[
                  {"type":"heading","attrs":{"level":2},"content":[
                    {"type":"mention","attrs":{"id":"712020:jack/chen ?#%","text":"Jack Chen"}}]},
                  {"type":"blockquote","content":[{"type":"bulletList","content":[{"type":"listItem","content":[
                    {"type":"paragraph","content":[{"type":"mention","attrs":{"id":"chen xiao","text":"@陈 晓"}}]}]}]}]},
                  {"type":"table","content":[
                    {"type":"tableRow","content":[{"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"Person"}]}]}]},
                    {"type":"tableRow","content":[{"type":"tableCell","content":[{"type":"paragraph","content":[
                      {"type":"mention","attrs":{"id":"special","text":"@A [B] (C)_D *E* & <F>"}}]}]}]}
                  ]},
                  {"type":"paragraph","content":[
                    {"type":"mention","attrs":{"text":"Guest Reader"}},{"type":"text","text":" / "},
                    {"type":"mention","attrs":{"id":" ","text":" "}},{"type":"text","text":" / "},
                    {"type":"mention","attrs":{"id":"unknown"}}
                  ]}
                ]}
                """);
        String expected = """
                ## [@Jack Chen](https://example.atlassian.net/jira/people/712020%3Ajack%2Fchen%20%3F%23%25)

                > - [@陈 晓](https://example.atlassian.net/jira/people/chen%20xiao)

                | Person |
                | --- |
                | [@A \\[B\\] \\(C\\)\\_D \\*E\\* &amp; &lt;F&gt;](https://example.atlassian.net/jira/people/special) |

                @Guest Reader / @user / [@user](https://example.atlassian.net/jira/people/unknown)
                """.strip();
        ObjectNode issue = ticket("done");
        ((ObjectNode) issue.path("fields")).set("description", body);
        ObjectNode comment = mapper.createObjectNode();
        comment.set("body", body);

        assertThat(JiraMapping.issue("connection", "cloud", "https://example.atlassian.net", issue).body()).isEqualTo(expected);
        assertThat(JiraMapping.comment("https://example.atlassian.net", Optional.empty(), comment).body()).isEqualTo(expected);
    }

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
        assertThat(JiraMapping.comment("https://example.atlassian.net", Optional.empty(), comment).author().avatarUrl()).contains(avatar);
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
