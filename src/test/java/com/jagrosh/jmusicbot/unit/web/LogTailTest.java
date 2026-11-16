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
package com.jagrosh.jmusicbot.unit.web;

import com.jagrosh.jmusicbot.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log poll reads only the end of the file and returns whole lines.
 */
class LogTailTest
{
    @TempDir
    Path dir;

    @Test
    void returnsLastLinesOfSmallFile() throws Exception
    {
        Path log = dir.resolve("a.log");
        Files.writeString(log, "one\r\ntwo\r\nthree\r\n");
        assertEquals(List.of("two", "three"), WebServer.tailLines(log, 2));
        assertEquals(List.of("one", "two", "three"), WebServer.tailLines(log, 10));
    }

    @Test
    void emptyFileHasNoLines() throws Exception
    {
        Path log = dir.resolve("empty.log");
        Files.writeString(log, "");
        assertTrue(WebServer.tailLines(log, 5).isEmpty());
    }

    @Test
    void dropsPartialFirstLineOfLargeFile() throws Exception
    {
        Path log = dir.resolve("big.log");
        StringBuilder text = new StringBuilder();
        int count = 0;
        while (text.length() < WebServer.LOG_TAIL_BYTES * 2)
            text.append("line-").append(count++).append('\n');
        Files.writeString(log, text);

        List<String> tail = WebServer.tailLines(log, 3);
        assertEquals(List.of("line-" + (count - 3), "line-" + (count - 2), "line-" + (count - 1)), tail);

        List<String> all = WebServer.tailLines(log, Integer.MAX_VALUE);
        assertTrue(all.stream().allMatch(line -> line.matches("line-\\d+")));
    }
}
