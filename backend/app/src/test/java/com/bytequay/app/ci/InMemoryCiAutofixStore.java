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
package com.bytequay.app.ci;

import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Status;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

final class InMemoryCiAutofixStore
        implements CiAutofixStore
{
    CiAutofixRun run;
    CiAutofixRun conflict;
    private RuntimeException updateFailure;

    @Override
    public void insert(CiAutofixRun value)
    {
        if (conflict != null) {
            run = conflict;
            throw new CiAutofixConflictException("simulated insert race");
        }
        if (run != null) {
            throw new CiAutofixConflictException("run already exists");
        }
        run = value;
    }

    @Override
    public Optional<CiAutofixRun> find(CiAutofixRunId runId)
    {
        return Optional.ofNullable(run).filter(value -> value.id().equals(runId));
    }

    @Override
    public Optional<CiAutofixRun> findByIdempotencyKey(String key)
    {
        return Optional.ofNullable(run).filter(value -> value.startIdempotencyKey().equals(key));
    }

    @Override
    public Optional<CiAutofixRun> findByAgentTaskId(String requestedTaskId)
    {
        return Optional.ofNullable(run)
                .filter(value -> value.agentTaskId().filter(requestedTaskId::equals).isPresent());
    }

    @Override
    public List<CiAutofixRun> listRecent(int limit)
    {
        return run == null ? ImmutableList.of() : ImmutableList.of(run);
    }

    @Override
    public List<CiAutofixRun> listNonFinished(int limit)
    {
        return run == null || run.status() == Status.FINISHED ? ImmutableList.of() : ImmutableList.of(run);
    }

    @Override
    public CiAutofixRun update(CiAutofixRun value)
    {
        if (updateFailure != null) {
            RuntimeException failure = updateFailure;
            updateFailure = null;
            throw failure;
        }
        if (run == null || value.version() != run.version()) {
            throw new CiAutofixConflictException("version changed");
        }
        run = value.withVersion(value.version() + 1);
        return run;
    }

    void failNextUpdate(RuntimeException failure)
    {
        updateFailure = failure;
    }
}
