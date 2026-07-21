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

import com.bytequay.app.service.ai.PlanUsageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static java.util.Objects.requireNonNull;

/** Exposes the latest local provider plan-limit snapshots to the desktop UI. */
@RestController
public class AiPlanUsageController
{
    private final PlanUsageService service;

    public AiPlanUsageController(PlanUsageService service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    @GetMapping("/api/ai/plan-usage")
    public PlanUsageService.PlanUsage current()
    {
        return service.current();
    }

    @PostMapping("/api/ai/plan-usage/claude/refresh")
    public PlanUsageService.PlanUsage refreshClaude()
    {
        try {
            return service.refreshClaude();
        }
        catch (IllegalStateException e) {
            // Scraping /usage out of the interactive CLI is best-effort — the TUI
            // may not render in a spawned pty, its format may drift, or the user
            // may be logged out. The panel auto-fires this on mount, so surface an
            // upstream 503 (logged quietly) instead of a 500 that spams unhandled
            // stack traces. The client already shows a "refresh failed" message.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        }
    }
}
