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

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class BackendServerConfiguration
{
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> backendPortFallback()
    {
        return factory -> factory.setProtocol(FallbackHttp11NioProtocol.class.getName());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announcePort(ApplicationReadyEvent event)
            throws IOException
    {
        Environment environment = event.getApplicationContext().getEnvironment();
        int port = environment.getProperty("local.server.port", Integer.class, -1);
        if (port <= 0) {
            return;
        }
        String portFile = environment.getProperty("bytequay.backend.port-file", "");
        if (!portFile.isBlank()) {
            Files.writeString(Path.of(portFile), port + "\n");
        }
        // The desktop launcher consumes this exact stdout line; normal log formatting is not a protocol.
        System.out.println("BYTEQUAY_BACKEND_PORT=" + port);
        System.out.flush();
    }
}
