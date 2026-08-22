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
package com.bytequay.app.web.git;

import com.bytequay.app.git.AvatarCache;
import com.bytequay.app.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.IMAGE_PNG;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class TestAvatarController
{
    private static final String URL = "https://avatars.example.invalid/example.png";

    @TempDir
    private Path root;

    private AvatarCache cache;
    private MockMvc mvc;

    @BeforeEach
    void setUp()
    {
        cache = mock(AvatarCache.class);
        mvc = standaloneSetup(new AvatarController(cache))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void servesOnlyAnExistingLocalAvatar()
            throws Exception
    {
        String key = "1".repeat(64);
        Path file = root.resolve("avatar.png");
        Files.write(file, new byte[] {1, 2, 3});
        when(cache.find(key)).thenReturn(Optional.of(new AvatarCache.CachedAvatar(file, IMAGE_PNG)));

        mvc.perform(get("/api/avatars/{key}", key))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(new byte[] {1, 2, 3}))
                .andExpect(header().string("ETag", '"' + key + '"'));
    }

    @Test
    void returnsMissingWithoutDownloadingAndRejectsMalformedKeys()
            throws Exception
    {
        String missing = "0".repeat(64);
        when(cache.find(missing)).thenReturn(Optional.empty());
        when(cache.find("bad")).thenThrow(new IllegalArgumentException("avatar key is invalid"));

        mvc.perform(get("/api/avatars/{key}", missing))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/avatars/bad"))
                .andExpect(status().isBadRequest());

        verify(cache, never()).cache(URL);
    }
}
