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

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Native Jira HTTP projections, containing no authentication secrets. */
public final class JiraDtos
{
    private JiraDtos() {}

    public record Site(String id, String name, String url) {}

    public record Connection(String id, String state, List<Site> sites,
            Optional<String> authorizationUrl, Optional<String> pairingCode, Optional<String> error, Optional<Account> account, Optional<String> duplicateOf,
            String authType, Optional<String> tokenEmail)
    {
        public Connection
        {
            sites = ImmutableList.copyOf(sites);
            // Existing saved OAuth connections predate the explicit authentication type.
            authType = authType == null ? "oauth" : authType;
            checkArgument(authType.equals("oauth") || authType.equals("api_token"), "Invalid Jira authentication type");
            tokenEmail = tokenEmail == null ? Optional.empty() : tokenEmail;
        }
    }

    public record Account(String accountId, String displayName, Optional<String> email) {}

    public record Board(long id, String name, String type)
    {
        public Board
        {
            checkArgument(id > 0, "board id must be positive");
            checkText(name, "name");
            checkText(type, "type");
        }
    }

    public record Sprint(long id, String name, String state, Optional<String> startDate, Optional<String> endDate)
    {
        public Sprint
        {
            checkArgument(id > 0, "sprint id must be positive");
            checkText(name, "name");
            checkText(state, "state");
            requireNonNull(startDate, "startDate is null");
            requireNonNull(endDate, "endDate is null");
            startDate.ifPresent(value -> checkText(value, "startDate"));
            endDate.ifPresent(value -> checkText(value, "endDate"));
        }
    }

    public record Person(String accountId, String displayName, Optional<String> avatarUrl) {}

    public record Status(String id, String name, String category) {}

    public record Choice(String id, String name) {}

    public record Parent(String id, String key, String title, Optional<String> type) {}

    public record TaskLink(String id, String workspaceId) {}

    public record WorkspaceLink(String id, String name) {}

    public record Comment(String id, Optional<String> parentId, Person author, String body, String created, String updated, boolean mine, Optional<String> editableText) {}

    public record Field(String id, String name, boolean required, String schemaType, List<Choice> allowedValues)
    {
        public Field
        {
            allowedValues = ImmutableList.copyOf(allowedValues);
        }
    }

    public record Transition(String id, String name, Status to, List<Field> fields)
    {
        public Transition
        {
            fields = ImmutableList.copyOf(fields);
        }
    }

    /** Another ticket as a subtask, child or linked work item row shows it. */
    public record RelatedIssue(String id, String key, String title, String type, Status status, Optional<Choice> priority, Optional<Person> assignee) {}

    /** A link as the ticket holding it reads it, for example "is blocked by" and the blocking ticket. */
    public record IssueLink(String id, String relation, RelatedIssue issue) {}

    public record LinkType(String id, String name, String inward, String outward) {}

    /** A site-defined multi-line text field, such as "Additional Context", rendered as Markdown. */
    public record RichTextField(String id, String name, String body) {}

    /** A site-defined user or multi-user field, such as "Co-Assignee(s)". */
    public record PeopleField(String id, String name, List<Person> people)
    {
        public PeopleField
        {
            people = ImmutableList.copyOf(people);
        }
    }

    /** An Atlassian team field and the team it names. */
    public record TeamField(String id, String name, String team) {}

    /** {@code hierarchyLevel} is Jira's: subtasks are -1, standard tickets 0 and epics 1. */
    public record Detail(JiraIssue issue, List<Comment> comments, List<Transition> transitions, List<Field> editableFields,
            List<Person> assignees, Optional<Instant> fetchedAt, Optional<String> error, boolean commentsTruncated,
            int hierarchyLevel, List<RelatedIssue> children, boolean childrenTruncated, List<IssueLink> links, List<RichTextField> richTextFields,
            Optional<String> project, Optional<Person> reporter, List<PeopleField> peopleFields, List<TeamField> teamFields)
    {
        public Detail
        {
            comments = ImmutableList.copyOf(comments);
            transitions = ImmutableList.copyOf(transitions);
            editableFields = ImmutableList.copyOf(editableFields);
            assignees = ImmutableList.copyOf(assignees);
            // A detail cached before children and links were read has neither until its next refresh.
            children = children == null ? ImmutableList.of() : ImmutableList.copyOf(children);
            links = links == null ? ImmutableList.of() : ImmutableList.copyOf(links);
            richTextFields = richTextFields == null ? ImmutableList.of() : ImmutableList.copyOf(richTextFields);
            peopleFields = peopleFields == null ? ImmutableList.of() : ImmutableList.copyOf(peopleFields);
            teamFields = teamFields == null ? ImmutableList.of() : ImmutableList.copyOf(teamFields);
        }
    }

    public record IssuePage(List<JiraIssue> issues, Optional<Instant> fetchedAt, Optional<String> error, Optional<String> nextPageToken)
    {
        public IssuePage
        {
            issues = ImmutableList.copyOf(issues);
        }
    }
}
