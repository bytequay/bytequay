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

import com.google.common.collect.ImmutableList;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Spec checks of a workspace's directories. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/repository-context/spec-checks")
public class ContextSpecCheckController
{
    private final ContextSpecCheckService checks;

    public ContextSpecCheckController(ContextSpecCheckService checks)
    {
        this.checks = requireNonNull(checks, "checks is null");
    }

    @GetMapping
    public Checks checks(@PathVariable String workspaceId)
    {
        return new Checks(checks.checks(workspaceId));
    }

    /** Starts one check of a directory; the user reads its report when it ends. */
    @PostMapping
    public ContextSpecCheckService.Started start(@PathVariable String workspaceId, @RequestBody ContextSpecCheckService.StartRequest request)
    {
        return checks.start(workspaceId, request);
    }

    @PostMapping("/{checkId}/candidates/{candidateId}/dismiss")
    public ContextSpecCheck dismiss(@PathVariable String workspaceId, @PathVariable String checkId, @PathVariable String candidateId,
            @RequestBody DismissRequest request)
    {
        return checks.dismiss(workspaceId, checkId, candidateId, request.reason());
    }

    @GetMapping("/sources")
    public Sources sources(@PathVariable String workspaceId, @RequestParam String directory)
    {
        return new Sources(checks.sources(workspaceId, directory));
    }

    public record Checks(List<ContextSpecCheck> checks)
    {
        public Checks
        {
            checks = ImmutableList.copyOf(checks);
        }
    }

    public record DismissRequest(String reason) {}

    public record Sources(List<ContextSpecSource> sources)
    {
        public Sources
        {
            sources = ImmutableList.copyOf(sources);
        }
    }
}
