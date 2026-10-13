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
package com.bytequay.agent.core;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Git-safe initial task branch names. */
public final class TaskBranches
{
    private static final int MAX_SLUG_WORDS = 4;
    private static final int MAX_SLUG_LENGTH = 32;
    private static final String LOGIN = "[a-z0-9](?:[a-z0-9-]{0,37}[a-z0-9])?";
    private static final Pattern BRANCH = Pattern.compile(LOGIN + "/bytequay/[a-z0-9]+(?:-[a-z0-9]+)*");
    // ponytail: English request lead-ins only; semantic summaries would need naming before provisioning.
    private static final Pattern REQUEST_PREFIX = Pattern.compile("^(?:(?:let['’]s|let us|please|(?:can|could|would|will) you|"
            + "(?:i|we) (?:want|need|would like) to|(?:i|we)['’]d like to|help (?:me|us)(?: to)?)\\b[\\s,:;!]*)++");

    private TaskBranches() {}

    public static String generate(TaskId taskId, String githubLogin, String description)
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(description, "description");
        String prefix = prefix(githubLogin);
        String descriptionLine = TaskTitles.firstLine(description).toLowerCase(Locale.ROOT);
        String slug = Normalizer.normalize(REQUEST_PREFIX.matcher(descriptionLine).replaceFirst(""), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        String[] words = slug.split("-");
        String shortSlug = String.join("-", Arrays.copyOf(words, Math.min(words.length, MAX_SLUG_WORDS)));
        if (shortSlug.length() > MAX_SLUG_LENGTH) {
            int boundary = shortSlug.lastIndexOf('-', MAX_SLUG_LENGTH);
            shortSlug = shortSlug.substring(0, boundary > 0 ? boundary : MAX_SLUG_LENGTH);
        }
        if (!shortSlug.equals(slug)) {
            shortSlug = shortSlug.replaceFirst("(?:-(?:a|an|the|and|or|in|on|at|to|for|of|with|from))+$", "");
        }
        return prefix + (shortSlug.isEmpty() ? "task" : shortSlug) + "-" + taskId.toString().substring(0, 8);
    }

    public static String prefix(String githubLogin)
    {
        checkNotBlank(githubLogin, "githubLogin");
        String login = githubLogin.toLowerCase(Locale.ROOT);
        checkArgument(login.matches(LOGIN), "githubLogin is not a safe GitHub login");
        return login + "/bytequay/";
    }

    static boolean isCleanName(String branch)
    {
        return branch.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)+");
    }

    static boolean matches(String branch)
    {
        return branch.length() <= 128 && BRANCH.matcher(branch).matches();
    }
}
