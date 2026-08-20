package com.discountscreener.android

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.robolectric.Shadows.shadowOf

/**
 * Makes `androidx.activity.ComponentActivity` launchable in unit tests on every build variant.
 *
 * `createAndroidComposeRule<ComponentActivity>()` starts a real activity, so that activity has to
 * be in the merged manifest. The `ui-test-manifest` artifact supplies the entry, and it can only be
 * a `debugImplementation`: any configuration that reaches the release variant would ship a test
 * activity inside the shipped APK. So `testReleaseUnitTest` used to die on "Unable to resolve
 * activity for Intent ... androidx.activity.ComponentActivity".
 *
 * Registering the component here fixes both variants from test source, and adds nothing to any APK.
 * It is idempotent, so the debug variant — where the manifest entry is already present — is
 * unaffected.
 *
 * Wired in through `src/test/resources/robolectric.properties`, which applies it to every
 * Robolectric class without a per-class annotation.
 */
class RobolectricTestApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        shadowOf(packageManager)
            .addActivityIfNotPresent(ComponentName(this, ComponentActivity::class.java))
    }
}
