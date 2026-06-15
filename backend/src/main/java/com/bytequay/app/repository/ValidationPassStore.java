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
package com.bytequay.app.repository;

import java.time.Instant;

/** Persistence for the {@code validation_pass} audit log. */
public interface ValidationPassStore
{
    /** Open a new run row; returns its generated id. */
    long startPass(String taskId, Instant startedAt);

    /** Close the run row with its outcome. */
    void finishPass(long id, Instant endedAt, boolean passed, int fixRounds, String failuresJson);
}
