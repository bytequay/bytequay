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
package com.bytequay.agent.cli.claude;

import java.net.URI;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** An Anthropic-compatible gateway, such as a company's LiteLLM proxy, that a Claude account reaches with a bearer token. */
public record ClaudeGateway(String baseUrl, String token)
{
    public ClaudeGateway
    {
        requireNonNull(baseUrl, "baseUrl is null");
        checkArgument(!requireNonNull(token, "token is null").isBlank(), "token is blank");
    }

    public String host()
    {
        return URI.create(baseUrl).getHost();
    }

    @Override
    public String toString()
    {
        return "ClaudeGateway[" + baseUrl + "]";
    }
}
