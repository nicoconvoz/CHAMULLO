#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-or-later OR LicenseRef-CHAMULLO-Commercial
# Copyright (C) 2026 Jesús Nicolás Astorga y RESOURCES OPEN DOORS S.A.S
# Builds the release APK and publishes it on the download page (../Chamullo-web, GitHub Pages),
# writing version.txt so installed apps show the "new version" notice. Same flow as ICEBREAK's web page.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WEB="${CHAMULLO_WEB:-$ROOT/../Chamullo-web}"

cd "$ROOT/android"
./gradlew :core:test :app:assembleRelease -q

VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
cp app/build/outputs/apk/release/app-release.apk "$WEB/chamullo-android.apk"
printf '%s\n' "$VERSION" > "$WEB/version.txt"

cd "$WEB"
git add -A
git commit -q -m "release: CHAMULLO $VERSION"
git push -q
echo "Published CHAMULLO $VERSION"
