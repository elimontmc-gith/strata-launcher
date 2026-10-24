@echo off
set DIR=%~dp0
java %JAVA_OPTS% %GRADLE_OPTS% -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" -Dorg.gradle.appname=gradlew org.gradle.wrapper.GradleWrapperMain %*
