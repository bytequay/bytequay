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

import com.bytequay.app.beans.stage.ExtendBudgetRequest;
import com.bytequay.app.service.stage.StageMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fail-closed compatibility routes for the retired LEGACY stage budget.
 * V2 recovery is exposed through the typed Task CI-repair endpoint.
 */
@RestController
public class StageBudgetController
{
    @PostMapping("/api/stages/{stageId}/budget/extend")
    public StageMetrics extend(
            @PathVariable String stageId,
            @RequestBody(required = false) ExtendBudgetRequest body)
    {
        throw retired();
    }

    @PostMapping("/api/stages/{stageId}/budget/fallback-to-review")
    public StageMetrics fallbackToReview(@PathVariable String stageId)
    {
        throw retired();
    }

    private static ResponseStatusException retired()
    {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "LEGACY stage budget controls are retired; use typed V2 CI-repair recovery");
    }
}
