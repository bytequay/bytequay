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
package com.bytequay.app.service.stage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Locates a repository's pull-request template on disk so the dev agent can
 * draft a PR body that follows it. Mirrors GitHub's own resolution: a single
 * template named {@code PULL_REQUEST_TEMPLATE} (any case, {@code .md}/{@code
 * .markdown}/no extension) at the repo root, in {@code docs/}, or in {@code
 * .github/}; or, when a repo ships multiple, the first entry under {@code
 * .github/PULL_REQUEST_TEMPLATE/}. Returns the template text, capped so it
 * can't blow the agent prompt, or empty when the repo has none.
 */
final class PullRequestTemplate
{
    /** Generous cap — real templates are a few hundred lines at most. */
    private static final int MAX_CHARS = 6000;

    /** Single-file candidate locations, in GitHub's precedence order. */
    private static final List<String> SINGLE_FILE_CANDIDATES = List.of(
            ".github/PULL_REQUEST_TEMPLATE.md",
            ".github/pull_request_template.md",
            ".github/PULL_REQUEST_TEMPLATE.markdown",
            ".github/PULL_REQUEST_TEMPLATE",
            "PULL_REQUEST_TEMPLATE.md",
            "pull_request_template.md",
            "PULL_REQUEST_TEMPLATE",
            "docs/PULL_REQUEST_TEMPLATE.md",
            "docs/pull_request_template.md",
            "docs/PULL_REQUEST_TEMPLATE");

    private PullRequestTemplate() {}

    /**
     * Finds the repo's PR template under {@code repoDir}. Never throws —
     * any I/O problem (missing dir, unreadable file) resolves to empty.
     */
    static Optional<String> find(String repoDir)
    {
        if (repoDir == null || repoDir.isBlank()) {
            return Optional.empty();
        }
        Path root;
        try {
            root = Path.of(repoDir);
        }
        catch (RuntimeException e) {
            return Optional.empty();
        }

        for (String candidate : SINGLE_FILE_CANDIDATES) {
            Optional<String> body = readIfRegularFile(root.resolve(candidate));
            if (body.isPresent()) {
                return body;
            }
        }
        return firstInTemplateDir(root.resolve(".github/PULL_REQUEST_TEMPLATE"));
    }

    private static Optional<String> firstInTemplateDir(Path dir)
    {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(p -> Files.isRegularFile(p))
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".md") || name.endsWith(".markdown");
                    })
                    .sorted()
                    .findFirst()
                    .flatMap(PullRequestTemplate::readIfRegularFile);
        }
        catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> readIfRegularFile(Path path)
    {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            String body = Files.readString(path).strip();
            if (body.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(body.length() > MAX_CHARS ? body.substring(0, MAX_CHARS) : body);
        }
        catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }
}
