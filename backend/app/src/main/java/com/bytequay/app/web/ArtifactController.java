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

import com.bytequay.app.artifact.ArtifactService;
import com.bytequay.core.domain.Artifact;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/artifacts")
public class ArtifactController
{
    private final ArtifactService artifacts;

    public ArtifactController(ArtifactService artifacts)
    {
        this.artifacts = requireNonNull(artifacts, "artifacts is null");
    }

    @GetMapping
    public List<Artifact> list(@PathVariable String workspaceId)
    {
        return artifacts.list(workspaceId);
    }

    @GetMapping("/{artifactId}")
    public Page page(@PathVariable String workspaceId, @PathVariable String artifactId)
    {
        return artifacts.page(workspaceId, artifactId).map(Page::new)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This page is not written yet"));
    }

    public record Page(String html) {}

    @PostMapping
    public Artifact ask(@PathVariable String workspaceId, @RequestBody Ask request)
    {
        return artifacts.ask(workspaceId, request.ask());
    }

    @PostMapping("/{artifactId}/change")
    public Artifact change(@PathVariable String workspaceId, @PathVariable String artifactId, @RequestBody Ask request)
    {
        return artifacts.change(workspaceId, artifactId, request.ask());
    }

    public record Ask(String ask) {}

    @PostMapping("/{artifactId}/stop")
    public void stop(@PathVariable String workspaceId, @PathVariable String artifactId)
    {
        artifacts.stop(workspaceId, artifactId);
    }

    @PostMapping("/{artifactId}/next/{cardId}/write")
    public Artifact writeNext(@PathVariable String workspaceId, @PathVariable String artifactId, @PathVariable String cardId)
    {
        return artifacts.writeNext(workspaceId, artifactId, cardId);
    }

    @PostMapping("/{artifactId}/next/{cardId}/dismiss")
    public Artifact dismissNext(@PathVariable String workspaceId, @PathVariable String artifactId, @PathVariable String cardId)
    {
        return artifacts.dismissNext(workspaceId, artifactId, cardId);
    }
}
