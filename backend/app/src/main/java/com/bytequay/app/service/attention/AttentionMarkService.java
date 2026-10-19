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
package com.bytequay.app.service.attention;

import com.bytequay.core.domain.AttentionMark;
import com.bytequay.core.store.AttentionMarkStore;
import org.springframework.stereotype.Service;

import java.util.List;

import static java.util.Objects.requireNonNull;

/** What the user acknowledged or snoozed in Needs attention, kept in ByteQuay so every build sees the same marks. The list owns a mark's shape. */
@Service
public class AttentionMarkService
{
    private final AttentionMarkStore store;

    public AttentionMarkService(AttentionMarkStore store)
    {
        this.store = requireNonNull(store, "store is null");
    }

    public List<AttentionMark> marks()
    {
        return store.all();
    }

    public void change(List<AttentionMark> saved, List<String> removed)
    {
        store.change(saved, removed);
    }
}
