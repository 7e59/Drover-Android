# Contributing

Keep the main screen simple and preserve the Discord-only routing scope. Explain the behavior changed and run `./gradlew assembleDebug testDebugUnitTest lintDebug` before submitting a pull request.

For networking changes, include meaningful transport/lifecycle evidence. JVM tests do not prove Android VPN lifecycle behavior or successful Discord audio. Test Start/Stop, notification Stop, revoke, network switching and bidirectional audio on a real device when relevant.

Do not commit signing keys, local.properties, account information, packet payloads or unredacted diagnostic logs. Native dependency updates must record revisions and retain license notices.
