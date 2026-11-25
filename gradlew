#!/bin/sh
# DubLive Gradle wrapper bootstrap (written fresh for this project).
# 1) Use gradle-wrapper.jar when present.
# 2) Else use a system Gradle (GitHub Actions installs one via setup-gradle).
# 3) Else download the Gradle 8.5 distribution once and run it.
set -e
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -f "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" ]; then
    exec java -cp "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
elif command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    DIST_DIR="$HOME/.gradle-dublive/gradle-8.5"
    if [ ! -x "$DIST_DIR/bin/gradle" ]; then
        echo "Downloading Gradle 8.5..."
        mkdir -p "$HOME/.gradle-dublive"
        TMP_ZIP="$HOME/.gradle-dublive/gradle-8.5-bin.zip"
        if command -v curl >/dev/null 2>&1; then
            curl -L -o "$TMP_ZIP" "https://services.gradle.org/distributions/gradle-8.5-bin.zip"
        else
            wget -O "$TMP_ZIP" "https://services.gradle.org/distributions/gradle-8.5-bin.zip"
        fi
        (cd "$HOME/.gradle-dublive" && unzip -o -q "$TMP_ZIP")
    fi
    exec "$DIST_DIR/bin/gradle" "$@"
fi
