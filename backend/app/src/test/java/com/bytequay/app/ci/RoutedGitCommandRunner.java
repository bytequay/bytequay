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
package com.bytequay.app.ci;

import com.bytequay.app.git.GitCommandRunner;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Routes test-only Git transport commands without changing repository URL configuration. */
public final class RoutedGitCommandRunner
        extends GitCommandRunner
{
    public static final String ROUTE_CONFIG = "bytequay.testRemoteRoute";

    public static String route(Path target, String sourceUrl)
    {
        return "url." + requireNonNull(target, "target is null").toUri()
                + ".insteadOf=" + requireNonNull(sourceUrl, "sourceUrl is null");
    }

    /**
     * Overrides the overload every other {@code run} funnels into, so a routed
     * command cannot escape by calling a different one.
     */
    @Override
    public Result run(Path worktree, Duration timeout, Map<String, String> environment, List<String> arguments)
    {
        requireNonNull(arguments, "arguments is null");
        if (arguments.isEmpty() || !isTransport(arguments.getFirst())) {
            return super.run(worktree, timeout, environment, arguments);
        }
        Result route = super.run(
                worktree, timeout, environment, ImmutableList.of("config", "--get", ROUTE_CONFIG));
        if (!route.successful() || route.truncated() || route.output().isBlank()) {
            return super.run(worktree, timeout, environment, arguments);
        }
        return super.run(worktree, timeout, environment, ImmutableList.<String>builder()
                .add("-c", route.output().strip())
                .addAll(arguments)
                .build());
    }

    private static boolean isTransport(String command)
    {
        return command.equals("fetch") || command.equals("ls-remote") || command.equals("push");
    }
}
