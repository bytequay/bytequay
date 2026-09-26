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
package com.bytequay.agent.spi;

import java.util.Optional;

/**
 * Names a capability a task's provider session must expose, by task identity alone.
 *
 * <p>The application answers from durable facts, so a resumed session after a restart receives the same tools and
 * instruction as the first one. Tasks without the capability resolve to nothing.
 */
@FunctionalInterface
public interface BridgeResolver<B>
{
    static <B> BridgeResolver<B> none()
    {
        return taskId -> Optional.empty();
    }

    Optional<B> resolve(String taskId);
}
