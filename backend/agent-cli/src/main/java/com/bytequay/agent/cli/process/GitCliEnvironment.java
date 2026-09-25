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
package com.bytequay.agent.cli.process;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/** Removes ambient GitHub and SSH credentials from agent process environments. */
public final class GitCliEnvironment
{
    private static final List<String> VARIABLES = ImmutableList.of(
            "GH_TOKEN",
            "GITHUB_TOKEN",
            "GH_ENTERPRISE_TOKEN",
            "GITHUB_ENTERPRISE_TOKEN",
            "GH_HOST",
            "GH_CONFIG_DIR",
            "SSH_AUTH_SOCK");

    private GitCliEnvironment() {}

    public static List<String> variables()
    {
        return VARIABLES;
    }

    /** Provider authentication stays in the CLI; ByteQuay does not add host Git credentials to its process. */
    public static Map<String, String> withoutCredentials(Map<String, String> environment)
    {
        requireNonNull(environment, "environment is null");
        ImmutableMap.Builder<String, String> selected = ImmutableMap.builder();
        environment.forEach((name, value) -> {
            if (!VARIABLES.contains(name)) {
                selected.put(name, value);
            }
        });
        return selected.buildOrThrow();
    }
}
