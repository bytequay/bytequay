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
package com.bytequay.agent.cli.codex;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodexMavenRepository
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
    void usesOnlyTheRepositoryUnderHome()
            throws IOException
    {
        assertThat(resolve()).isEmpty();
        Path repository = Files.createDirectories(home.resolve(".m2/repository"));

        assertThat(resolve()).contains(repository);
        assertThat(CodexMavenRepository.resolve(ImmutableMap.of())).isEmpty();
        assertThat(CodexMavenRepository.resolve(ImmutableMap.of("HOME", "relative/home"))).isEmpty();
    }

    @Test
    void ignoresSettingsThatNameAnotherRepository()
            throws IOException
    {
        Path repository = Files.createDirectories(home.resolve(".m2/repository"));
        Path elsewhere = Files.createDirectories(root.resolve("LaunchAgents"));
        Files.writeString(home.resolve(".m2/settings.xml"), "<settings><localRepository>%s</localRepository></settings>".formatted(elsewhere));

        assertThat(resolve()).contains(repository);
    }

    @Test
    void refusesALinkedRepositoryOrMavenDirectory()
            throws IOException
    {
        Path target = Files.createDirectories(root.resolve("LaunchAgents"));
        Files.createDirectories(home.resolve(".m2"));
        Files.createSymbolicLink(home.resolve(".m2/repository"), target);
        assertThat(resolve()).isEmpty();

        Files.delete(home.resolve(".m2/repository"));
        Files.delete(home.resolve(".m2"));
        Path mavenHome = Files.createDirectories(root.resolve("m2/repository")).getParent();
        Files.createSymbolicLink(home.resolve(".m2"), mavenHome);
        assertThat(resolve()).isEmpty();
    }

    private Optional<Path> resolve()
    {
        return CodexMavenRepository.resolve(ImmutableMap.of("HOME", home.toString()));
    }
}
