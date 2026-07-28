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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.stage.persistence.SqliteStageResumeRearmStore;
import com.bytequay.app.developmentflow.task.TaskResumeOwner;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

@Component
final class RemoteTaskResumeOwner
        implements TaskResumeOwner
{
    private final SqliteStageResumeRearmStore store;

    RemoteTaskResumeOwner(SqliteStageResumeRearmStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    @Override
    public StageKind kind() { return StageKind.REMOTE_DEVELOPMENT; }

    @Override
    public Acceptance accept(Request request) { return store.accept(request, kind()); }
}
