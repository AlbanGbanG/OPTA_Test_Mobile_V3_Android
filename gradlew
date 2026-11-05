#!/bin/sh
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
JAVA_CMD=${JAVA_HOME:+$JAVA_HOME/bin/}java
exec "$JAVA_CMD" -Dwrapper.appHome="$APP_HOME" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
