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

import com.bytequay.app.service.skills.PonytailRefreshService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

@RestController
public class ManagedSkillController
{
    private final PonytailRefreshService ponytail;

    public ManagedSkillController(PonytailRefreshService ponytail)
    {
        this.ponytail = requireNonNull(ponytail, "ponytail is null");
    }

    @GetMapping("/api/skills/vendor/ponytail/status")
    public PonytailRefreshService.Status ponytailStatus()
    {
        return ponytail.status();
    }

    @PostMapping("/api/skills/vendor/ponytail/refresh")
    public PonytailRefreshService.Status refreshPonytail()
    {
        return ponytail.refresh();
    }
}
