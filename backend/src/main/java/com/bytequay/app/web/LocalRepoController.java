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

import com.bytequay.app.domain.LocalRepoStatus;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.LocalRepoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for the Repos page. Reads only — clone / locate / push
 * land in follow-up commits once the list view ships.
 */
@RestController
@RequestMapping("/api/repos/local")
public class LocalRepoController
{
    private final LocalRepoService localRepoService;
    private final WatchedRepoStore watchedRepoStore;

    public LocalRepoController(LocalRepoService localRepoService, WatchedRepoStore watchedRepoStore)
    {
        this.localRepoService = requireNonNull(localRepoService, "localRepoService is null");
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
    }

    /**
     * GET /api/repos/local — one row per watched repo with its
     * local-clone state. Drives the cards on the Repos page.
     */
    @GetMapping
    public List<LocalRepoStatus> listAll()
    {
        return localRepoService.listAll();
    }

    /**
     * PUT /api/repos/local/{owner}/{repo}/path — record a local
     * working-copy path against a watched repo. Pass an empty string
     * to unmap. The clone / locate-existing flows on the Repos page
     * call this once the user picks a destination.
     */
    @PutMapping("/{owner}/{repo}/path")
    public void setLocalClonePath(
            @PathVariable("owner") String owner,
            @PathVariable("repo") String repo,
            @RequestBody PathRequest body)
    {
        String path = body == null || body.path() == null || body.path().isBlank() ? null : body.path();
        watchedRepoStore.setLocalClonePath(owner, repo, path);
    }

    public record PathRequest(String path) {}
}
