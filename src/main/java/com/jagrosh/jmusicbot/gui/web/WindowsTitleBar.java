package com.jagrosh.jmusicbot.gui.web;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;

/**
 * Switches the native Windows title bar between light and dark.
 *
 * <p>JavaFX does not expose the window handle, so the window is found by its title and owning
 * process, and the
 * DWM immersive-dark-mode attribute is set on it. On other platforms this is a no-op.
 */
final class WindowsTitleBar
{
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19;

    private interface User32 extends Library
    {
        Pointer FindWindowExW(Pointer parent, Pointer childAfter, WString className, WString windowName);

        int GetWindowThreadProcessId(Pointer hwnd, IntByReference processId);
    }

    private interface Dwmapi extends Library
    {
        int DwmSetWindowAttribute(Pointer hwnd, int attribute, Pointer value, int size);
    }

    private WindowsTitleBar()
    {
    }

    /**
     * @param title the window title
     * @param dark true for a dark title bar
     */
    static void setDark(String title, boolean dark)
    {
        if (!Platform.isWindows())
            return;
        try
        {
            User32 user32 = (User32) Native.loadLibrary("user32", User32.class);
            Pointer hwnd = findOwnWindow(user32, title);
            if (hwnd == null)
                return;

            Dwmapi dwmapi = (Dwmapi) Native.loadLibrary("dwmapi", Dwmapi.class);
            IntByReference value = new IntByReference(dark ? 1 : 0);
            int result = dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE,
                    value.getPointer(), 4);
            if (result != 0)
            {
                dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD,
                        value.getPointer(), 4);
            }
        }
        catch (Throwable ignored)
        {
            // The title bar stays in the system style.
        }
    }

    /**
     * Finds the top-level window with this title that belongs to this process. Another program
     * (a browser tab, an Explorer folder) can show the same title, so the owner is checked.
     */
    private static Pointer findOwnWindow(User32 user32, String title)
    {
        long self = ProcessHandle.current().pid();
        WString name = new WString(title);
        Pointer hwnd = null;
        for (int i = 0; i < 64; i++)
        {
            hwnd = user32.FindWindowExW(null, hwnd, null, name);
            if (hwnd == null)
                return null;
            IntByReference owner = new IntByReference();
            user32.GetWindowThreadProcessId(hwnd, owner);
            if (Integer.toUnsignedLong(owner.getValue()) == self)
                return hwnd;
        }
        return null;
    }
}
