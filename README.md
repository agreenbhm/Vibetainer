# Portainer Android App

This sample Android application demonstrates how to connect to a Portainer server via its HTTP API and list Docker Swarm nodes. It uses Retrofit for networking and is structured as a basic Android project.

## Building

Ensure the Android SDK is installed, `ANDROID_HOME` is configured, and Gradle is available on your PATH, then run:

```
gradle assembleDebug
```

## Configuration

By default the app points to `http://localhost:9000/`. Update the base URL or credentials in `MainActivity` for your environment.
