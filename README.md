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

## External dependencies

Versions are defined in [gradle/libs.versions.toml](gradle/libs.versions.toml).

### Runtime

- androidx.core:core-ktx 1.15.0
- androidx.lifecycle:lifecycle-runtime-ktx 2.8.7
- androidx.activity:activity-compose 1.10.0
- androidx.media:media 1.7.1
- com.google.android.material:material 1.10.0
- androidx.compose:compose-bom 2024.12.01 (platform)
- androidx.compose.ui:ui (from Compose BOM)
- androidx.compose.ui:ui-graphics (from Compose BOM)
- androidx.compose.ui:ui-tooling-preview (from Compose BOM)
- androidx.compose.material3:material3 (from Compose BOM)
- androidx.lifecycle:lifecycle-runtime-compose 2.8.7
- androidx.lifecycle:lifecycle-viewmodel-compose 2.8.7
- androidx.compose.material:material-icons-extended (from Compose BOM)
- com.google.android.gms:play-services-location 21.3.0
- androidx.navigation:navigation-fragment-ktx 2.9.8
- androidx.navigation:navigation-ui-ktx 2.9.8
- androidx.exifinterface:exifinterface 1.3.7
- org.jetbrains.kotlinx:kotlinx-collections-immutable 0.3.8
- androidx.core:core-google-shortcuts 1.1.0
- com.meta.wearable:mwdat-core 0.7.0
- com.meta.wearable:mwdat-camera 0.7.0
- com.meta.wearable:mwdat-display 0.7.0
- com.meta.wearable:mwdat-mockdevice 0.7.0
- com.auth0.android:auth0 3.14.0
- org.jetbrains.kotlinx:kotlinx-coroutines-play-services 1.10.1

### Test

- junit:junit 4.13.2
- androidx.test.ext:junit 1.2.1
- androidx.test.espresso:espresso-core 3.6.1
- androidx.compose.ui:ui-test-junit4 (from Compose BOM)
- com.google.assistant.appactions:testing 1.0.0
- androidx.test.uiautomator:uiautomator 2.3.0
- androidx.test:rules 1.6.1
- androidx.compose.ui:ui-tooling (from Compose BOM, debug)
- androidx.compose.ui:ui-test-manifest (from Compose BOM, debug)

### Build plugins

- com.android.application 9.1.1
- org.jetbrains.kotlin.android 2.2.10
- org.jetbrains.kotlin.plugin.compose 2.2.10
- org.gradle.toolchains.foojay-resolver-convention 1.0.0

## Notable code locations

- Wearables stream setup and lifecycle: [app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/WearablesManager.kt](app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/WearablesManager.kt)
- Capture and upload orchestration, media button handling: [app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/HomeFragment.kt](app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/HomeFragment.kt)
- Video encoding and muxing: [app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/VideoFileEncoder.kt](app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/VideoFileEncoder.kt)
- Audio recording: [app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/GlassesAudioRecorder.kt](app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/GlassesAudioRecorder.kt)
- Encryption and upload logic: [app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/VideoRepository.kt](app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/VideoRepository.kt)
- Auth0 login flow: [app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/LoginFragment.kt](app/src/main/java/com/unisg/hands_free_incident_report_smartglasses/LoginFragment.kt)