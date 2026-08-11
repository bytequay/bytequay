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
package com.bytequay.app.flow.github;

import com.bytequay.app.flow.ci.CiAutofixCoordinator.CiObservationActivation;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;

import java.util.List;
import java.util.Map;

/** Provider-minted proof of one stable exhaustive GitHub CI read. */
public sealed interface GitHubCiObservationProof
        permits GitHubCiProvider.CompleteBatch
{
    boolean matchesActivation(CiObservationActivation activation);

    List<NormalizedCheck> checks();

    Map<String, byte[]> failedLogsByProviderCheckId();

    String batchDigest();
}
