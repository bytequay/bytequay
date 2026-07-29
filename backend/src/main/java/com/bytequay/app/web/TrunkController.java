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

import com.bytequay.app.beans.workspace.TrunkDto;
import com.bytequay.app.developmentflow.compatibility.V2TrunkRuntimeProjection;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.repository.ThreadStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

import static java.util.Objects.requireNonNull;

/** Public Trunk vocabulary over the existing Thread rows. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/trunks")
public class TrunkController
{
    private final ThreadStore threads;
    private final V2TrunkRuntimeProjection trunkRuntime;

    public TrunkController(
            ThreadStore threads,
            V2TrunkRuntimeProjection trunkRuntime)
    {
        this.threads = requireNonNull(threads, "threads is null");
        this.trunkRuntime = requireNonNull(
                trunkRuntime, "trunkRuntime is null");
    }

    @GetMapping
    public List<TrunkDto> list(@PathVariable String workspaceId)
    {
        List<Thread> stored = threads.listThreadsByWorkspace(workspaceId).stream()
                .filter(thread -> thread.kind() != ThreadKind.BRAIN_AGENT)
                .filter(thread -> thread.flow() != ThreadFlow.REVIEW)
                .toList();
        return trunkRuntime.projectAll(stored).stream()
                .map(TrunkDto::from)
                .toList();
    }

    @GetMapping("/{trunkId}")
    public TrunkDto get(
            @PathVariable String workspaceId,
            @PathVariable String trunkId)
    {
        return threads.findThreadById(trunkId)
                .filter(thread -> workspaceId.equals(thread.workspaceId()))
                .filter(thread -> thread.kind() != ThreadKind.BRAIN_AGENT)
                .filter(thread -> thread.flow() != ThreadFlow.REVIEW)
                .map(trunkRuntime::project)
                .map(TrunkDto::from)
                .orElseThrow(() -> new NoSuchElementException(
                        "no trunk in workspace: " + trunkId));
    }
}
