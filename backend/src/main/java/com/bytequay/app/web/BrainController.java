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

import com.bytequay.app.beans.brain.BrainMessageRequest;
import com.bytequay.app.beans.brain.BrainMessageResponse;
import com.bytequay.app.service.brain.BrainService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Posts a user question to a task's read-only brain agent. The reply
 * streams back over the existing thread SSE endpoint; this returns the
 * turn + brain-thread ids so the frontend can subscribe.
 */
@RestController
public class BrainController
{
    private final BrainService brainService;

    public BrainController(BrainService brainService)
    {
        this.brainService = requireNonNull(brainService, "brainService is null");
    }

    @PostMapping("/api/tasks/{taskId}/brain/message")
    @ResponseStatus(HttpStatus.CREATED)
    public BrainMessageResponse message(
            @PathVariable String taskId,
            @RequestBody BrainMessageRequest body)
    {
        String text = body == null ? null : body.text();
        List<String> images = body == null ? null : body.images();
        return brainService.sendMessage(taskId, text, images);
    }
}
