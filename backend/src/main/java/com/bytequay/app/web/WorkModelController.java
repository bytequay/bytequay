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
import com.bytequay.app.service.workmodel.CliAgentDetector;
import com.bytequay.app.service.workmodel.WorkModelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/**
 * Read endpoint for the work-model option tree the picker walks, plus
 * a refresh hook for the CLI-detection cache.
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
    private final CliAgentDetector cliDetector;

    public WorkModelController(WorkModelService workModels, CliAgentDetector cliDetector)
    {
        this.workModels = requireNonNull(workModels, "workModels is null");
        this.cliDetector = requireNonNull(cliDetector, "cliDetector is null");
    }

    /**
     * GET /api/work-models — the catalog × credentials × CLI detection
     * option tree. The picker re-fetches on open so a freshly added
     * credential / freshly installed CLI agent shows up without an app
     * restart (modulo the detector's short memo TTL).
     */
    @GetMapping("/work-models")
    public WorkModelOptions options()
    {
        return workModels.options();
    }

    /**
     * POST /api/work-models/refresh — forces the CLI detector to drop
     * its memo so the next read of {@link #options()} re-probes every
     * binary. Used by the picker's "refresh" affordance after the user
     * runs a CLI installer or auth flow outside ByteQuay.
     */
    @PostMapping("/work-models/refresh")
    public WorkModelOptions refresh()
    {
        cliDetector.invalidate();
        return workModels.options();
    }
}
