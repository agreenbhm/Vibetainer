# Vibetainer

This Android app is for managing a Docker swarm or standalone environment with Portainer.

## Building

Ensure the Android SDK is installed, `ANDROID_HOME` is configured, and Gradle is available on your PATH, then run:

```
gradle assembleDebug
```

## Configuration

Set the app's URL to your Portainer instance.  If you have multiple "environments" within Portainer, you will be able to choose the correct one after logging in.  If you need to disable SSL verification, there is an option within the settings screen.


