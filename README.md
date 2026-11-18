# JARVIS Android (Kotlin)

A native Android starter project for a personal AI assistant.

## Features
- Kotlin + Jetpack Compose holographic-style UI
- Gemini chat using the REST `generateContent` endpoint
- Local real-time clock and date
- Android speech recognition intent and TextToSpeech replies
- AI Horde community image generation and save to `Pictures/JARVIS`
- Steins;Gate-inspired eccentric mad-scientist personality prompt

## Build
1. Install Android Studio on a computer.
2. Open this project folder in Android Studio.
3. Let Gradle sync. Use JDK 17.
4. Connect an Android phone with USB debugging enabled, or use an emulator.
5. Select Build > Build Bundle(s) / APK(s) > Build APK(s).
6. The debug APK is normally at `app/build/outputs/apk/debug/app-debug.apk`.

## Configure Gemini
1. Launch JARVIS.
2. Tap SETUP.
3. Paste a newly created Gemini API key.
4. Save and send a message.

Do not put an API key in source code or publish an APK containing your personal key.
This starter stores the key in the app's private SharedPreferences. That is suitable
only for a personal prototype, not a public release. For a production app, use a
backend proxy and proper user authentication.

## Image generation
Images use the community-run AI Horde API with its anonymous key. Availability,
queue time, worker capacity, and acceptable-use limits are not guaranteed. This is
not an unlimited guaranteed-free service.

## Notes
- Speech recognition uses the Android recognition service available on the device.
- TextToSpeech uses the Android system TTS engine.
- This is a starter project; it has not been compiled or tested on your specific phone.
