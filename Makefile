REPO_ROOT := $(subst \,/,$(shell cd))
DESKTOP_DIR := $(REPO_ROOT)/apps/desktop
ANDROID_DIR := $(REPO_ROOT)/apps/android
DIST_DIR := $(REPO_ROOT)/dist
APK_EXPORT := $(DIST_DIR)/discount-screener-debug.apk

CARGO := cargo
GRADLE := gradlew.bat

.PHONY: all build test clean fmt check release run \
        desktop-build desktop-test desktop-clean desktop-fmt desktop-check desktop-release desktop-smoke desktop-run \
        android-build android-test android-clean android-release android-run apk \
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

android-run:
	powershell -NoProfile -ExecutionPolicy Bypass -File "$(REPO_ROOT)/scripts/android-run.ps1"

android-build:
	pushd "$(ANDROID_DIR)" && $(GRADLE) compileDebugKotlin && popd

android-test:
	pushd "$(ANDROID_DIR)" && $(GRADLE) test && popd

android-clean:
	pushd "$(ANDROID_DIR)" && $(GRADLE) clean && popd

android-release:
	pushd "$(ANDROID_DIR)" && $(GRADLE) assembleRelease && popd

apk:
	pushd "$(ANDROID_DIR)" && $(GRADLE) :app:assembleDebug && popd
	powershell -NoProfile -ExecutionPolicy Bypass -Command "New-Item -ItemType Directory -Force -Path '$(DIST_DIR)' | Out-Null; Copy-Item -Force '$(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk' '$(APK_EXPORT)'; Write-Host 'Installable APK: $(APK_EXPORT)'"

# ── Contracts (cross-platform) ──

contracts-test:
	$(CARGO) test --manifest-path $(DESKTOP_DIR)/Cargo.toml contract_fixture
	pushd "$(ANDROID_DIR)" && $(GRADLE) :core:test --tests com.discountscreener.core.contracts.ContractFixtureTest && popd
