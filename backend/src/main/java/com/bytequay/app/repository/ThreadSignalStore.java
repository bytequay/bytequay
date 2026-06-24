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

import com.bytequay.app.domain.ThreadSignal;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for the per-thread passive signal feed. */
public interface ThreadSignalStore
{
    /** Insert or update a signal; returns the persisted row. */
    ThreadSignal save(ThreadSignal signal);

    /** Signals on a thread, newest-first. */
    List<ThreadSignal> findByThread(String threadId);

    /** One signal by id. */
    Optional<ThreadSignal> findById(String id);
}
