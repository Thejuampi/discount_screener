package com.discountscreener.android.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.discountscreener.android.presentation.dashboard.DashboardViewModel
import com.discountscreener.android.ui.DiscountScreenerApp

class MainActivity : ComponentActivity() {
    private val appContainer by lazy { DiscountScreenerAppContainer(applicationContext) }
    private val viewModel: DashboardViewModel by viewModels {
        appContainer.dashboardViewModelFactory()
    }

    private val askForNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // The load runs either way. Without the permission the progress notification is hidden,
        // and a hidden notification is a worse product, never a broken one.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        appContainer.keepLoadsRunningInBackground()
        setContent {
            DiscountScreenerApp(viewModel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
