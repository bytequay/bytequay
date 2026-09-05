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
package com.bytequay.app.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain git plumbing for tests that build throwaway repositories. */
public final class GitTestSupport
{
    private static final GitCommandRunner GIT = new GitCommandRunner();

    private GitTestSupport() {}

    public static String git(Path directory, String... arguments)
    {
        GitCommandRunner.Result result = GIT.run(directory, arguments);
        assertThat(result.exitCode()).as("git %s: %s", String.join(" ", arguments), result.output()).isZero();
        return result.output().strip();
    }

    public static Path initRepository(Path root)
            throws IOException
    {
        git(root.getParent(), "init", "--quiet", "-b", "main", root.toString());
        git(root, "config", "user.name", "ByteQuay Test");
        git(root, "config", "user.email", "bytequay@example.com");
        return root.toRealPath();
    }

    public static void write(Path repository, String path, String content)
    {
        try {
            Path file = repository.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void commit(Path repository, String subject)
    {
        git(repository, "add", "-A");
        git(repository, "commit", "--quiet", "-m", subject);
    }

    public static String head(Path repository)
    {
        return git(repository, "rev-parse", "HEAD");
    }
}
