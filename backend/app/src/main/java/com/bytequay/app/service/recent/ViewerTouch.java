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
package com.bytequay.app.service.recent;

import com.bytequay.core.domain.RecentItemType;

/**
 * A touch the renderer observed: it is the only side that can see how long a
 * detail stayed open or that a diff was opened. Everything the user did to an
 * item is recorded on the write path instead, so this carries no verdict the
 * renderer could get wrong.
 *
 * @param dwellSeconds how long the detail stayed open; ignored for
 * {@code DIFF_OPENED}, which records on intent.
 */
public record ViewerTouch(
        RecentItemType type,
        String repo,
        int number,
        String workspaceId,
        Signal signal,
        int dwellSeconds)
{
    public enum Signal
    {
        DWELL,
        DIFF_OPENED
    }
}
