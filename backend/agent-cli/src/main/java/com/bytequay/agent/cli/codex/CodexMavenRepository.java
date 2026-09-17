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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The user's Maven local repository, which attended Codex turns may write so builds and tests work in the sandbox.
 *
 * <p>Only {@code $HOME/.m2/repository} itself qualifies. Agent sandboxes and a pull request's code can reach
 * {@code ~/.m2/settings.xml}, {@code MAVEN_OPTS} and a worktree's {@code .mvn} files, so none of them, and no symbolic
 * link, may choose what a turn can write.
 */
final class CodexMavenRepository
{
    private CodexMavenRepository() {}

    /**
     * @param environment the driver environment, whose {@code HOME} is used
     * @return the repository, or empty when {@code HOME} is unusable, the directory is missing, or {@code .m2} or
     * {@code repository} is a symbolic link
     */
    static Optional<Path> resolve(Map<String, String> environment)
    {
        try {
            Path home = Path.of(environment.getOrDefault("HOME", ""));
            if (!home.isAbsolute()) {
                return Optional.empty();
            }
            Path repository = home.toRealPath().resolve(".m2").resolve("repository");
            // A real path equal to the lexical one means neither .m2 nor repository redirects elsewhere.
            if (!Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS) || !repository.toRealPath().equals(repository)) {
                return Optional.empty();
            }
            return Optional.of(repository);
        }
        catch (IOException | InvalidPathException e) {
            return Optional.empty();
        }
    }
}
