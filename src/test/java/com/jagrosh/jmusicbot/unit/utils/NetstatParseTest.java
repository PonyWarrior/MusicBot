package com.jagrosh.jmusicbot.unit.utils;

import com.jagrosh.jmusicbot.utils.LocalCipherService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The netstat state column is translated on localized Windows, so listening rows must be found
 * without it.
 */
class NetstatParseTest
{
    private static final String ENGLISH = """

            Active Connections

              Proto  Local Address          Foreign Address        State           PID
              TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       1100
              TCP    127.0.0.1:8001         0.0.0.0:0              LISTENING       4242
              TCP    127.0.0.1:8001         127.0.0.1:50123        ESTABLISHED     4242
              TCP    127.0.0.1:50123        127.0.0.1:8001         ESTABLISHED     777
              TCP    127.0.0.1:80010        0.0.0.0:0              LISTENING       999
              TCP    [::1]:8001             [::]:0                 LISTENING       4242
            """;

    private static final String FRENCH = """

            Connexions actives

              Proto  Adresse locale         Adresse distante       État           PID
              TCP    127.0.0.1:8001         0.0.0.0:0              ÉCOUTE          5150
              TCP    127.0.0.1:8001         127.0.0.1:50123        ÉTABLIE         5150
            """;

    @Test
    void findsListenersInEnglishOutput()
    {
        assertEquals(List.of(4242), LocalCipherService.parseListeningPids(ENGLISH, 8001));
    }

    @Test
    void findsListenersInLocalizedOutput()
    {
        assertEquals(List.of(5150), LocalCipherService.parseListeningPids(FRENCH, 8001));
    }

    @Test
    void ignoresOtherPortsAndClientSides()
    {
        assertEquals(List.of(), LocalCipherService.parseListeningPids(ENGLISH, 50123));
        assertEquals(List.of(), LocalCipherService.parseListeningPids(ENGLISH, 800));
        assertEquals(List.of(), LocalCipherService.parseListeningPids("", 8001));
    }
}
