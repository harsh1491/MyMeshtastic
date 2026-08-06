/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.app

import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import com.eygraber.uri.toKmpUri
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.meshtastic.app.intro.AnalyticsIntro
import org.meshtastic.app.map.getMapViewProvider
import org.meshtastic.app.node.component.InlineMap
import org.meshtastic.app.node.metrics.getTracerouteMapOverlayInsets
import org.meshtastic.app.ui.MainScreen
import org.meshtastic.core.barcode.rememberBarcodeScanner
import org.meshtastic.core.navigation.DEEP_LINK_BASE_URI
import org.meshtastic.core.network.repository.UsbRepository
import org.meshtastic.core.nfc.NfcScannerEffect
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.channel_invalid
import org.meshtastic.core.service.MeshServiceClient
import org.meshtastic.core.ui.theme.AppTheme
import org.meshtastic.core.ui.theme.MODE_DYNAMIC
import org.meshtastic.core.ui.util.LocalAnalyticsIntroProvider
import org.meshtastic.core.ui.util.LocalBarcodeScannerProvider
import org.meshtastic.core.ui.util.LocalBarcodeScannerSupported
import org.meshtastic.core.ui.util.LocalEventBranding
import org.meshtastic.core.ui.util.LocalInlineMapProvider
import org.meshtastic.core.ui.util.LocalMapMainScreenProvider
import org.meshtastic.core.ui.util.LocalMapViewProvider
import org.meshtastic.core.ui.util.LocalNfcScannerProvider
import org.meshtastic.core.ui.util.LocalNfcScannerSupported
import org.meshtastic.core.ui.util.LocalNodeMapScreenProvider
import org.meshtastic.core.ui.util.LocalNodeTrackMapProvider
import org.meshtastic.core.ui.util.LocalTracerouteMapOverlayInsetsProvider
import org.meshtastic.core.ui.util.LocalTracerouteMapProvider
import org.meshtastic.core.ui.util.LocalTracerouteMapScreenProvider
import org.meshtastic.core.ui.util.showToast
import org.meshtastic.core.ui.viewmodel.UIViewModel
import org.meshtastic.feature.intro.AppIntroductionScreen
import org.meshtastic.feature.intro.IntroViewModel
import org.meshtastic.feature.map.MapScreen
import org.meshtastic.feature.map.SharedMapViewModel
import org.meshtastic.feature.map.node.NodeMapViewModel
import org.meshtastic.feature.node.metrics.MetricsViewModel
import org.meshtastic.feature.node.metrics.TracerouteMapScreen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background


import org.koin.android.ext.android.inject
import org.meshtastic.app.battlefield.BattlefieldViewModel
import org.meshtastic.core.repository.NodeRepository
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

import kotlinx.coroutines.delay


class MainActivity : AppCompatActivity() {
    private val model: UIViewModel by viewModel()

    private val usbRepository: UsbRepository by inject()

    private val antSdrManager: org.meshtastic.app.sdr.AntSdrManager by inject() // <-- ADD THIS LINE

    /**
     * Activity-lifecycle-aware client that binds to the mesh service. Note: This is used implicitly as it registers
     * itself as a LifecycleObserver in its init block.
     */
    internal val meshServiceClient: MeshServiceClient by inject { parametersOf(this) }

    override fun onCreate(savedInstanceState: Bundle?) {

        // Emergency wipe check — must be first
        if (EmergencyWipeManager.isWiped(this)) {
            finish()
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }


        installSplashScreen()

        // Eagerly evaluate lazy Koin dependency so it registers its LifecycleObserver
        meshServiceClient.hashCode()

        // Set myNodeId in BattlefieldViewModel so it can send LoRa messages
        val battlefieldVm: BattlefieldViewModel by inject()
        val nodeRepository: NodeRepository by inject()
        lifecycleScope.launch {
            delay(2000)
            val myId = nodeRepository.myId.value ?: ""
            if (myId.isNotEmpty()) {
                battlefieldVm.resetToSoldierOnLaunch(myId)
            }
        }

        // ── ADD THIS BLOCK: Initialize AntSDR Listening Stream ──
        lifecycleScope.launch {
            delay(3000) // Give the USB hub sub-stack a moment to settle down on app launch
            android.util.Log.d("AntSDR_USB", "Initializing automated over-the-air drone tracker...")
            antSdrManager.startListening()
        }

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // Explicitly set the cutout mode to ALWAYS for Android 15+ to satisfy Play Console recommendations.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // Ensure the navigation bar remains seamless on modern Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            // Bridge Koin-provided ImageLoader to Coil's singleton
            setSingletonImageLoaderFactory { get<ImageLoader>() }

            // ── TIME-BOMB EXPIRATION CHECK ──
            val expiryCalendar = java.util.Calendar.getInstance().apply {
                // Year, Month (0-indexed: 5 = June), Day
                set(2026, java.util.Calendar.AUGUST, 20, 0, 0, 0)
            }
            val currentCalendar = java.util.Calendar.getInstance()
            val isExpired = currentCalendar.after(expiryCalendar)

            if (isExpired) {
                // ── GRACEFUL LOCKOUT INTERFACE (Bypasses normal app initialization) ──
                AppTheme(dynamicColor = false, darkTheme = true) {
                    androidx.compose.foundation.layout.Box(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color(0xFF0A0F0D)), // Tactical pitch black
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        androidx.compose.foundation.layout.Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                            modifier = androidx.compose.ui.Modifier.padding(24.dp)
                        ) {
                            // Warn Icon / Header
                            Text(
                                text = "🛑 BUILD EXPIRED",
                                color = androidx.compose.ui.graphics.Color(0xFFE53935), // Dark Alert Red
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                fontSize = 22.sp,
                                letterSpacing = 1.sp
                            )

                            // Explanation Text
//                            Text(
//                                text = "CRITICAL: This operational evaluation build expired on 14 June 2026.\n\nExecution privileges have been suspended. Please contact system administration for an updated deployment package.",
//                                color = androidx.compose.ui.graphics.Color(0xFFA0B2A6), // Tactical gray-green text
//                                fontSize = 14.sp,
//                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
//                                lineHeight = 20.sp
//                            )

                            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(16.dp))

                            // Graceful Exit Controller
                            Button(
                                onClick = {
                                    finish() // Gracefully closes the activity view wrapper without crashing
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF8B0000)), // Deep Blood Red
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                            ) {
                                Text(
                                    text = "OK",
                                    color = androidx.compose.ui.graphics.Color.White,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            } else {
                // ── NORMAL APP RUNTIME (Runs perfectly if before June 14) ──
                val theme by model.theme.collectAsStateWithLifecycle()
                val dynamic = theme == MODE_DYNAMIC
                val dark =
                    when (theme) {
                        AppCompatDelegate.MODE_NIGHT_YES -> true
                        AppCompatDelegate.MODE_NIGHT_NO -> false
                        else -> isSystemInDarkTheme()
                    }

                // Update system bar style when theme changes
                androidx.compose.runtime.SideEffect {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    )
                }

                AppCompositionLocals {
                    AppTheme(dynamicColor = dynamic, darkTheme = dark) {
                        val appIntroCompleted by model.appIntroCompleted.collectAsStateWithLifecycle()

                        ReportDrawnWhen { true }

                        if (appIntroCompleted) {
                            MainScreen()
                        } else {
                            val introViewModel = koinViewModel<IntroViewModel>()
                            AppIntroductionScreen(onDone = { model.onAppIntroCompleted() }, viewModel = introViewModel)
                        }
                    }
                }
            }
        }

        // Listen for new intents (e.g. deep links, NFC) without overriding onNewIntent
        addOnNewIntentListener { intent -> handleIntent(intent) }

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Belt-and-suspenders for the Android 12+ attach-intent quirk: if the activity is
        // resumed while a USB device is already attached (e.g. process restart, returning
        // from another app), the manifest-declared attach intent may have already fired
        // before UsbRepository was constructed. Re-poll deviceList here so the UI reflects
        // reality without requiring the user to physically replug.
        usbRepository.refreshState()
    }

    @Suppress("LongMethod")
    @Composable
    private fun AppCompositionLocals(content: @Composable () -> Unit) {
        val eventEdition by model.eventEdition.collectAsStateWithLifecycle()
        CompositionLocalProvider(
            LocalEventBranding provides eventEdition,
            LocalBarcodeScannerProvider provides { onResult -> rememberBarcodeScanner(onResult) },
            LocalNfcScannerProvider provides { onResult, onDisabled -> NfcScannerEffect(onResult, onDisabled) },
            LocalBarcodeScannerSupported provides true,
            LocalNfcScannerSupported provides true,
            LocalAnalyticsIntroProvider provides { AnalyticsIntro() },
            LocalMapViewProvider provides getMapViewProvider(),
            LocalInlineMapProvider provides { node, modifier -> InlineMap(node, modifier) },
            LocalNodeTrackMapProvider provides
                { destNum, positions, modifier, selectedPositionTime, onPositionSelected ->
                    org.meshtastic.app.map.node.NodeTrackMap(
                        destNum,
                        positions,
                        modifier,
                        selectedPositionTime,
                        onPositionSelected,
                    )
                },
            LocalTracerouteMapOverlayInsetsProvider provides getTracerouteMapOverlayInsets(),
            LocalTracerouteMapProvider provides
                { overlay, nodePositions, onMappableCountChanged, modifier ->
                    org.meshtastic.app.map.traceroute.TracerouteMap(
                        tracerouteOverlay = overlay,
                        tracerouteNodePositions = nodePositions,
                        onMappableCountChanged = onMappableCountChanged,
                        modifier = modifier,
                    )
                },
            LocalNodeMapScreenProvider provides
                { destNum, onNavigateUp ->
                    val vm = koinViewModel<NodeMapViewModel>()
                    vm.setDestNum(destNum)
                    org.meshtastic.app.map.node.NodeMapScreen(vm, onNavigateUp = onNavigateUp)
                },
            LocalTracerouteMapScreenProvider provides
                { destNum, requestId, logUuid, onNavigateUp ->
                    val metricsViewModel = koinViewModel<MetricsViewModel> { parametersOf(destNum) }
                    metricsViewModel.setNodeId(destNum)

                    TracerouteMapScreen(
                        metricsViewModel = metricsViewModel,
                        requestId = requestId,
                        logUuid = logUuid,
                        onNavigateUp = onNavigateUp,
                    )
                },
            LocalMapMainScreenProvider provides
                { onClickNodeChip, navigateToNodeDetails, waypointId ->
                    val viewModel = koinViewModel<SharedMapViewModel>()
                    MapScreen(
                        viewModel = viewModel,
                        onClickNodeChip = onClickNodeChip,
                        navigateToNodeDetails = navigateToNodeDetails,
                        waypointId = waypointId,
                    )
                },
            content = content,
        )
    }

    @Suppress("NestedBlockDepth")
    private fun handleIntent(intent: Intent) {
        val appLinkAction = intent.action
        val appLinkData: Uri? = intent.data

        when (appLinkAction) {
            Intent.ACTION_VIEW -> {
                appLinkData?.let { handleMeshtasticUri(it) }
            }

            NfcAdapter.ACTION_NDEF_DISCOVERED -> {
                val rawMessages =
                    IntentCompat.getParcelableArrayExtra(
                        intent,
                        NfcAdapter.EXTRA_NDEF_MESSAGES,
                        NdefMessage::class.java,
                    )
                if (rawMessages != null) {
                    for (rawMsg in rawMessages) {
                        val msg = rawMsg as NdefMessage
                        for (record in msg.records) {
                            record.toUri()?.let { handleMeshtasticUri(it) }
                        }
                    }
                }
            }

            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Logger.d { "USB device attached" }
                // Android 12+ delivers ACTION_USB_DEVICE_ATTACHED only to manifest-declared
                // receivers, so the runtime-registered UsbBroadcastReceiver inside UsbRepository
                // never sees this event. Forward it explicitly so the serialDevices StateFlow
                // refreshes and the device shows up in the Connect → Serial tab.
                usbRepository.refreshState()
                showConnectionsPage()
            }

            Intent.ACTION_MAIN -> {}

            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (text != null) {
                    createShareIntent(text).send()
                }
            }

            else -> {
                Logger.w { "Unexpected action $appLinkAction" }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        antSdrManager.stopListening() // <-- ADD THIS LINE: Closes serial stream links safely


        val battlefieldVm: BattlefieldViewModel by inject()
        val nodeRepository: NodeRepository by inject()
        val myId = nodeRepository.myId.value ?: ""
        if (myId.isNotEmpty()) {
            battlefieldVm.resetToSoldierOnClose(myId)
        }
    }

    private fun handleMeshtasticUri(uri: Uri) {
        Logger.d { "Handling Meshtastic URI: $uri" }

        model.handleDeepLink(uri.toKmpUri()) { lifecycleScope.launch { showToast(Res.string.channel_invalid) } }
    }

    private fun createShareIntent(message: String): PendingIntent {
        val deepLink = "$DEEP_LINK_BASE_URI/share?message=$message"
        val startActivityIntent =
            Intent(Intent.ACTION_VIEW, deepLink.toUri(), this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        val resultPendingIntent: PendingIntent? =
            TaskStackBuilder.create(this).run {
                addNextIntentWithParentStack(startActivityIntent)
                getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE)
            }
        return resultPendingIntent!!
    }

    private fun createConnectionsIntent(): PendingIntent {
        val deepLink = "$DEEP_LINK_BASE_URI/connections"
        val startActivityIntent =
            Intent(Intent.ACTION_VIEW, deepLink.toUri(), this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        val resultPendingIntent: PendingIntent? =
            TaskStackBuilder.create(this).run {
                addNextIntentWithParentStack(startActivityIntent)
                getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE)
            }
        return resultPendingIntent!!
    }

    private fun showConnectionsPage() {
        createConnectionsIntent().send()
    }
}




//just checking
