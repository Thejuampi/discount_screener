package com.discountscreener.android.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DashboardViewModel
import com.discountscreener.android.ui.dashboard.DashboardScreen
import com.discountscreener.android.ui.dashboard.DetailScreen
import com.discountscreener.android.ui.theme.DiscountScreenerTheme
import kotlinx.coroutines.delay

@Composable
fun DiscountScreenerApp(viewModel: DashboardViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val (disclaimerAccepted, setDisclaimerAccepted) = remember(context) {
        mutableStateOf(readDisclaimerAccepted(context))
    }
    val (splashMinimumElapsed, setSplashMinimumElapsed) = remember {
        mutableStateOf(false)
    }

    DisposableEffect(viewModel) {
        viewModel.dispatch(DashboardAction.Start)
        onDispose { }
    }

    LaunchedEffect(Unit) {
        delay(splashMinimumDurationMillis)
        setSplashMinimumElapsed(true)
    }

    DiscountScreenerTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            when (startupStage(
                loading = state.loading,
                splashMinimumElapsed = splashMinimumElapsed,
                disclaimerAccepted = disclaimerAccepted,
            )) {
                StartupStage.Splash -> StartupSplashScreen(loading = state.loading)
                StartupStage.Disclaimer -> DisclaimerGate(
                    onAccept = {
                        writeDisclaimerAccepted(context, true)
                        setDisclaimerAccepted(true)
                    },
                    onClose = {
                        context.findActivity()?.finish()
                    },
                )
                StartupStage.Content -> {
                    when (val detailRoute = state.detailRoute) {
                        null -> DashboardScreen(state = state, onAction = viewModel::dispatch)
                        else -> DetailScreen(
                            route = detailRoute,
                            detail = state.detailData,
                            charts = state.detailCharts,
                            history = state.detailHistory,
                            alerts = state.detailAlerts.map { "${it.kind} #${it.sequence}" },
                            quantLens = state.detailQuantLens,
                            detailNotice = state.detailNotice,
                            projectedDetail = state.projectedDetailData,
                            onAction = viewModel::dispatch,
                        )
                    }
                }
            }
        }
    }
}
