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

import com.jagrosh.jmusicbot.web.LocalRequestGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code Host} / {@code Origin} checks that keep other origins out of the local API.
 */
class LocalRequestGuardTest
{
    private static final int PORT = 54321;

    @Test
    void acceptsLoopbackHostWithoutOrigin()
    {
        assertTrue(LocalRequestGuard.allows("127.0.0.1:54321", null, PORT));
        assertTrue(LocalRequestGuard.allows("localhost:54321", null, PORT));
        assertTrue(LocalRequestGuard.allows("[::1]:54321", null, PORT));
    }

    @Test
    void acceptsLoopbackHostWithoutPort()
    {
        assertTrue(LocalRequestGuard.allows("localhost", null, PORT));
        assertTrue(LocalRequestGuard.allows("127.0.0.1", null, PORT));
    }

    @Test
    void rejectsMissingOrForeignHost()
    {
        assertFalse(LocalRequestGuard.allows(null, null, PORT));
        assertFalse(LocalRequestGuard.allows("", null, PORT));
        assertFalse(LocalRequestGuard.allows("   ", null, PORT));
        assertFalse(LocalRequestGuard.allows("192.168.1.20:54321", null, PORT));
        assertFalse(LocalRequestGuard.allows("evil.com:54321", null, PORT));
        assertFalse(LocalRequestGuard.allows("127.0.0.1.evil.com:54321", null, PORT));
    }

    @Test
    void rejectsUserInfoAndMalformedPorts()
    {
        assertFalse(LocalRequestGuard.allows("user@127.0.0.1:54321", null, PORT));
        assertFalse(LocalRequestGuard.allows("127.0.0.1:notaport", null, PORT));
        assertFalse(LocalRequestGuard.allows("[::1:54321", null, PORT));
        assertFalse(LocalRequestGuard.allows("127.0.0.1:54321:54321", null, PORT));
    }

    @Test
    void rejectsAnotherPort()
    {
        assertFalse(LocalRequestGuard.allows("127.0.0.1:1234", null, PORT));
        assertFalse(LocalRequestGuard.allows("localhost:1234", null, PORT));
        assertFalse(LocalRequestGuard.allows("[::1]:1234", null, PORT));
    }

    @Test
    void acceptsMatchingOrigin()
    {
        assertTrue(LocalRequestGuard.allows("127.0.0.1:54321", "http://127.0.0.1:54321", PORT));
        assertTrue(LocalRequestGuard.allows("localhost:54321", "http://localhost:54321", PORT));
        assertTrue(LocalRequestGuard.allows("[::1]:54321", "http://[::1]:54321", PORT));
        assertTrue(LocalRequestGuard.allows("localhost:54321", "https://localhost:54321", PORT));
    }

    @Test
    void rejectsForeignOrigin()
    {
        assertFalse(LocalRequestGuard.allows("127.0.0.1:54321", "http://evil.com", PORT));
        assertFalse(LocalRequestGuard.allows(
                "127.0.0.1:54321", "http://127.0.0.1:54321.evil.com", PORT));
        assertFalse(LocalRequestGuard.allows("127.0.0.1:54321", "null", PORT));
        assertFalse(LocalRequestGuard.allows("127.0.0.1:54321", "not a url", PORT));
        assertFalse(LocalRequestGuard.allows("127.0.0.1:54321", "file://127.0.0.1", PORT));
    }

    @Test
    void rejectsOriginOnAnotherPort()
    {
        assertFalse(LocalRequestGuard.allows("127.0.0.1:54321", "http://127.0.0.1:1234", PORT));
    }

    @Test
    void skipsPortComparisonWhenPortIsUnknown()
    {
        assertTrue(LocalRequestGuard.allows("127.0.0.1:1234", null, 0));
        assertTrue(LocalRequestGuard.allows("127.0.0.1:1234", "http://127.0.0.1:1234", 0));
    }
}
