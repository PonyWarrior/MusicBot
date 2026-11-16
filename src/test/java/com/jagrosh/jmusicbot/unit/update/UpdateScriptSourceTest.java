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
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The jar-swap script must come from the new jar, so a script fix applies to the update that ships it.
 */
class UpdateScriptSourceTest
{
    @TempDir
    Path dir;

    @Test
    void takesScriptFromNewJar() throws Exception
    {
        Path jar = dir.resolve("new.jar");
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file))
        {
            zip.putNextEntry(new ZipEntry("update/apply-update.ps1"));
            zip.write("# new script".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path script = dir.resolve("apply-update.ps1");

        assertTrue(UpdateManager.writeUpdateScript(jar, script));
        assertEquals("# new script", Files.readString(script));
    }

    @Test
    void fallsBackToCurrentScriptWhenNewJarHasNone() throws Exception
    {
        Path jar = dir.resolve("new.jar");
        try (OutputStream file = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(file))
        {
            zip.putNextEntry(new ZipEntry("other.txt"));
            zip.closeEntry();
        }
        Path script = dir.resolve("apply-update.ps1");

        assertFalse(UpdateManager.writeUpdateScript(jar, script));
        assertTrue(Files.readString(script).contains("JMB_PID"));
    }

    @Test
    void fallsBackWhenNewJarIsUnreadable() throws Exception
    {
        Path jar = dir.resolve("broken.jar");
        Files.writeString(jar, "not a zip");
        Path script = dir.resolve("apply-update.ps1");

        assertFalse(UpdateManager.writeUpdateScript(jar, script));
        assertTrue(Files.readString(script).contains("JMB_PID"));
    }
}
