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
package com.jagrosh.jmusicbot.testutil;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.MessageComponentTree;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads a Components V2 message back as text and controls, for assertions.
 */
public final class V2Messages
{
    private V2Messages()
    {
    }

    /** All text displays in the message, joined with newlines. */
    public static String text(Collection<? extends MessageTopLevelComponent> components)
    {
        return MessageComponentTree.of(components).findAll(TextDisplay.class).stream()
                .map(TextDisplay::getContent)
                .collect(Collectors.joining("\n"));
    }

    /** All action rows in the message, including those nested in containers. */
    public static List<ActionRow> rows(Collection<? extends MessageTopLevelComponent> components)
    {
        return MessageComponentTree.of(components).findAll(ActionRow.class);
    }

    /** All buttons in the message. */
    public static List<Button> buttons(Collection<? extends MessageTopLevelComponent> components)
    {
        return MessageComponentTree.of(components).findAll(Button.class);
    }
}
