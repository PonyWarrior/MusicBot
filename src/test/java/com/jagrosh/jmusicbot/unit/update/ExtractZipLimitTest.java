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

import com.jagrosh.jmusicbot.update.UpdateSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An update archive that expands past the size limit is refused instead of filling the disk.
 */
class ExtractZipLimitTest
{
    @TempDir
    Path dir;

    @Test
    void extractsWithinLimit() throws Exception
    {
        Path zip = zip(4096);
        var files = UpdateSupport.extractZip(zip, dir.resolve("out"), 10_000);
        assertEquals(1, files.size());
        assertEquals(4096, Files.size(files.get(0)));
    }

    @Test
    void refusesArchiveOverLimit() throws Exception
    {
        Path zip = zip(4096);
        assertThrows(IOException.class, () -> UpdateSupport.extractZip(zip, dir.resolve("out"), 1000));
    }

    private Path zip(int size) throws IOException
    {
        Path zip = dir.resolve("test.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip)))
        {
            out.putNextEntry(new ZipEntry("bin/tool.exe"));
            out.write(new byte[size]);
            out.closeEntry();
        }
        return zip;
    }
}
