# `$(shell cd)` is a cmd.exe idiom: bare `cd` prints the working directory there, but under sh it
# changes to $HOME and prints nothing, leaving every path below rooted at `/`. CURDIR is make's own,
# so it is right whatever shell make picked.
REPO_ROOT := $(subst \,/,$(CURDIR))
DESKTOP_DIR := $(REPO_ROOT)/apps/desktop
ANDROID_DIR := $(REPO_ROOT)/apps/android
WINDOWS_DIR := $(REPO_ROOT)/apps/windows
FLUTTER_DIR := $(REPO_ROOT)/apps/flutter
DIST_DIR := $(REPO_ROOT)/dist

CARGO := cargo
# Absolute, not bare: cmd.exe resolves a bare `gradlew.bat` from the working directory, sh does not.
GRADLE := $(ANDROID_DIR)/gradlew.bat
NPX := npx

.PHONY: all build test clean fmt check release version run \
        desktop-build desktop-test desktop-clean desktop-fmt desktop-check desktop-release desktop-smoke desktop-run \
        android-build android-test android-clean android-release android-run android-run-qa android-signing-bootstrap apk \
        windows-run windows-dev windows-stop windows-build windows-test run-windows \
        flutter-test flutter-build-windows flutter-run-windows flutter-build-android flutter-run-android \
        contracts-test repo-check

run: desktop-run

# ── Version (date-based, computed from git state — see scripts/version.ps1) ──

version:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/version.ps1"

# Always succeeds: stamps whatever the current git state calls for (release on
# main/master, feature build otherwise; dirty gets a -dirty suffix either way).
release: android-release windows-build

# ── Desktop (Rust) ──

desktop-run:
	$(CARGO) run --manifest-path $(DESKTOP_DIR)/Cargo.toml

desktop-build:
	$(CARGO) build --manifest-path $(DESKTOP_DIR)/Cargo.toml

desktop-test:
	$(CARGO) test --manifest-path $(DESKTOP_DIR)/Cargo.toml

desktop-clean:
	$(CARGO) clean --manifest-path $(DESKTOP_DIR)/Cargo.toml

desktop-fmt:
	$(CARGO) fmt --manifest-path $(DESKTOP_DIR)/Cargo.toml -- --check

desktop-check:
	$(CARGO) check --manifest-path $(DESKTOP_DIR)/Cargo.toml

desktop-release:
	$(CARGO) build --release --manifest-path $(DESKTOP_DIR)/Cargo.toml

desktop-smoke:
	$(CARGO) run --manifest-path $(DESKTOP_DIR)/Cargo.toml -- --smoke

# ── Android (Gradle) ──

# Regular app: cold-starts the product profile (sp500) and keeps existing app data.
android-run:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/android-run.ps1"

# Live / agent QA: boots profile qa (≤20 symbols). Keeps the on-device database.
android-run-qa:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/android-run.ps1" -Qa

android-build:
	pushd "$(ANDROID_DIR)" && $(GRADLE) compileDebugKotlin && popd

android-test:
	pushd "$(ANDROID_DIR)" && $(GRADLE) test && popd

android-clean:
	pushd "$(ANDROID_DIR)" && $(GRADLE) clean && popd

android-release:
	pushd "$(ANDROID_DIR)" && $(GRADLE) :app:assembleRelease -PallowDebugSignedRelease=true && popd
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/export-android-apk.ps1" -SourceApk "$(ANDROID_DIR)/app/build/outputs/apk/release/app-release.apk" -DistDir "$(DIST_DIR)" -Kind release

android-signing-bootstrap:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/create-android-release-keystore.ps1" -UpdateLocalProperties

apk:
	pushd "$(ANDROID_DIR)" && $(GRADLE) :app:assembleDebug && popd
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/export-android-apk.ps1" -SourceApk "$(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk" -DistDir "$(DIST_DIR)" -Kind debug

# ── Windows (Tauri / Vantage) ──
# Dev launcher: Vite + Rust backend. Requires Node/npm and a working Tauri toolchain.

run-windows: windows-run

# Kill prior Vite (5173) + Vantage window so re-runs do not fail with "port already in use".
windows-stop:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/stop-windows-dev.ps1"

windows-run windows-dev: windows-stop
	pushd "$(WINDOWS_DIR)" && $(NPX) tauri dev && popd

windows-build:
	pushd "$(WINDOWS_DIR)" && $(NPX) tauri build && popd

windows-test:
	$(CARGO) test --manifest-path $(WINDOWS_DIR)/src-tauri/Cargo.toml

# ── Flutter ──

flutter-test:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/validate-flutter.ps1"

flutter-build-windows:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/build-flutter-windows.ps1"

flutter-run-windows:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/build-flutter-windows.ps1" -Run

flutter-build-android:
	pushd "$(FLUTTER_DIR)" && flutter build apk --debug && popd
	powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '$(DIST_DIR)' | Out-Null; Copy-Item -Force '$(FLUTTER_DIR)/build/app/outputs/flutter-apk/app-debug.apk' '$(DIST_DIR)/discount-screener-flutter-debug.apk'"

flutter-run-android:
	pushd "$(FLUTTER_DIR)" && flutter run && popd

# ── Contracts (cross-platform) ──

contracts-test:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/validate-contracts.ps1"

# ── Repository structure ──

repo-check:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/check-repo-structure.ps1"
