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
package com.bytequay.core.discussion;

import java.util.List;

public interface ReviewFixStore
{
    /** Stores a newly requested comment; a comment the task already holds keeps its row. */
    void insertIfAbsent(ReviewFixReply reply);

    void update(ReviewFixReply reply);

    /** Every comment Auto-Review fix handed to one task, oldest request first. */
    List<ReviewFixReply> listByTask(String taskId);
}
