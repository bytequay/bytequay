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

import com.bytequay.app.developmentflow.trunk.ThreadTurnProjection;
import com.bytequay.app.domain.TrunkTraceEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** Read-only provider trace, kept separate from conversation messages. */
@RestController
@RequestMapping("/api/threads/{trunkId}/traces")
public class TrunkTraceController
{
    private final ThreadTurnProjection turns;

    public TrunkTraceController(ThreadTurnProjection turns)
    {
        this.turns = requireNonNull(turns, "turns is null");
    }

    @GetMapping
    public List<TrunkTraceEvent> traces(
            @PathVariable String trunkId,
            @RequestParam(name = "requestMessageId", required = false)
            List<String> requestMessageIds)
    {
        return turns.traceEvents(
                trunkId,
                requestMessageIds == null ? List.of() : requestMessageIds);
    }
}
