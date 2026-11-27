#!/bin/sh
# Starts JMusicBot under systemd (or any supervisor that restarts it when it exits non-zero).
#
# The in-app updater swaps JMusicBot.jar, keeps the previous jar as JMusicBot.jar.old and exits
# so the service manager starts the new one. When the new jar has not reported ready after two
# starts, this script puts the old jar back, so a broken release cannot keep the bot down.
#
# Environment:
#   JMB_JAR    jar to run, relative to this folder (default JMusicBot.jar)
#   JAVA       java executable (default: java on PATH)
#   JAVA_OPTS  JVM options (default: UTF-8 file encoding, headless, native access)
#
# The bot refreshes this file from each new release, so do not edit it; set the variables in
# the service unit instead.

cd "$(dirname "$0")" || exit 1

JAR="${JMB_JAR:-JMusicBot.jar}"
UPDATE=update

if [ -f "$UPDATE/pending" ] && [ ! -f "$UPDATE/ok" ]; then
    attempts=$(cat "$UPDATE/attempts" 2>/dev/null || echo 0)
    case "$attempts" in ''|*[!0-9]*) attempts=0 ;; esac
    attempts=$((attempts + 1))
    echo "$attempts" > "$UPDATE/attempts"
    if [ "$attempts" -gt 2 ] && [ -f "$JAR.old" ]; then
        version=$(cat "$UPDATE/pending")
        mv -f "$JAR.old" "$JAR"
        echo "rolled back: $version did not start" > "$UPDATE/result.txt"
        rm -f "$UPDATE/pending" "$UPDATE/attempts" "$UPDATE/started"
        echo "JMusicBot $version did not start twice, restored the previous jar" >&2
    fi
fi

# JAVA_OPTS is split into words on purpose.
# shellcheck disable=SC2086
exec "${JAVA:-java}" ${JAVA_OPTS:--Dfile.encoding=UTF-8 -Dnogui=true --enable-native-access=ALL-UNNAMED} -jar "$JAR" "$@"
