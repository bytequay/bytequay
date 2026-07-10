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
package com.bytequay.app.service.brain;

import com.bytequay.app.beans.brain.BrainMessageResponse;

import java.util.List;

/**
 * Backend contract for the brain composer: post a user question to a
 * task's read-only brain agent. The agent's reply streams back via the
 * existing thread SSE endpoint and is persisted to the conversation log.
 */
public interface BrainService
{
    /**
     * Send {@code text} to the task's brain agent, lazily creating the
     * task's single brain thread if needed, and enqueue the answering turn.
     * Returns the turn id and the brain thread id (for the SSE subscription).
     * {@code images}: pasted-screenshot data URLs, optional/omittable.
     */
    BrainMessageResponse sendMessage(String taskId, String text, List<String> images);
}
