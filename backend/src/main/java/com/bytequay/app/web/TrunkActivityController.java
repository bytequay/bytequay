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

import com.bytequay.app.beans.workspace.TrunkActivityDto;
import com.bytequay.app.service.workspaces.TrunkActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/** Canonical public Trunk projection; the legacy Thread APIs stay internal. */
@RestController
@RequestMapping("/api/trunks")
public class TrunkActivityController
{
    private final TrunkActivityService activity;

    public TrunkActivityController(TrunkActivityService activity)
    {
        this.activity = requireNonNull(activity, "activity is null");
    }

    @GetMapping("/{trunkId}/activity")
    public TrunkActivityDto activity(@PathVariable String trunkId)
    {
        return activity.get(trunkId);
    }
}
