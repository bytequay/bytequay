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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TestPullRequestTemplate
{
    @Test
    void findsTemplateInDotGithub(@TempDir Path repo)
            throws IOException
    {
        Files.createDirectories(repo.resolve(".github"));
        Files.writeString(repo.resolve(".github/PULL_REQUEST_TEMPLATE.md"),
                "## Summary\n\n## Checklist\n- [ ] tests");

        assertThat(PullRequestTemplate.find(repo.toString()))
                .hasValueSatisfying(t -> assertThat(t).contains("## Summary").contains("- [ ] tests"));
    }

    @Test
    void prefersDotGithubOverRootAndDocs(@TempDir Path repo)
            throws IOException
    {
        Files.createDirectories(repo.resolve(".github"));
        Files.createDirectories(repo.resolve("docs"));
        Files.writeString(repo.resolve(".github/PULL_REQUEST_TEMPLATE.md"), "from-github");
        Files.writeString(repo.resolve("PULL_REQUEST_TEMPLATE.md"), "from-root");
        Files.writeString(repo.resolve("docs/PULL_REQUEST_TEMPLATE.md"), "from-docs");

        assertThat(PullRequestTemplate.find(repo.toString())).hasValue("from-github");
    }

    @Test
    void findsLowercaseRootTemplate(@TempDir Path repo)
            throws IOException
    {
        Files.writeString(repo.resolve("pull_request_template.md"), "rooty");
        assertThat(PullRequestTemplate.find(repo.toString())).hasValue("rooty");
    }

    @Test
    void findsFirstTemplateInMultiTemplateDir(@TempDir Path repo)
            throws IOException
    {
        Files.createDirectories(repo.resolve(".github/PULL_REQUEST_TEMPLATE"));
        Files.writeString(repo.resolve(".github/PULL_REQUEST_TEMPLATE/bugfix.md"), "bug body");
        Files.writeString(repo.resolve(".github/PULL_REQUEST_TEMPLATE/feature.md"), "feat body");

        // Sorted by name → bugfix.md wins.
        assertThat(PullRequestTemplate.find(repo.toString())).hasValue("bug body");
    }

    @Test
    void emptyWhenNoTemplate(@TempDir Path repo)
    {
        assertThat(PullRequestTemplate.find(repo.toString())).isEmpty();
    }

    @Test
    void emptyOnBlankOrMissingDir()
    {
        assertThat(PullRequestTemplate.find(null)).isEmpty();
        assertThat(PullRequestTemplate.find("  ")).isEmpty();
        assertThat(PullRequestTemplate.find("/no/such/dir/anywhere-xyz")).isEmpty();
    }

    @Test
    void capsOverlongTemplate(@TempDir Path repo)
            throws IOException
    {
        Files.writeString(repo.resolve("PULL_REQUEST_TEMPLATE.md"), "x".repeat(9000));
        assertThat(PullRequestTemplate.find(repo.toString()))
                .hasValueSatisfying(t -> assertThat(t.length()).isEqualTo(6000));
    }
}
