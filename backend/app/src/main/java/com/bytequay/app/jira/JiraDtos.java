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

import static com.google.common.base.Preconditions.checkArgument;

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

    public record Board(long id, String name, String type) {}

    public record Sprint(long id, String name, String state, Optional<String> startDate, Optional<String> endDate) {}

    public record Person(String accountId, String displayName, Optional<String> avatarUrl) {}

    public record Status(String id, String name, String category) {}

    public record Choice(String id, String name) {}

    public record Parent(String id, String key, String title) {}

    public record TaskLink(String id, String workspaceId) {}

    public record WorkspaceLink(String id, String name) {}

    public record Comment(String id, Person author, String body, String created, String updated) {}

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

    public record Detail(JiraIssue issue, List<Comment> comments, List<Transition> transitions, List<Field> editableFields,
            List<Person> assignees, Optional<Instant> fetchedAt, Optional<String> error, boolean commentsTruncated)
    {
        public Detail
        {
            comments = ImmutableList.copyOf(comments);
            transitions = ImmutableList.copyOf(transitions);
            editableFields = ImmutableList.copyOf(editableFields);
            assignees = ImmutableList.copyOf(assignees);
        }
    }

    public record IssuePage(List<JiraIssue> issues, Optional<Instant> fetchedAt, Optional<String> error, boolean partial)
    {
        public IssuePage
        {
            issues = ImmutableList.copyOf(issues);
        }
    }
}
