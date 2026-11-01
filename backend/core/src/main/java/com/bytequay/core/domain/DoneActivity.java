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
package com.bytequay.core.domain;

import com.google.common.collect.ImmutableList;

import java.util.List;

/**
 * What the viewer finished in a span of days, newest first.
 *
 * @param complete false when part of GitHub could not be read, so the list may miss pull requests, reviews or issues
 */
public record DoneActivity(List<DoneItem> items, boolean complete)
{
    public DoneActivity
    {
        items = ImmutableList.copyOf(items);
    }
}
