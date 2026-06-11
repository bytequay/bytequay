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

import com.bytequay.app.domain.WorkModelOptions;
import com.bytequay.app.service.workmodel.WorkModelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/**
 * Read endpoint for the work-model option tree the picker walks.
 *
 * <p>Phase 1 — turn execution still routes via the legacy active-
 * provider registry. The cascade resolver + lane router land in Phase 2
 * and read from the same source this endpoint exposes.
 */
@RestController
@RequestMapping("/api")
public class WorkModelController
{
    private final WorkModelService workModels;

    public WorkModelController(WorkModelService workModels)
    {
        this.workModels = requireNonNull(workModels, "workModels is null");
    }

    /**
     * GET /api/work-models — the catalog × credentials option tree.
     * The picker re-fetches on open so a freshly added credential shows
     * up without an app restart.
     */
    @GetMapping("/work-models")
    public WorkModelOptions options()
    {
        return workModels.options();
    }

    /**
     * POST /api/work-models/refresh — kept as an alias for the GET
     * endpoint so the picker's existing Refresh affordance keeps
     * working. The CLI-detection cache it used to invalidate is gone
     * (every CLI agent is now reported as available unconditionally),
     * so a refresh just re-reads the catalog × credentials snapshot.
     */
    @PostMapping("/work-models/refresh")
    public WorkModelOptions refresh()
    {
        return workModels.options();
    }
}
