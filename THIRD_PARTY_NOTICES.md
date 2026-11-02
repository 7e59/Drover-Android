# Third-party notices

No Discord Drover code, DLL, DDetours, or custom packet is redistributed.

Native source and licenses are included under `app/src/main/jni/hev/`:

- hev-socks5-tunnel — MIT — https://github.com/heiher/hev-socks5-tunnel
- hev-socks5-core — MIT — https://github.com/heiher/hev-socks5-core
- hev-task-system — MIT — https://github.com/heiher/hev-task-system
- lwIP fork — BSD3 — https://github.com/heiher/lwip
- libyaml fork — MIT — https://github.com/heiher/yaml

Exact source revisions are in `SOURCE-PINS.txt`; full runtime notices are packaged in `app/src/main/assets/NOTICES.txt` and accessible through the app's licenses button. JNI bridge class matches upstream's registration contract. Android.mk/Application.mk outer integration files are VoiceLocal-original; upstream files retain their original licenses. Wintun is not linked into Android.

Generated Gradle Wrapper scripts/JAR are Apache2.0; license at `gradle/LICENSE`, upstream https://github.com/gradle/gradle/tree/v9.1.0 . Android SDK/NDK and build plugins retain their licenses. JUnit4.13.2 (EPL1.0) and Hamcrest (BSD) are test-only dependencies, absent from runtime APK.


Original libyaml and additional source-header attributions are preserved in [the native notice supplement](licenses/NATIVE-LICENSE-SUPPLEMENT.txt), also packaged in APK assets. The conservative inventory includes optional upstream sources; it does not claim that every listed file is linked into the Android binary.
