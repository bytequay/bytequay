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
package com.bytequay.core.store;

import com.bytequay.core.domain.AssistantAction;
import com.bytequay.core.domain.AssistantDay;
import com.bytequay.core.domain.AssistantItem;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AssistantStore
{
    Optional<AssistantDay> find(LocalDate day);

    void save(AssistantDay day);

    /** The day's items in the order they were added. */
    List<AssistantItem> items(LocalDate day);

    void add(AssistantItem item);

    void addAction(AssistantAction action);

    /** The action tool's calls on one message, in the order they were made. */
    List<AssistantAction> actions(String itemId);

    /** Removes the days, their items and their actions before {@code day}. */
    void deleteBefore(LocalDate day);
}
