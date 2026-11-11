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
package com.jagrosh.jmusicbot.gui.web;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

/**
 * Switches the native Windows title bar between light and dark.
 *
 * <p>JavaFX does not expose the window handle, so the window is found by its title and the
 * DWM immersive-dark-mode attribute is set on it. On other platforms this is a no-op.
 *
 * @author Arif Banai (arif-banai)
 */
final class WindowsTitleBar
{
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19;

    private interface User32 extends Library
    {
        Pointer FindWindowA(String className, String windowName);
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
            Pointer hwnd = user32.FindWindowA(null, title);
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
}
