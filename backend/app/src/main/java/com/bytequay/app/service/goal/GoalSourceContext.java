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
package com.bytequay.app.service.goal;

import com.bytequay.app.context.ContextText;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Bounded text evidence for tool-free goal writing, read directly from the already fetched monitored baseline. */
@Component
public class GoalSourceContext
{
    private static final Logger log = LoggerFactory.getLogger(GoalSourceContext.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_EXCERPT = 16_384;
    private final WorkspaceRepositoryService repositories;
    private final GitCommandRunner git;

    public GoalSourceContext(WorkspaceRepositoryService repositories, GitCommandRunner git)
    {
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.git = requireNonNull(git, "git is null");
    }

    public Optional<Evidence> find(String workspaceId, String question)
    {
        // ponytail: eight literal terms are discovery hints, not semantic relationships; precise exploration uses the task's IDE services.
        List<String> words = GoalCandidates.words(ContextText.excerpt(question, 2000)).stream().limit(8).collect(toImmutableList());
        if (words.isEmpty()) {
            return Optional.empty();
        }
        try {
            WorkspaceRepositoryContext workspace = repositories.requireLocalRepository(workspaceId);
            GitCommandRunner.Result resolved = git.runLocalBoundedStdout(workspace.repository(), TIMEOUT, 128, ImmutableList.of(
                    "rev-parse", "--verify", "--end-of-options", "refs/remotes/" + workspace.sourceRemote() + "/" + workspace.defaultBranch() + "^{commit}"));
            if (!resolved.successful() || resolved.truncated() || !resolved.output().strip().matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
                return Optional.empty();
            }
            String commit = resolved.output().strip();
            ImmutableList.Builder<String> arguments = ImmutableList.<String>builder().add("grep", "--no-color", "--no-textconv", "-I", "-n", "-i", "-F", "-C", "2");
            words.forEach(word -> arguments.add("-e", word));
            arguments.add(commit, "--");
            GitCommandRunner.Result found = git.runLocalBoundedStdout(workspace.repository(), TIMEOUT, MAX_EXCERPT * 4, arguments.build());
            if (!found.successful() || found.output().isBlank()) {
                return Optional.empty();
            }
            String text = ContextText.excerpt(found.output(), MAX_EXCERPT);
            return Optional.of(new Evidence(commit, text, found.truncated() || !text.equals(found.output())));
        }
        catch (RuntimeException unavailable) {
            log.debug("Goal source context unavailable for workspace {}", workspaceId, unavailable);
            return Optional.empty();
        }
    }

    public record Evidence(String commit, String text, boolean truncated) {}
}
