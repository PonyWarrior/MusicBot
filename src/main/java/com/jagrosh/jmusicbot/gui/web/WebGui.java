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
import com.jagrosh.jmusicbot.utils.OtherUtil;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

                // Start the native title bar in the saved theme instead of waiting for the poll.
                final boolean[] lastDark = {"dark".equals(savedTheme())};

                stage = new Stage();
                stage.setTitle("JMusicBot");
                stage.setScene(new Scene(view, 1280, 860));
                stage.setOnCloseRequest(event -> bot.shutdown());
                stage.show();
                WindowsTitleBar.setDark("JMusicBot", lastDark[0]);
                LOG.info("Web GUI window opened at {}", url);

                // The page also seeds its theme from the OS on a first run, so resync on load.
                view.getEngine().getLoadWorker().stateProperty().addListener(
                        (observable, oldState, newState) ->
                        {
                            if (newState == Worker.State.SUCCEEDED)
                                syncTitleBar(view, lastDark);
                        });

                // Keep the native title bar in step with the web UI's light/dark mode.
                Timeline themeWatcher = new Timeline(new KeyFrame(Duration.seconds(1),
                        event -> syncTitleBar(view, lastDark)));
                themeWatcher.setCycleCount(Timeline.INDEFINITE);
                themeWatcher.play();
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
     * @return the theme saved by the web UI, or null when the user has never picked one
     */
    private static String savedTheme()
    {
        Path file = OtherUtil.getPath("gui-theme.txt");
        if (!Files.isRegularFile(file))
            return null;
        try
        {
            return Files.readString(file, StandardCharsets.UTF_8).trim();
        }
        catch (IOException e)
        {
            return null;
        }
    }

    /**
     * Moves the title bar to the theme the page is currently showing.
     *
     * @param view the web view to read the theme from
     * @param lastDark the last applied value, kept so the title bar is only touched on change
     */
    private static void syncTitleBar(WebView view, boolean[] lastDark)
    {
        try
        {
            Object theme = view.getEngine().executeScript(
                    "document.documentElement.getAttribute('data-theme')");
            boolean dark = "dark".equals(String.valueOf(theme));
            if (dark != lastDark[0])
            {
                lastDark[0] = dark;
                WindowsTitleBar.setDark("JMusicBot", dark);
            }
        }
        catch (Exception ignored)
        {
            // The page is not ready yet.
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
