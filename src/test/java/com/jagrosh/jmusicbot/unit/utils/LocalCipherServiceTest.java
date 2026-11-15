package com.jagrosh.jmusicbot.unit.utils;

import com.jagrosh.jmusicbot.utils.LocalCipherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service stops a leftover process on its port only when that process runs the configured
 * command, so an unrelated program on the same port is never killed.
 */
@DisplayName("Local cipher service")
class LocalCipherServiceTest
{
    @Test
    @DisplayName("the same program is recognised whatever its folder, case or suffix")
    void recognisesTheSameProgram()
    {
        assertTrue(LocalCipherService.sameProgram("C:\\Tools\\deno\\deno.exe", "deno"));
        assertTrue(LocalCipherService.sameProgram("C:\\Tools\\deno\\DENO.EXE", "deno.exe"));
        assertTrue(LocalCipherService.sameProgram("/usr/local/bin/deno", "deno"));
        assertTrue(LocalCipherService.sameProgram("C:\\Tools\\deno\\deno.exe", "C:/Other/deno.exe"));
    }

    @Test
    @DisplayName("another program is not ours")
    void rejectsAnotherProgram()
    {
        assertFalse(LocalCipherService.sameProgram("C:\\Program Files\\nodejs\\node.exe", "deno"));
        assertFalse(LocalCipherService.sameProgram("C:\\Tools\\deno2.exe", "deno"));
        assertFalse(LocalCipherService.sameProgram("", "deno"));
        assertFalse(LocalCipherService.sameProgram(null, "deno"));
        assertFalse(LocalCipherService.sameProgram("C:\\Tools\\deno.exe", null));
    }
}
