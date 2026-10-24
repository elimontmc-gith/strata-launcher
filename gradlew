#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Gradle wrapper JAR is missing: $WRAPPER_JAR" >&2
  exit 1
fi

exec java ${JAVA_OPTS:-} ${GRADLE_OPTS:-} \
  -classpath "$WRAPPER_JAR" \
  -Dorg.gradle.appname=gradlew \
  org.gradle.wrapper.GradleWrapperMain "$@"
