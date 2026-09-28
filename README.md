# Baton

Baton is an independent, unofficial Android controller for Sonos devices on a trusted local network. It is phone-only and direct-LAN: no backend and no remote API authentication are included.

## Requirements

- JDK 17+
- Android SDK 37
- Android Gradle Plugin and Kotlin/Compose plugin versions declared in the root build files

Configure the Android SDK through `ANDROID_HOME`/`ANDROID_SDK_ROOT`, or use an ignored `local.properties` file.

## Build and test

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The app discovers Sonos devices on the local network and offers rooms, playback and queue controls, grouping, sound and room settings, inferred bonded products, and guided speaker setup where the device protocol permits it. Device and product identification can be inferred from topology and is not a verified physical inventory.

This project does not guarantee eARC, HDMI, cable, firmware, calibration, or other physical/vendor-app repair. Direct-LAN control assumes a trusted network; hostile devices on that network may be able to control speakers. Do not expose device control ports to the internet.

## Privacy

Baton discovers and controls speakers over the local network. It reads and displays room names, device identifiers, playback metadata, and speaker-hosted artwork. Network and multicast permissions are needed for discovery and control; cleartext HTTP is used for Sonos local control. Baton has no backend, telemetry, or account sign-in. It saves the selected quick-connect device identifier locally on the phone; Android backup is disabled. Do not publish real room names, device IDs, IP addresses, or playback captures when filing issues.

No APKs, signing secrets, keys, local paths, or personal setup data are shipped. Tests use synthetic devices and rooms. Product renders are intentionally omitted from this public source repository because their redistribution license was not verified; the UI uses a neutral model-name text fallback instead. UI screenshot tests generate ignored local build artifacts rather than publishing household captures.

Project licensing has not yet been selected. The Gradle wrapper is separately licensed under the notices in `THIRD_PARTY_NOTICES.md`.
