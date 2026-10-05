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

import com.bytequay.agent.spi.AgentPermissionScope;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What a session may write outside its worktree, chosen by access level for both lanes. A session that may change nothing,
 * such as one answering a discussion over untrusted text, writes nowhere: a build cache or temporary file it left behind,
 * like a Gradle init script, would run in later builds.
 */
public final class SessionWriteRoots
{
    private SessionWriteRoots() {}

    public static List<Path> buildCaches(AgentPermissionScope scope, Map<String, String> environment)
    {
        return scope.access().restricted() ? ImmutableList.of() : BuildCacheRoots.writableRoots(environment);
    }

    public static List<Path> temporary(AgentPermissionScope scope, Map<String, String> environment)
    {
        return scope.access().restricted() ? ImmutableList.of() : TemporaryWriteRoots.writableRoots(environment);
    }
}
