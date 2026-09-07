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
package com.bytequay.core.review;

import static java.util.Objects.requireNonNull;

/** The replay record of one write tool call: the normalized payload it carried and the result it was given. */
public record ReviewOperation(String payloadDigest, String result)
{
    public ReviewOperation
    {
        requireNonNull(payloadDigest, "payloadDigest is null");
        requireNonNull(result, "result is null");
    }
}
