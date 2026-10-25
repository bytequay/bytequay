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
package com.bytequay.app.assistant;

import com.bytequay.core.domain.AssistantItem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * One assistant conversation as the panel draws it.
 *
 * @param model the model label of the conversation's last reply
 * @param running whether a reply in this conversation is being written now
 * @param items the conversation's messages, replies, receipts, lists and failures, oldest first
 * @param rows each ref the conversation's lists name that still resolves, read now; a ref missing here is gone
 */
public record AssistantView(String id, Optional<String> model, boolean running, List<AssistantItem> items, Map<String, AssistantRow> rows)
{
    public AssistantView
    {
        requireNonNull(id, "id is null");
        requireNonNull(model, "model is null");
        items = ImmutableList.copyOf(items);
        rows = ImmutableMap.copyOf(rows);
    }
}
