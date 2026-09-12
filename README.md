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

The app discovers Sonos devices on the local network, presents dynamic room names, supports core playback/volume controls, exposes inferred bonded products, and offers quick TV-audio connection where the device protocol permits it. Device and product identification can be inferred from topology and is not a verified physical inventory.

This project does not guarantee eARC, HDMI, cable, firmware, calibration, or other physical/vendor-app repair. Direct-LAN control assumes a trusted network; hostile devices on that network may be able to control speakers. Do not expose device control ports to the internet.

No APKs, signing secrets, keys, local paths, or personal setup data are shipped. Product renders are intentionally omitted from this public source repository because their redistribution license was not verified; the UI uses a neutral model-name text fallback instead.

Project licensing has not yet been selected. The Gradle wrapper is separately licensed under the notices in `THIRD_PARTY_NOTICES.md`.
