package com.discountscreener.android.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.discountscreener.android.presentation.dashboard.DashboardViewModel
import com.discountscreener.android.ui.DiscountScreenerApp

class MainActivity : ComponentActivity() {
    private val appContainer by lazy { DiscountScreenerAppContainer(applicationContext) }
    private val viewModel: DashboardViewModel by viewModels {
        appContainer.dashboardViewModelFactory()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DiscountScreenerApp(viewModel)
        }
    }
}
