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

import static com.bytequay.core.domain.Checks.checkText;

/** Work the development model proposed for an Epic, which waits until the user accepts or dismisses it; nothing exists before that. */
public record EpicProposal(String id, String title, String doneWhen)
{
    public EpicProposal
    {
        checkText(id, "id");
        checkText(title, "title");
        checkText(doneWhen, "doneWhen");
    }
}
