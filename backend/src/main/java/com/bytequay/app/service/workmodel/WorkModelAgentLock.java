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
package com.bytequay.app.service.workmodel;

import com.bytequay.app.domain.WorkModel;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

/** Keeps a provider-native conversation on the agent that started it. */
public final class WorkModelAgentLock
{
    private WorkModelAgentLock() {}

    public static void requireSameAgent(boolean locked, WorkModel current, WorkModel requested)
    {
        if (!locked) {
            return;
        }
        if (requested == null
                || requested.kind() != current.kind()
                || !Objects.equals(requested.agentOrProvider(), current.agentOrProvider())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "agent is locked after the first message; only its model can be changed");
        }
    }
}
