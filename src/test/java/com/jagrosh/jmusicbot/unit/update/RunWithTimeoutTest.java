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

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deadline must hold even when the child keeps its output open, which is what made the old
 * read-then-wait code hang.
 */
class RunWithTimeoutTest
{
    private static ProcessBuilder java(String... args)
    {
        String exe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new java.util.ArrayList<>(List.of(exe));
        command.addAll(List.of(args));
        return new ProcessBuilder(command);
    }

    @Test
    void returnsOutputAndExitCode() throws Exception
    {
        UpdateManager.ProcessOutput result = UpdateManager.runWithTimeout(
                java("-version"), 60, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(0, result.exitCode());
        // -version writes to stderr, so this also proves stderr is merged.
        assertTrue(result.output().toLowerCase().contains("version"), result.output());
    }

    @Test
    void killsAProcessThatOverrunsTheDeadline() throws Exception
    {
        long start = System.nanoTime();
        UpdateManager.ProcessOutput result = UpdateManager.runWithTimeout(
                java("-cp", System.getProperty("java.class.path"), Sleeper.class.getName()),
                2, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertNull(result);
        assertTrue(elapsedMs < 20_000, "took " + elapsedMs + " ms");
    }

    /** A child that never exits on its own. */
    public static final class Sleeper
    {
        public static void main(String[] args) throws InterruptedException
        {
            System.out.println("sleeping");
            Thread.sleep(TimeUnit.MINUTES.toMillis(10));
        }
    }
}
