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

import com.bytequay.app.domain.DailyCard;
import com.bytequay.app.service.daily.DailyCardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

@RestController
public class DailyCardController
{
    private final DailyCardService service;

    public DailyCardController(DailyCardService service)
    {
        this.service = requireNonNull(service, "service is null");
    }

    /** GET /daily-card — today's curated home-page card. */
    @GetMapping("/daily-card")
    public DailyCard today()
    {
        return service.today();
    }
}
