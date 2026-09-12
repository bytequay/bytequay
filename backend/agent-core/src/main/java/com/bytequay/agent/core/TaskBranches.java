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
import java.util.Locale;
import java.util.regex.Pattern;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Git-safe task branch names, frozen before the worktree is created. */
public final class TaskBranches
{
    private static final String LOGIN = "[a-z0-9](?:[a-z0-9-]{0,37}[a-z0-9])?";
    private static final Pattern BRANCH = Pattern.compile(LOGIN + "/bytequay/[a-z0-9]+(?:-[a-z0-9]+)*-[0-9a-f]{8}");

    private TaskBranches() {}

    public static String generate(TaskId taskId, String githubLogin, String description)
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(githubLogin, "githubLogin");
        String login = githubLogin.toLowerCase(Locale.ROOT);
        checkArgument(login.matches(LOGIN), "githubLogin is not a safe GitHub login");
        String slug = Normalizer.normalize(TaskTitles.provisional(description), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        slug = slug.substring(0, Math.min(slug.length(), 48)).replaceAll("-$", "");
        return login + "/bytequay/" + (slug.isEmpty() ? "task" : slug) + "-" + taskId.toString().substring(0, 8);
    }

    static boolean matches(TaskId taskId, String branch)
    {
        return branch.length() <= 128 && BRANCH.matcher(branch).matches() && branch.endsWith("-" + taskId.toString().substring(0, 8));
    }
}
