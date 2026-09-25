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
package com.bytequay.app.context;

import com.bytequay.core.context.RepositoryNotebook;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** User-triggered previews and selected configuration acceptance. Neither endpoint starts an agent. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/repository-context/discovery")
public class ContextDiscoveryController
{
    private final ContextDiscoveryService discovery;

    public ContextDiscoveryController(ContextDiscoveryService discovery)
    {
        this.discovery = requireNonNull(discovery, "discovery is null");
    }

    @PostMapping
    public ContextDiscoveryService.Preview discover(@PathVariable String workspaceId, @RequestBody DiscoveryRequest request)
    {
        return discovery.discover(workspaceId, new ContextDiscoveryService.Request(
                request.expectedRevision(), request.scopeIds(), request.query(), request.includeRemote()));
    }

    @PostMapping("/apply")
    public RepositoryNotebook apply(@PathVariable String workspaceId, @RequestBody ApplyRequest request)
    {
        return discovery.apply(workspaceId, new ContextDiscoveryService.ApplyRequest(
                request.expectedRevision(), request.baselineSha(), request.repositoryFingerprint(), request.query(), request.scopeIds(), request.sourceIds()));
    }

    public record DiscoveryRequest(Long expectedRevision, List<String> scopeIds, String query, Boolean includeRemote)
    {
        public DiscoveryRequest
        {
            checkArgument(expectedRevision != null && expectedRevision >= 0, "expectedRevision is required and must not be negative");
            checkArgument(includeRemote != null, "includeRemote is required");
            ContextDiscoveryService.Request checked = new ContextDiscoveryService.Request(expectedRevision, scopeIds, query, includeRemote);
            scopeIds = checked.scopeIds();
            query = checked.query();
        }
    }

    public record ApplyRequest(Long expectedRevision, String baselineSha, String repositoryFingerprint, String query, List<String> scopeIds, List<String> sourceIds)
    {
        public ApplyRequest
        {
            checkArgument(expectedRevision != null && expectedRevision >= 0, "expectedRevision is required and must not be negative");
            ContextDiscoveryService.ApplyRequest checked = new ContextDiscoveryService.ApplyRequest(
                    expectedRevision, baselineSha, repositoryFingerprint, query, scopeIds, sourceIds);
            query = checked.query();
            scopeIds = checked.scopeIds();
            sourceIds = checked.sourceIds();
        }
    }
}
