# hands-free-incident-report-smartglasses

Android app for capturing incident reports with Meta smart glasses and uploading encrypted video with location and orientation metadata.

## What this app does

- Records video frames from Meta Wearables DAT camera stream and encodes them to HEVC + AAC.
- Records audio from the device microphone and muxes it with video.
- Encrypts the captured video (AES) and encrypts the session key with RSA.
- Uploads the encrypted payload plus GPS and compass heading to the backend.
- Uses Auth0 for login and access-token based API calls.

## Key flows

- Login screen -> home screen navigation is defined in [app/src/main/res/navigation/nav_graph.xml](app/src/main/res/navigation/nav_graph.xml).
- On the home screen, the app listens for media button events and volume changes to start/stop a report capture.
- Capture uses the Meta Wearables DAT SDK stream session and uploads through the repository layer.

## Important configuration

- Meta Wearables DAT SDK repository and credentials are configured in [settings.gradle.kts](settings.gradle.kts). It reads `GITHUB_TOKEN` or `local.properties` key `github_token`.
- Meta Wearables app identifiers are stored in [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml).
- Auth0 settings are stored in [app/src/main/res/values/auth0.xml](app/src/main/res/values/auth0.xml).

## Permissions

Declared in [app/src/main/AndroidManifest.xml](app/src/main/AndroidManifest.xml):

- Camera, audio recording, Bluetooth scan/connect, coarse/fine location, and storage (legacy).

## Build settings

- compileSdk: 35, minSdk: 35, targetSdk: 35
- Kotlin JVM target: 17
- Compose and ViewBinding enabled

## Notable code locations

- Wearables stream setup and lifecycle: [app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/WearablesManager.kt](app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/WearablesManager.kt)
- Capture and upload orchestration, media button handling: [app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/HomeFragment.kt](app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/HomeFragment.kt)
- Video encoding and muxing: [app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/VideoFileEncoder.kt](app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/VideoFileEncoder.kt)
- Audio recording: [app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/GlassesAudioRecorder.kt](app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/GlassesAudioRecorder.kt)
- Encryption and upload logic: [app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/VideoRepository.kt](app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/VideoRepository.kt)
- Auth0 login flow: [app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/LoginFragment.kt](app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/LoginFragment.kt)