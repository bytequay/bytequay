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

import com.bytequay.app.backport.BackportChainService;
import com.bytequay.app.backport.BackportChainService.View;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/backport-chains")
public class BackportChainController
{
    private final BackportChainService service;

    public BackportChainController(BackportChainService service)
    {
        this.service = service;
    }

    @GetMapping
    public List<View> list(@PathVariable String workspaceId)
    {
        return service.list(workspaceId).stream().map(service::view).collect(com.google.common.collect.ImmutableList.toImmutableList());
    }

    @PostMapping("/{id}/source")
    public View selectSource(@PathVariable String workspaceId, @PathVariable String id, @RequestBody BackportChainService.SelectSource selection)
    {
        return service.view(service.selectSource(workspaceId, id, selection));
    }

    @PostMapping("/{id}/stop")
    public View stop(@PathVariable String workspaceId, @PathVariable String id)
    {
        return service.view(service.stop(workspaceId, id));
    }

    @PostMapping("/{id}/resume")
    public View resume(@PathVariable String workspaceId, @PathVariable String id)
    {
        return service.view(service.resume(workspaceId, id));
    }
}
