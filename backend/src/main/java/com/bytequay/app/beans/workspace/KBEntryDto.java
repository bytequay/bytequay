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
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.bytequay.app.beans.workspace;

import java.util.List;
import java.util.Map;

/** Durable workspace knowledge loaded only for matching session audiences. */
public record KBEntryDto(
        String id,
        String workspaceId,
        String title,
        String body,
        List<String> audience,
        Map<String, Object> provenance,
        long createdAt,
        long updatedAt)
{
}
