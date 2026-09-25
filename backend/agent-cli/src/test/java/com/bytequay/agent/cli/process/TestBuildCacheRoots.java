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

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestBuildCacheRoots
{
    @TempDir
    private Path root;
    private Path home;

    @BeforeEach
    void createHome()
            throws IOException
    {
        home = Files.createDirectories(root.resolve("home")).toRealPath();
    }

    @Test
    void createsOnlyFixedBuildDirectoriesUnderHome()
            throws IOException
    {
        assertThat(resolve()).containsExactly(
                home.resolve(".m2"), home.resolve(".gradle"), home.resolve(".npm"));
        assertThat(Files.exists(home.resolve(".m2/settings.xml"))).isFalse();
        assertThat(BuildCacheRoots.writableRoots(ImmutableMap.of())).isEmpty();
        assertThat(BuildCacheRoots.writableRoots(ImmutableMap.of("HOME", "relative/home"))).isEmpty();
    }

    @Test
    void ignoresSettingsThatNameAnotherRepository()
            throws IOException
    {
        Path maven = resolve().getFirst();
        Path elsewhere = Files.createDirectories(root.resolve("LaunchAgents"));
        Files.writeString(home.resolve(".m2/settings.xml"), "<settings><localRepository>%s</localRepository></settings>".formatted(elsewhere));

        assertThat(resolve()).contains(maven).doesNotContain(elsewhere);
    }

    @Test
    void refusesALinkedMavenDirectory()
            throws IOException
    {
        Path target = Files.createDirectories(root.resolve("LaunchAgents"));
        Files.createSymbolicLink(home.resolve(".m2"), target);
        assertThat(resolve()).doesNotContain(home.resolve(".m2"), target);
    }

    private List<Path> resolve()
    {
        return BuildCacheRoots.writableRoots(ImmutableMap.of("HOME", home.toString()));
    }
}
