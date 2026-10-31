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
package com.bytequay.app.team;

import com.bytequay.core.hosting.HostingServer;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One hosting account's private circles; their people are users on that account's server. */
public record TeamDirectory(long accountId, String accountLogin, HostingServer server, List<TeamCircle> circles)
{
    public TeamDirectory
    {
        checkArgument(accountId > 0, "Account ID must be positive");
        checkText(accountLogin, "Account login");
        requireNonNull(server, "server is null");
        circles = ImmutableList.copyOf(circles);
        checkArgument(circles.stream().map(TeamCircle::id).distinct().count() == circles.size(), "Directory contains duplicate circle IDs");
    }
}
