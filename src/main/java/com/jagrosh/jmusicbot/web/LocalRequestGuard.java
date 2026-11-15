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
package com.jagrosh.jmusicbot.web;

import java.net.URI;

/**
 * Checks that a request was addressed to the loopback web GUI that this process is serving.
 *
 * <p>The server binds to {@code 127.0.0.1}, but that alone does not stop a web page from
 * reaching it: DNS rebinding makes an attacker's origin resolve to {@code 127.0.0.1}, so the
 * browser sends the request with the attacker's {@code Host} and {@code Origin}. Requiring both
 * headers to name this loopback endpoint closes that hole without any tokens or setup.
 *
 * @author Arif Banai (arif-banai)
 */
public final class LocalRequestGuard
{
    private LocalRequestGuard()
    {
    }

    /**
     * Checks the {@code Host} and {@code Origin} headers of one request.
     *
     * @param hostHeader the {@code Host} header, may be null
     * @param originHeader the {@code Origin} header, may be null for same-origin navigations
     * @param port the port the server is listening on; zero skips the port comparison
     * @return true when the request names this loopback endpoint
     */
    public static boolean allows(String hostHeader, String originHeader, int port)
    {
        return isLoopbackHost(hostHeader, port)
                && (originHeader == null || isLoopbackOrigin(originHeader, port));
    }

    /**
     * Checks a {@code Host} header such as {@code 127.0.0.1:8080}, {@code localhost} or
     * {@code [::1]:8080}.
     *
     * @param hostHeader the header value, may be null
     * @param port the expected port, or zero to accept any port
     * @return true when the header names a loopback host and (when given) the expected port
     */
    static boolean isLoopbackHost(String hostHeader, int port)
    {
        if (hostHeader == null || hostHeader.isBlank())
            return false;

        String value = hostHeader.trim();
        if (value.indexOf('@') >= 0)
            return false;

        String host;
        int hostPort = -1;
        if (value.startsWith("["))
        {
            int end = value.indexOf(']');
            if (end < 0)
                return false;
            host = value.substring(1, end);
            String rest = value.substring(end + 1);
            if (!rest.isEmpty())
            {
                if (!rest.startsWith(":"))
                    return false;
                hostPort = parsePort(rest.substring(1));
                if (hostPort < 0)
                    return false;
            }
        }
        else
        {
            int colon = value.lastIndexOf(':');
            if (colon >= 0 && value.indexOf(':') == colon)
            {
                host = value.substring(0, colon);
                hostPort = parsePort(value.substring(colon + 1));
                if (hostPort < 0)
                    return false;
            }
            else
            {
                host = value;
            }
        }

        if (!isLoopbackName(host))
            return false;
        return port <= 0 || hostPort < 0 || hostPort == port;
    }

    /**
     * Checks an {@code Origin} header such as {@code http://127.0.0.1:8080}.
     *
     * @param originHeader the header value, may be null
     * @param port the expected port, or zero to accept any port
     * @return true when the origin names a loopback host and (when given) the expected port
     */
    static boolean isLoopbackOrigin(String originHeader, int port)
    {
        if (originHeader == null)
            return true;
        String value = originHeader.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value))
            return false;

        URI uri;
        try
        {
            uri = URI.create(value);
        }
        catch (IllegalArgumentException e)
        {
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")))
            return false;
        if (!isLoopbackName(uri.getHost()))
            return false;

        int originPort = uri.getPort();
        return port <= 0 || originPort < 0 || originPort == port;
    }

    private static boolean isLoopbackName(String host)
    {
        if (host == null)
            return false;
        String name = host.replace("[", "").replace("]", "");
        return name.equals("127.0.0.1") || name.equals("::1")
                || name.equalsIgnoreCase("localhost");
    }

    private static int parsePort(String text)
    {
        if (text.isEmpty())
            return -1;
        for (int i = 0; i < text.length(); i++)
            if (!Character.isDigit(text.charAt(i)))
                return -1;
        try
        {
            return Integer.parseInt(text);
        }
        catch (NumberFormatException e)
        {
            return -1;
        }
    }
}
