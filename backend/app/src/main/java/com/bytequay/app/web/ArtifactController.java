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

import com.bytequay.app.artifact.ArtifactRow;
import com.bytequay.app.artifact.ArtifactService;
import com.bytequay.app.artifact.ArtifactView;
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
    public List<ArtifactRow> list(@PathVariable String workspaceId)
    {
        return artifacts.list(workspaceId);
    }

    @PostMapping
    public ArtifactView start(@PathVariable String workspaceId, @RequestBody Message request)
    {
        return artifacts.start(workspaceId, request.text());
    }

    @GetMapping("/{artifactId}")
    public ArtifactView view(@PathVariable String workspaceId, @PathVariable String artifactId)
    {
        return artifacts.view(workspaceId, artifactId);
    }

    @PostMapping("/{artifactId}/messages")
    public ArtifactView send(@PathVariable String workspaceId, @PathVariable String artifactId, @RequestBody Message request)
    {
        return artifacts.send(workspaceId, artifactId, request.text());
    }

    public record Message(String text) {}

    @PostMapping("/{artifactId}/offers/{itemId}")
    public ArtifactView answerOffer(@PathVariable String workspaceId, @PathVariable String artifactId, @PathVariable String itemId, @RequestBody OfferAnswer request)
    {
        return artifacts.answerOffer(workspaceId, artifactId, itemId, request.accept());
    }

    public record OfferAnswer(boolean accept) {}

    @PostMapping("/{artifactId}/stop")
    public void stop(@PathVariable String workspaceId, @PathVariable String artifactId)
    {
        artifacts.stop(workspaceId, artifactId);
    }

    @GetMapping("/{artifactId}/pages/{pageId}")
    public Page page(@PathVariable String workspaceId, @PathVariable String artifactId, @PathVariable String pageId)
    {
        return artifacts.pageHtml(workspaceId, artifactId, pageId).map(Page::new)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This page no longer exists"));
    }

    public record Page(String html) {}
}
