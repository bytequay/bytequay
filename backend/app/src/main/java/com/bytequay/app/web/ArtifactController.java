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
package com.bytequay.app.web;

import com.bytequay.core.domain.Artifact;
import com.bytequay.core.domain.ArtifactSummary;
import com.bytequay.core.store.ArtifactStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/artifacts")
public class ArtifactController
{
    private final ArtifactStore artifacts;

    public ArtifactController(ArtifactStore artifacts)
    {
        this.artifacts = requireNonNull(artifacts, "artifacts is null");
    }

    @GetMapping
    public List<ArtifactSummary> list(@PathVariable String workspaceId)
    {
        return artifacts.list(workspaceId);
    }

    @GetMapping("/{artifactId}")
    public Artifact page(@PathVariable String workspaceId, @PathVariable String artifactId)
    {
        return artifacts.find(workspaceId, artifactId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This artifact no longer exists"));
    }
}
