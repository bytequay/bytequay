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
package com.bytequay.app.service.tools;

import java.util.List;

/**
 * Cache-stable envelope for one turn's request. The four sections are
 * ordered exactly as the spec requires:
 *
 * <ol>
 *   <li>{@code tools} — skill tools first (frozen JSON), then action tools.</li>
 *   <li>{@code systemBlocks} — the role skill block, then the brain. The
 *       manifest never lives here.</li>
 *   <li>{@code historyMessages} — append-only history, oldest first.</li>
 *   <li>{@code newTurn} — the new user turn at the tail.</li>
 * </ol>
 *
 * <p>The model's prefix cache (DeepSeek auto, Anthropic via
 * {@code cache_control}) hashes the bytes of {@code tools} +
 * {@code systemBlocks} + earlier {@code historyMessages} — anything
 * shorter and the next turn refills the cache from cold. Constructed
 * once per turn; throwaway after the request leaves the JVM.
 */
public record TurnRequest(
        List<String> tools,
        List<String> systemBlocks,
        List<String> historyMessages,
        String newTurn) {}
