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
package com.bytequay.agent.spi;

/**
 * Input was sent but nothing proves whether the provider took it.
 *
 * <p>A timeout or a lost connection after the request left this process. The caller must not
 * resend on its own, because the provider has no key that would let it drop a duplicate.
 */
public class AgentDeliveryUncertainException
        extends AgentUnavailableException
{
    public AgentDeliveryUncertainException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
