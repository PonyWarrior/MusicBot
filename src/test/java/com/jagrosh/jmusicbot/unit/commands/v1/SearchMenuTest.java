package com.jagrosh.jmusicbot.unit.commands.v1;

import com.jagrosh.jmusicbot.commands.v1.SearchMenu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the number parsing of the search menu.
 *
 * <p>The value that is parsed is either the name of a keycap reaction, whose name starts with the
 * digit, or the raw content of a message that the user typed.
 */
@DisplayName("Search menu")
class SearchMenuTest
{
    /**
     * Calls the package private number parser of the menu.
     */
    private static int number(String value, int count) throws Exception
    {
        Method method = SearchMenu.class.getDeclaredMethod("number", String.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(null, value, count);
    }
    /** The keycap reaction for the given number, as Discord reports it. */
    private static String keycap(int number)
    {
        // The name is the digit followed by the combining enclosing keycap, without a
        // variation selector.
        return number + "\u20E3";
    }

    @Nested
    @DisplayName("Reaction names")
    class ReactionNames
    {
        @Test
        @DisplayName("A keycap reaction is understood")
        void keycapIsUnderstood() throws Exception
        {
            assertEquals(0, number(keycap(1), 5));
            assertEquals(1, number(keycap(2), 5));
            assertEquals(4, number(keycap(5), 5));
        }

        @Test
        @DisplayName("A keycap above the number of results is ignored")
        void keycapAboveResultsIsIgnored() throws Exception
        {
            assertEquals(Integer.MIN_VALUE, number(keycap(4), 3));
        }
    }

    @Nested
    @DisplayName("Typed input")
    class TypedInput
    {
        @Test
        @DisplayName("A typed number is understood")
        void typedNumberIsUnderstood() throws Exception
        {
            assertEquals(0, number("1", 5));
            assertEquals(2, number("3", 5));
            assertEquals(2, number(" 3 ", 5), "surrounding space should be ignored");
        }

        @Test
        @DisplayName("A number above the number of results is ignored")
        void numberAboveResultsIsIgnored() throws Exception
        {
            assertEquals(Integer.MIN_VALUE, number("9", 3));
            assertEquals(Integer.MIN_VALUE, number("0", 3));
        }

        @Test
        @DisplayName("Text that is not a number is ignored")
        void textIsIgnored() throws Exception
        {
            assertEquals(Integer.MIN_VALUE, number("hello", 5));
            assertEquals(Integer.MIN_VALUE, number("", 5));
            assertEquals(Integer.MIN_VALUE, number("   ", 5));
            assertEquals(Integer.MIN_VALUE, number(null, 5));
            assertEquals(Integer.MIN_VALUE, number("12", 5));
            assertEquals(Integer.MIN_VALUE, number("1st", 5));
        }
    }
}
