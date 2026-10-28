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
package com.bytequay.app.agenda;

import com.bytequay.core.domain.Workspace;

import java.util.Locale;
import java.util.Optional;

/**
 * The short names the agenda's reading tool gives the things it lists, which its reply must repeat: {@code pr:owner/name#12},
 * {@code issue:owner/name#3}, {@code jira:cloud:issue}, {@code task:id}, {@code review:id}, {@code work:id} and {@code epic:id}.
 */
public final class AgendaRefs
{
    private AgendaRefs() {}

    // Repositories are lower case, as work items key them, so one pull request has one ref whichever page names it.
    public static String pull(String repo, int number)
    {
        return "pr:" + repo.toLowerCase(Locale.ROOT) + "#" + number;
    }

    public static String issue(String repo, int number)
    {
        return "issue:" + repo.toLowerCase(Locale.ROOT) + "#" + number;
    }

    public static String issue(Workspace workspace, String repo, int number)
    {
        return workspace.isGitHub() ? issue(repo, number) : "issue:" + workspace.id() + ":" + repo.toLowerCase(Locale.ROOT) + "#" + number;
    }

    public static String jira(String cloudId, String issueId)
    {
        return "jira:" + cloudId + ":" + issueId;
    }

    public static String task(String taskId)
    {
        return "task:" + taskId;
    }

    public static String review(String reviewId)
    {
        return "review:" + reviewId;
    }

    public static String work(String itemId)
    {
        return "work:" + itemId;
    }

    public static String epic(String epicId)
    {
        return "epic:" + epicId;
    }

    /** What kind of thing a ref names: its prefix. */
    public static String kind(String ref)
    {
        int colon = ref.indexOf(':');
        return colon < 0 ? "" : ref.substring(0, colon);
    }

    /** The task, review, work item or Epic id after the prefix. */
    public static String id(String ref)
    {
        return ref.substring(ref.indexOf(':') + 1);
    }

    /** The repository and number of a {@code pr:} or {@code issue:} ref. */
    public static Optional<Numbered> numbered(String ref)
    {
        String rest = id(ref);
        int hash = rest.lastIndexOf('#');
        if (hash <= 0) {
            return Optional.empty();
        }
        try {
            String repository = rest.substring(0, hash);
            int scope = repository.indexOf(':');
            return Optional.of(new Numbered(scope < 0 ? repository : repository.substring(scope + 1), Integer.parseInt(rest.substring(hash + 1)),
                    scope < 0 ? Optional.empty() : Optional.of(repository.substring(0, scope))));
        }
        catch (NumberFormatException notNumbered) {
            return Optional.empty();
        }
    }

    public record Numbered(String repo, int number, Optional<String> workspaceId)
    {
        public Numbered(String repo, int number)
        {
            this(repo, number, Optional.empty());
        }
    }
}
