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
package com.bytequay.app.context;

import com.bytequay.core.context.ContextUsageEvent;
import com.bytequay.core.store.ContextUsageStore;
import org.springframework.stereotype.Service;

import static java.util.Objects.requireNonNull;

/** Records only task-bound observations; calls to this service cannot create or change notebook content. */
@Service
public class ContextUsageRecorder
{
    private final ContextUsageStore store;

    public ContextUsageRecorder(ContextUsageStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    public void record(ContextUsageEvent event)
    {
        store.record(requireNonNull(event, "event is null"));
    }

    public void confirm(String workspaceId, String receiptRef, ContextUsageEvent.Stage stage)
    {
        store.confirm(workspaceId, receiptRef, stage);
    }

    public void markUncertain(String workspaceId, String receiptRef, ContextUsageEvent.Stage stage)
    {
        store.markUncertain(workspaceId, receiptRef, stage);
    }
}
