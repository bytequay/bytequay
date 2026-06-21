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

import com.bytequay.app.service.ai.AiLedgerService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import static java.util.Objects.requireNonNull;

/**
 * The AI usage ledger. Thin delegator to {@link AiLedgerService}; no auth
 * beyond the existing internal-API sanity checks (localhost sidecar).
 */
@RestController
public class AiLedgerController
{
    private final AiLedgerService ledgerService;

    public AiLedgerController(AiLedgerService ledgerService)
    {
        this.ledgerService = requireNonNull(ledgerService, "ledgerService is null");
    }

    /** GET /api/ai/ledger?month=YYYY-MM — defaults to the current month. */
    @GetMapping("/api/ai/ledger")
    public AiLedgerService.AiLedger ledger(@RequestParam(value = "month", required = false) String month)
    {
        if (month == null || month.isBlank()) {
            return ledgerService.ledger(YearMonth.now());
        }
        try {
            return ledgerService.ledger(YearMonth.parse(month));
        }
        catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400),
                    "month must be YYYY-MM: " + month);
        }
    }
}
