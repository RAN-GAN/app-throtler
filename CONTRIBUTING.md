# Contributing

Thanks for your interest in App Throttler.

## Getting started

Build and test instructions are in [README.md](README.md); the code layout and design are in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## Pull requests

- Open an issue first for larger changes, so we can agree on the approach.
- Keep a pull request to one change, and add or update unit tests for packet-handling and
  throttling logic (`app/src/test`).
- Follow the existing Kotlin style (`kotlin.code.style=official`).
- Make sure the tests pass; CI runs them on every pull request.
- By contributing you agree that your work is licensed under the GNU General Public License v3.0.

## Reporting bugs

Include the Android version and device, what you expected, what happened, and the contents of
the Logs tab (or `adb logcat -s AppThrottler`). Remove any hostnames or app names you do not
want to share.

## Security

See [SECURITY.md](SECURITY.md).
