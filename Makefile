# `$(shell cd)` is a cmd.exe idiom: bare `cd` prints the working directory there, but under sh it
# changes to $HOME and prints nothing, leaving every path below rooted at `/`. CURDIR is make's own,
# so it is right whatever shell make picked.
REPO_ROOT := $(subst \,/,$(CURDIR))
DESKTOP_DIR := $(REPO_ROOT)/apps/desktop
ANDROID_DIR := $(REPO_ROOT)/apps/android
WINDOWS_DIR := $(REPO_ROOT)/apps/windows
DIST_DIR := $(REPO_ROOT)/dist
APK_EXPORT_DEBUG := $(DIST_DIR)/discount-screener-debug.apk
APK_EXPORT_RELEASE := $(DIST_DIR)/discount-screener-release.apk

CARGO := cargo
# Absolute, not bare: cmd.exe resolves a bare `gradlew.bat` from the working directory, sh does not.
GRADLE := $(ANDROID_DIR)/gradlew.bat
NPX := npx

.PHONY: all build test clean fmt check release run \
        desktop-build desktop-test desktop-clean desktop-fmt desktop-check desktop-release desktop-smoke desktop-run \
        android-build android-test android-clean android-release android-run android-run-qa android-signing-bootstrap apk \
        windows-run windows-dev windows-stop windows-build windows-test run-windows \
        contracts-test

run: desktop-run

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

# Live / agent QA: cold-starts profile qa (≤20 symbols) and clears app data first.
android-run-qa:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/android-run.ps1" -Qa

android-build:
	pushd "$(ANDROID_DIR)" && $(GRADLE) compileDebugKotlin && popd

android-test:
	pushd "$(ANDROID_DIR)" && $(GRADLE) test && popd

android-clean:
	pushd "$(ANDROID_DIR)" && $(GRADLE) clean && popd

android-release:
	pushd "$(ANDROID_DIR)" && $(GRADLE) :app:assembleRelease && popd
	powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '$(DIST_DIR)' | Out-Null; Copy-Item -Force '$(ANDROID_DIR)/app/build/outputs/apk/release/app-release.apk' '$(APK_EXPORT_RELEASE)'; Write-Host 'Installable release APK: $(APK_EXPORT_RELEASE)'"

android-signing-bootstrap:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/create-android-release-keystore.ps1" -UpdateLocalProperties

apk:
	pushd "$(ANDROID_DIR)" && $(GRADLE) :app:assembleDebug && popd
	powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '$(DIST_DIR)' | Out-Null; Copy-Item -Force '$(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk' '$(APK_EXPORT_DEBUG)'; Write-Host 'Installable APK: $(APK_EXPORT_DEBUG)'"

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

# ── Contracts (cross-platform) ──

contracts-test:
	$(CARGO) test --manifest-path $(DESKTOP_DIR)/Cargo.toml contract_fixture
	pushd "$(ANDROID_DIR)" && $(GRADLE) :core:test --tests com.discountscreener.core.contracts.ContractFixtureTest && popd
