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

import com.bytequay.app.domain.BranchGuard;
import com.bytequay.app.service.review.BranchGuardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

@RestController
public class BranchGuardController
{
    private final BranchGuardService guards;

    public BranchGuardController(BranchGuardService guards)
    {
        this.guards = requireNonNull(guards, "guards is null");
    }

    @GetMapping("/api/tasks/{taskId}/guard")
    public BranchGuard guard(@PathVariable String taskId)
    {
        return guards.get(taskId);
    }

    public record GuardPatch(Boolean enabled, String schedule) {}

    @PatchMapping("/api/tasks/{taskId}/guard")
    public BranchGuard updateGuard(@PathVariable String taskId, @RequestBody GuardPatch patch)
    {
        return guards.update(taskId, patch == null ? null : patch.enabled(),
                patch == null ? null : patch.schedule());
    }
}
