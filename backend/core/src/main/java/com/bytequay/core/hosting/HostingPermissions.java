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
package com.bytequay.core.hosting;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/** A current credential's granted permissions; project membership remains a separate check. */
public record HostingPermissions(Status status, List<String> scopes, String detail)
{
    public HostingPermissions
    {
        requireNonNull(status, "status is null");
        scopes = ImmutableList.copyOf(scopes);
        checkText(detail, "detail");
    }

    public enum Status
    {
        VERIFIED,
        MISSING,
        UNAVAILABLE
    }
}
