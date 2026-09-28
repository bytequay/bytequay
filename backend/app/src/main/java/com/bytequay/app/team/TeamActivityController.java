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
package com.bytequay.app.team;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/teams")
public class TeamActivityController
{
    private final TeamActivityService activity;

    public TeamActivityController(TeamActivityService activity)
    {
        this.activity = requireNonNull(activity, "activity is null");
    }

    @GetMapping("/activity")
    public TeamActivitySnapshot activity(@RequestParam Optional<String> circleId, @RequestParam Optional<Long> memberId,
            @RequestParam(defaultValue = "7") int days, @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "all") String filter)
    {
        return activity.snapshot(circleId, memberId, days, limit, filter);
    }

    @PostMapping("/refresh")
    public TeamActivitySnapshot refresh(@RequestParam Optional<String> circleId, @RequestParam Optional<Long> memberId,
            @RequestParam(defaultValue = "7") int days, @RequestParam(defaultValue = "100") int limit, @RequestParam(defaultValue = "all") String filter)
    {
        return activity.refresh(circleId, memberId, days, limit, filter);
    }
}
