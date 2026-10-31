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
package com.bytequay.core.domain;

import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.hosting.HostingServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static com.bytequay.core.hosting.HostingServer.GITHUB_COM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestWorkspace
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void linkedRepositoryMustBeTheSingleMonitoredUpstream()
    {
        assertThatThrownBy(() -> new Workspace(
                "workspace",
                "contributor/project",
                100,
                RepoRef.of("contributor", "project"),
                Optional.of(200L),
                Optional.of(RepoRef.of("upstream", "project")),
                300,
                RepoRef.of("other", "project"),
                Topology.FORK,
                "main",
                "https://github.com/contributor/project.git",
                Optional.of("main"),
                Optional.of("https://github.com/upstream/project.git"),
                "/tmp/workspace/repository",
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW,
                GITHUB_COM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upstream ID");
    }

    @Test
    void workspaceNamesItsServerAndProvider()
    {
        HostingServer gitlab = new HostingServer(GITLAB, URI.create("https://gitlab.example.com"), URI.create("https://gitlab.example.com/api/v4"));

        assertThat(GITHUB_COM).isEqualTo(new HostingServer(GITHUB, URI.create("https://github.com/"), URI.create("https://api.github.com")));
        assertThat(workspace(GITHUB_COM).server()).isEqualTo(GITHUB_COM);
        assertThat(workspace(GITHUB_COM).provider()).isEqualTo(GITHUB);
        assertThat(workspace(gitlab).server()).isEqualTo(gitlab);
        assertThat(workspace(gitlab).provider()).isEqualTo(GITLAB);
    }

    private static Workspace workspace(HostingServer server)
    {
        RepoRef repository = RepoRef.of("owner", "project");
        return new Workspace("workspace", "owner/project", 100, repository, Optional.empty(), Optional.empty(), 100, repository, Topology.DIRECT, "main",
                "https://example.com/owner/project.git", Optional.empty(), Optional.empty(), "/tmp/workspace/repository", Optional.empty(), Optional.empty(), 0,
                NOW, NOW, server);
    }
}
