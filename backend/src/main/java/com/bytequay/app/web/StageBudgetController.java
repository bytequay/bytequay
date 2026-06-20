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
import com.bytequay.app.service.stage.StageBudgetService;
import com.bytequay.app.service.stage.StageMetrics;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * The two recovery actions a user takes when a ci-fixing stage's auto-push
 * budget is exhausted: extend it, or fall back to per-push review. Both
 * 404 on an unknown stage and 422 unless the stage is actually awaiting
 * a budget decision (see {@link StageBudgetService}).
 */
@RestController
public class StageBudgetController
{
    private final StageBudgetService budgetService;

    public StageBudgetController(StageBudgetService budgetService)
    {
        this.budgetService = requireNonNull(budgetService, "budgetService is null");
    }

    @PostMapping("/api/stages/{stageId}/budget/extend")
    public StageMetrics extend(
            @PathVariable String stageId,
            @RequestBody(required = false) ExtendBudgetRequest body)
    {
        Integer additional = body == null ? null : body.additional();
        return budgetService.extendBudget(parse(stageId), additional);
    }

    @PostMapping("/api/stages/{stageId}/budget/fallback-to-review")
    public StageMetrics fallbackToReview(@PathVariable String stageId)
    {
        return budgetService.fallbackToReview(parse(stageId));
    }

    private static UUID parse(String raw)
    {
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "bad stage id: " + raw);
        }
    }
}
