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

import com.bytequay.app.service.ai.ApiUsageService;
import com.bytequay.app.service.ai.DeepSeekBalanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/** API-key usage observed locally, plus provider data available without admin keys. */
@RestController
public class AiApiUsageController
{
    private final ApiUsageService usage;
    private final DeepSeekBalanceService deepSeekBalance;

    public AiApiUsageController(ApiUsageService usage, DeepSeekBalanceService deepSeekBalance)
    {
        this.usage = requireNonNull(usage, "usage is null");
        this.deepSeekBalance = requireNonNull(deepSeekBalance, "deepSeekBalance is null");
    }

    @GetMapping("/api/ai/api-usage")
    public ApiUsageService.ApiUsage usage()
    {
        return usage.current();
    }

    @GetMapping("/api/ai/deepseek/balance")
    public DeepSeekBalanceService.DeepSeekBalance deepSeekBalance()
    {
        return deepSeekBalance.current();
    }
}
