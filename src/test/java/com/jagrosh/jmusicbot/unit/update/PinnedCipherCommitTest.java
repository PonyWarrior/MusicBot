/*
 * Copyright 2026 Arif Banai (arif-banai)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.unit.update;

import com.jagrosh.jmusicbot.update.UpdateManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundle and the in-app updater must install the same reviewed yt-cipher commit.
 */
class PinnedCipherCommitTest
{
    @Test
    void isAFullCommitId()
    {
        assertTrue(UpdateManager.PINNED_CIPHER_COMMIT.matches("[0-9a-f]{40}"));
    }

    @Test
    void matchesTheSetupScript() throws Exception
    {
        String script = Files.readString(Path.of("scripts", "windows", "setup-cipher.ps1"), StandardCharsets.UTF_8);
        assertTrue(script.contains("$CipherCommit = \"" + UpdateManager.PINNED_CIPHER_COMMIT + "\""),
                "setup-cipher.ps1 must pin the same yt-cipher commit as UpdateManager.PINNED_CIPHER_COMMIT");
    }
}
