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
package com.bytequay.app.web.review;

import com.bytequay.app.review.IdeConnection;
import com.bytequay.app.review.IdeRuntimeService;
import com.bytequay.app.review.ReviewCodeService;
import com.bytequay.app.review.TaskCodeHost;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.Objects.requireNonNull;

/** Authenticated desktop-only runtime transport; workspace paths are resolved from durable task ownership. */
@RestController
@RequestMapping("/agent/ide")
public class IdeController
{
    private final ReviewCodeService code;
    private final IdeRuntimeService runtime;

    public IdeController(ReviewCodeService code, IdeRuntimeService runtime)
    {
        this.code = requireNonNull(code, "code is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    @PostMapping("/reviews/{reviewId}")
    public IdeConnection openReview(@PathVariable String reviewId)
    {
        return runtime.open(code.ideWorktree(reviewId));
    }

    @PostMapping("/tasks/{workspaceId}/{taskId}")
    public IdeConnection openTask(@PathVariable String workspaceId, @PathVariable String taskId)
    {
        return runtime.open(code.ideWorktree(new TaskCodeHost(workspaceId, taskId)));
    }

    @PostMapping("/{taskId}/{sessionId}/touch")
    public void touch(@PathVariable String taskId, @PathVariable String sessionId)
    {
        runtime.touch(taskId, sessionId);
    }

    @DeleteMapping("/{taskId}/{sessionId}")
    public void stop(@PathVariable String taskId, @PathVariable String sessionId)
    {
        runtime.stop(taskId, sessionId);
    }
}
