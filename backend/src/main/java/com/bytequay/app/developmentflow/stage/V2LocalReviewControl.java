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

import java.util.List;

/** Persists one immutable V2 LocalFeedbackBatch and admits its typed StageTurn. */
public interface V2LocalReviewControl
{
    Submission submit(
            String taskId, String body, String verdict, List<String> commentIds);

    record Submission(int submitted, String turnId) {}
}
