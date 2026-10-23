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
package com.bytequay.app.config;

import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.NioEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.BindException;

public final class FallbackHttp11NioProtocol
        extends Http11NioProtocol
{
    private static final Logger log = LoggerFactory.getLogger(FallbackHttp11NioProtocol.class);

    public FallbackHttp11NioProtocol()
    {
        super(new NioEndpoint()
        {
            @Override
            protected void initServerSocket()
                    throws Exception
            {
                try {
                    super.initServerSocket();
                }
                catch (BindException e) {
                    if (getPort() == 0) {
                        throw e;
                    }
                    // Retry the real bind without restarting Spring or racing a separate free-port probe.
                    doCloseServerSocket();
                    log.info("Backend port {} is unavailable; requesting an OS-assigned port", getPortWithOffset());
                    setPort(0);
                    super.initServerSocket();
                }
            }
        });
    }
}
