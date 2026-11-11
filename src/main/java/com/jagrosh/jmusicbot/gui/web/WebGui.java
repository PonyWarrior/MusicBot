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

import com.jagrosh.jmusicbot.Bot;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A JavaFX window that renders the Astryx web UI in a WebView.
 *
 * <p>The window owns the bot: closing it calls {@link Bot#shutdown()}, which stops the cipher
 * service and any running download before the JVM exits.
 *
 * @author Arif Banai (arif-banai)
 */
public final class WebGui
{
    private static final Logger LOG = LoggerFactory.getLogger(WebGui.class);

    private static Stage stage;

    private WebGui()
    {
    }

    /**
     * Opens the window and loads the given url.
     *
     * @param bot the bot the window belongs to
     * @param url the local web UI url
     */
    public static void start(Bot bot, String url)
    {
        Runnable show = () ->
        {
            try
            {
                WebView view = new WebView();
                view.getEngine().load(url);

                stage = new Stage();
                stage.setTitle("JMusicBot");
                stage.setScene(new Scene(view, 1280, 860));
                stage.setOnCloseRequest(event -> bot.shutdown());
                stage.show();
                LOG.info("Web GUI window opened at {}", url);
            }
            catch (Throwable t)
            {
                LOG.error("Could not open the web GUI window", t);
            }
        };

        try
        {
            Platform.startup(show);
        }
        catch (IllegalStateException alreadyStarted)
        {
            Platform.runLater(show);
        }
    }

    /**
     * Closes the window, if it is open.
     */
    public static void close()
    {
        if (stage != null)
        {
            Platform.runLater(() -> stage.close());
        }
    }
}
