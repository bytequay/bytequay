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

import com.bytequay.app.developmentflow.compatibility.DevelopmentFlowCanaryRoute;
import com.bytequay.app.developmentflow.compatibility.DevelopmentFlowInvariantAuditor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/** Local operator diagnostics for the V2 canary and LEGACY drain. */
@RestController
@RequestMapping("/api/development-flow")
public final class DevelopmentFlowDiagnosticsController
{
    private final DevelopmentFlowInvariantAuditor auditor;
    private final DevelopmentFlowCanaryRoute route;

    public DevelopmentFlowDiagnosticsController(
            DevelopmentFlowInvariantAuditor auditor,
            DevelopmentFlowCanaryRoute route)
    {
        this.auditor = requireNonNull(auditor, "auditor is null");
        this.route = requireNonNull(route, "route is null");
    }

    @GetMapping("/diagnostics")
    public DevelopmentFlowInvariantAuditor.Audit diagnostics()
    {
        return auditor.audit();
    }

    @GetMapping("/legacy-drain")
    public DevelopmentFlowInvariantAuditor.DrainStatus legacyDrain()
    {
        return auditor.legacyDrainStatus();
    }

    @GetMapping("/route")
    public DevelopmentFlowCanaryRoute.Snapshot route()
    {
        return route.snapshot();
    }
}
