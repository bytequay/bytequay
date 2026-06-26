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
package com.bytequay.app.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TestNoRawJavaThreadStarts
{
    private static final Pattern BANNED_THREAD_OWNERSHIP = Pattern.compile(
            "new\\s+java\\.lang\\.Thread"
                    + "|Thread\\.startVirtualThread"
                    + "|Thread\\.ofVirtual\\(\\)(?:\\s*\\.\\s*name\\([^;]*?\\))?\\s*\\.\\s*start"
                    + "|new\\s+Thread\\s*\\(\\s*(?:\\(\\)\\s*->|r\\s*,)",
            Pattern.DOTALL);

    @Test
    void productionCodeDoesNotStartRawJavaThreads()
            throws IOException
    {
        Path mainSource = Path.of("src/main/java");
        List<String> violations;
        try (var paths = Files.walk(mainSource)) {
            violations = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> violationsIn(path).stream())
                    .toList();
        }

        assertThat(violations).isEmpty();
    }

    private static List<String> violationsIn(Path path)
    {
        try {
            String source = Files.readString(path);
            return BANNED_THREAD_OWNERSHIP.matcher(source).results()
                    .map(match -> path + ": " + source.substring(
                            match.start(), Math.min(match.end(), match.start() + 120)).replace('\n', ' '))
                    .toList();
        }
        catch (IOException e) {
            return List.of(path + ": " + e.getMessage());
        }
    }
}
