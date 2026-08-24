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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

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
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("upstream ID");
    }
}
