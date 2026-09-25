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

import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentPermissionScope;
import com.bytequay.agent.spi.SessionAccess;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestAgentPermissionManager
{
    @TempDir
    private Path temporary;

    @Test
    void developmentAndCiAllowConcreteWritesOnlyWithinVerifiedRoots()
            throws Exception
    {
        Path worktree = Files.createDirectory(temporary.resolve("worktree"));
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(worktree.resolve("escape"), outside);
        var environment = ImmutableMap.of("HOME", home.toString());
        AgentPermissionScope development = new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false);
        AgentPermissionScope ci = new AgentPermissionScope(AgentPermissionPolicy.CI_AUTOFIX, SessionAccess.FULL, false);
        AgentPermissionScope cherryPick = new AgentPermissionScope(AgentPermissionPolicy.CHERRY_PICK, SessionAccess.FULL, false);

        assertThat(AgentPermissionManager.allowsFileWrite(worktree.resolve("new/source.java"), worktree, environment, development)).isTrue();
        assertThat(AgentPermissionManager.allowsFileWrite(home.resolve(".m2/settings.xml"), worktree, environment, ci)).isTrue();
        assertThat(AgentPermissionManager.allowsFileWrite(worktree.resolve("escape/secret"), worktree, environment, development)).isFalse();
        assertThat(AgentPermissionManager.allowsFileWrite(outside.resolve("secret"), worktree, environment, ci)).isFalse();
        assertThat(AgentPermissionManager.allowsFileWrite(home.resolve(".ssh/id_ed25519"), worktree, environment, development)).isFalse();
        assertThat(AgentPermissionManager.allowsFileWrite(worktree.resolve("new/source.java"), worktree, environment, cherryPick)).isTrue();
        assertThat(AgentPermissionManager.allowsFileWrite(home.resolve(".m2/repository/test.pom"), worktree, environment, cherryPick)).isFalse();
        assertThat(AgentPermissionManager.allowsFileWrite(Path.of("relative"), worktree, environment, development)).isFalse();
        assertThat(AgentPermissionManager.allowsFileWrite(worktree.resolve("new/source.java"), worktree, environment,
                new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, true))).isFalse();
        assertThat(AgentPermissionManager.allowsFileWrite(worktree.resolve("new/source.java"), worktree, environment,
                new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.READ_ONLY, false))).isFalse();
    }

    @Test
    void linkedCacheRootDoesNotGrantItsTarget()
            throws Exception
    {
        Path worktree = Files.createDirectory(temporary.resolve("worktree"));
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.createSymbolicLink(home.resolve(".m2"), outside);

        assertThat(AgentPermissionManager.allowsFileWrite(home.resolve(".m2/settings.xml"), worktree,
                ImmutableMap.of("HOME", home.toString()),
                new AgentPermissionScope(AgentPermissionPolicy.AUTO, SessionAccess.FULL, false))).isFalse();
    }
}
