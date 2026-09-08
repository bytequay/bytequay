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

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class TestBackendServerConfiguration
{
    @TempDir
    private Path temporaryDirectory;

    @Test
    void usesPreferredPortWhenAvailable(CapturedOutput output)
            throws Exception
    {
        int preferredPort;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            preferredPort = reservation.getLocalPort();
        }
        try (ServletWebServerApplicationContext context = start(preferredPort)) {
            assertThat(context.getWebServer().getPort()).isEqualTo(preferredPort);
            assertReady(context, output);
        }
    }

    @Test
    void usesOsAssignedPortWhenPreferredPortIsOccupied(CapturedOutput output)
            throws Exception
    {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                ServletWebServerApplicationContext context = start(occupied.getLocalPort())) {
            assertThat(context.getWebServer().getPort()).isNotEqualTo(occupied.getLocalPort());
            assertThat(occupied.isBound()).isTrue();
            assertThat(occupied.isClosed()).isFalse();
            assertReady(context, output);
        }
    }

    @Test
    void acceptsExplicitOsAssignedPort(CapturedOutput output)
            throws Exception
    {
        try (ServletWebServerApplicationContext context = start(0)) {
            assertReady(context, output);
        }
    }

    private ServletWebServerApplicationContext start(int port)
    {
        SpringApplication application = new SpringApplication(
                BackendServerConfiguration.class,
                ServletWebServerFactoryAutoConfiguration.class,
                TestServletConfiguration.class);
        application.setRegisterShutdownHook(false);
        return (ServletWebServerApplicationContext) application.run(
                "--spring.config.location=optional:file:" + temporaryDirectory.resolve("unused.properties"),
                "--spring.main.banner-mode=off",
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--bytequay.backend.port-file=" + temporaryDirectory.resolve("backend.port"));
    }

    private void assertReady(ServletWebServerApplicationContext context, CapturedOutput output)
            throws Exception
    {
        int port = context.getWebServer().getPort();
        assertThat(port).isBetween(1, 65535);
        assertThat(Files.readString(temporaryDirectory.resolve("backend.port"))).isEqualTo(port + "\n");
        assertThat(output.getOut().lines()).contains("BYTEQUAY_BACKEND_PORT=" + port);
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();
        Http11NioProtocol protocol = (Http11NioProtocol) server.getTomcat().getConnector().getProtocolHandler();
        assertThat(protocol.getAddress().getHostAddress()).isEqualTo("127.0.0.1");
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ready")).timeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("ready");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestServletConfiguration
    {
        @Bean
        public ServletRegistrationBean<HttpServlet> readyServlet()
        {
            return new ServletRegistrationBean<>(new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response)
                        throws IOException
                {
                    response.getWriter().write("ready");
                }
            }, "/ready");
        }
    }
}
