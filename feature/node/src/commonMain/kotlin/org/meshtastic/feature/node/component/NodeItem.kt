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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.node.component

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import org.meshtastic.core.common.util.MetricFormatter
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.DeviceType
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.isUnmessageableRole
import org.meshtastic.core.model.util.toDistanceString
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.air_utilization
import org.meshtastic.core.resources.channel_utilization
import org.meshtastic.core.resources.current
import org.meshtastic.core.resources.elevation_suffix
import org.meshtastic.core.resources.signal_quality
import org.meshtastic.core.resources.unknown_username
import org.meshtastic.core.resources.voltage
import org.meshtastic.core.ui.component.AirQualityInfo
import org.meshtastic.core.ui.component.ChannelInfo
import org.meshtastic.core.ui.component.DistanceInfo
import org.meshtastic.core.ui.component.ElevationInfo
import org.meshtastic.core.ui.component.HardwareInfo
import org.meshtastic.core.ui.component.HopsInfo
import org.meshtastic.core.ui.component.HumidityInfo
import org.meshtastic.core.ui.component.IconInfo
import org.meshtastic.core.ui.component.LastHeardInfo
import org.meshtastic.core.ui.component.MaterialBatteryInfo
import org.meshtastic.core.ui.component.NodeChip
import org.meshtastic.core.ui.component.NodeIdInfo
import org.meshtastic.core.ui.component.NodeKeyStatusIcon
import org.meshtastic.core.ui.component.PaxcountInfo
import org.meshtastic.core.ui.component.PowerInfo
import org.meshtastic.core.ui.component.PressureInfo
import org.meshtastic.core.ui.component.RoleInfo
import org.meshtastic.core.ui.component.Rssi
import org.meshtastic.core.ui.component.SatelliteCountInfo
import org.meshtastic.core.ui.component.Snr
import org.meshtastic.core.ui.component.SoilMoistureInfo
import org.meshtastic.core.ui.component.SoilTemperatureInfo
import org.meshtastic.core.ui.component.TemperatureInfo
import org.meshtastic.core.ui.component.TransportIcon
import org.meshtastic.core.ui.component.determineSignalQuality
import org.meshtastic.core.ui.icon.AirUtilization
import org.meshtastic.core.ui.icon.ChannelUtilization
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.Notes
import org.meshtastic.proto.Config

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp


// ── In-Memory Active / Passive State for Demo Nodes ──
private val demoNodeModeState = mutableStateMapOf(
    10101 to true,  // commander -> Passive (true) / Active (false)
    10102 to true,  // detector-1
    10103 to true,  // detector-2
    10104 to true,  // drone1
    10105 to false  // drone2
)

private fun isDemoNode(num: Int, name: String): Boolean {
    return (num in 10101..10105) || name in listOf("commander", "detector-1", "detector-2", "drone1", "drone2")
}

private fun getDemoSpeed(num: Int, name: String): String {
    return when {
        num == 10104 || name == "drone1" -> "38.4 km/h"
        num == 10105 || name == "drone2" -> "42.1 km/h"
        num == 10101 || name == "commander" -> "0.0 km/h"
        num == 10102 || name == "detector-1" -> "0.0 km/h"
        num == 10103 || name == "detector-2" -> "0.0 km/h"
        else -> ""
    }
}

private const val ACTIVE_ALPHA = 0.5f
private const val INACTIVE_ALPHA = 0.2f
private const val GRID_COLUMNS = 3

@Composable
@Suppress("LongMethod")
fun NodeItem(
    thisNode: Node?,
    thatNode: Node,
    distanceUnits: Int,
    tempInFahrenheit: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    connectionState: ConnectionState,
    deviceType: DeviceType? = null,
    isActive: Boolean = false,
) {



    val isDemo = isDemoNode(thatNode.num, thatNode.user.long_name)
    val nowSec = (System.currentTimeMillis() / 1000).toInt()

    // ── Enriches Demo Nodes so they show 100% complete metrics like real nodes ──
    val effectiveNode = if (isDemo) {
        val defaultAlt = when (thatNode.num) {
            10101 -> 1170
            10102 -> 1168
            10103 -> 1172
            10104 -> 1250
            10105 -> 1280
            else -> 1170
        }
        val defaultBatt = when (thatNode.num) {
            10101 -> 98
            10102 -> 89
            10103 -> 91
            10104 -> 84
            10105 -> 78
            else -> 85
        }
        val defaultVolt = when (thatNode.num) {
            10101 -> 4.18f
            10102 -> 4.05f
            10103 -> 4.08f
            10104 -> 3.95f
            10105 -> 3.86f
            else -> 4.0f
        }
        val defaultSnr = when (thatNode.num) {
            10101 -> 9.8f
            10102 -> 8.5f
            10103 -> 8.2f
            10104 -> 7.4f
            10105 -> 7.1f
            else -> 8.0f
        }
        thatNode.copy(
            lastHeard = thatNode.lastHeard?.takeIf { it > 0 } ?: nowSec,
            snr = if (thatNode.snr != 0f && thatNode.snr < 100f) thatNode.snr else defaultSnr,
            deviceMetrics = thatNode.deviceMetrics.copy(
                battery_level = thatNode.deviceMetrics.battery_level?.takeIf { it > 0 } ?: defaultBatt,
                voltage = thatNode.deviceMetrics.voltage?.takeIf { it > 0f } ?: defaultVolt
            ),
            position = thatNode.position?.copy(
                altitude = thatNode.position?.altitude?.takeIf { it != 0 } ?: defaultAlt
            ) ?: org.meshtastic.proto.Position(altitude = defaultAlt)
        )
    } else {
        thatNode
    }




    val originalLongName = thatNode.user.long_name.ifEmpty { stringResource(Res.string.unknown_username) }
    val isMuted = remember(thatNode) { thatNode.isMuted }
    val isIgnored = thatNode.isIgnored
    val isFavorite = if (isDemo) true else thatNode.isFavorite

    val isThisNode = remember(thatNode) { thisNode?.num == thatNode.num }
    val system =
        remember(distanceUnits) {
            Config.DisplayConfig.DisplayUnits.fromValue(distanceUnits) ?: Config.DisplayConfig.DisplayUnits.METRIC
        }
    val distance =
        remember(thisNode, thatNode) { thisNode?.distance(thatNode)?.takeIf { it > 0 }?.toDistanceString(system) }

    var contentColor = MaterialTheme.colorScheme.onSurface
    val cardColors =
        if (isThisNode) {
            thisNode?.colors?.second
        } else {
            thatNode.colors.second
        }
            ?.let {
                val alpha = if (isActive) ACTIVE_ALPHA else INACTIVE_ALPHA
                val containerColor = Color(it).copy(alpha = alpha)
                contentColor = contentColorFor(containerColor)
                CardDefaults.cardColors().copy(containerColor = containerColor, contentColor = contentColor)
            } ?: (CardDefaults.cardColors())

    val style =
        if (thatNode.isUnknownUser) {
            FontStyle.Italic
        } else {
            FontStyle.Normal
        }

    val unmessageable =
        remember(thatNode) {
            when {
                thatNode.user.is_unmessagable != null -> thatNode.user.is_unmessagable!!
                else -> thatNode.user.role.isUnmessageableRole()
            }
        }

    Card(modifier = modifier.fillMaxWidth(), colors = cardColors) {
        Column(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ✅ Pass effectiveNode here
            NodeItemHeader(
                thatNode = effectiveNode,
                isThisNode = isThisNode,
                longName = originalLongName,
                style = style,
                isIgnored = isIgnored,
                isFavorite = isFavorite,
                isMuted = isMuted,
                isUnmessageable = unmessageable,
                connectionState = connectionState,
                deviceType = deviceType,
                contentColor = contentColor,
            )

            effectiveNode.nodeStatus?.let { status ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MeshtasticIcons.Notes,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = contentColor.copy(alpha = 0.7f),
                    )
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ✅ Pass effectiveNode here (Unlocks 98% 4.18V and Altitude MSL)
            NodeBatteryPositionRow(
                thatNode = effectiveNode,
                distance = distance,
                system = system,
                contentColor = contentColor,
            )

            // ✅ Pass effectiveNode here (Unlocks SNR 9.80 dB)
            NodeSignalRow(thatNode = effectiveNode, isThisNode = isThisNode, contentColor = contentColor)

            val sensorItems = gatherSensors(effectiveNode, tempInFahrenheit, contentColor)
            if (sensorItems.isNotEmpty()) {
                MetricsGrid(sensorItems)
            }

            // ✅ Pass effectiveNode here
            NodeItemFooter(thatNode = effectiveNode, contentColor = contentColor)

            // ── 4th UPDATE: Active / Passive Toggle Button ──
            if (isDemo) {
                val isPassive = demoNodeModeState[effectiveNode.num] ?: true
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isPassive) Color(0x33FF9800) else Color(0x334CAF50),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isPassive) "• PASSIVE (STEALTH)" else "• ACTIVE TRANSMIT",
                            color = if (isPassive) Color(0xFFFF9800) else Color(0xFF4CAF50),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Button(
                        onClick = {
                            val current = demoNodeModeState[effectiveNode.num] ?: true
                            demoNodeModeState[effectiveNode.num] = !current
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPassive) Color(0xFF1976D2) else Color(0xFFE65100)
                        ),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 4.dp
                        ),
                    ) {
                        Text(
                            text = if (isPassive) "Switch to Active mode" else "Switch to Passive mode",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeBatteryPositionRow(
    thatNode: Node,
    distance: String?,
    system: Config.DisplayConfig.DisplayUnits,
    contentColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MaterialBatteryInfo(
            level = thatNode.batteryLevel ?: 0,
            voltage = thatNode.voltage ?: 0f,
            contentColor = contentColor,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (distance != null) {
                DistanceInfo(distance = distance, contentColor = contentColor)
            }
            thatNode.validPosition?.let { position ->
                ElevationInfo(
                    altitude = position.altitude ?: 0,
                    system = system,
                    suffix = stringResource(Res.string.elevation_suffix),
                    contentColor = contentColor,
                )
            }

            // ═══════════════════════════════════════════════════════════
            // ── ADD THIS: SPEED INDICATOR ──
            // ═══════════════════════════════════════════════════════════
            val speedStr = getDemoSpeed(thatNode.num, thatNode.user.long_name)
            if (speedStr.isNotEmpty()) {
                Text(
                    text = "Spd: $speedStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun NodeSignalRow(thatNode: Node, isThisNode: Boolean, contentColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isThisNode) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconInfo(
                    icon = MeshtasticIcons.ChannelUtilization,
                    contentDescription = stringResource(Res.string.channel_utilization),
                    label = stringResource(Res.string.channel_utilization),
                    text = MetricFormatter.percent(thatNode.deviceMetrics.channel_utilization ?: 0f),
                    contentColor = contentColor,
                )
                IconInfo(
                    icon = MeshtasticIcons.AirUtilization,
                    contentDescription = stringResource(Res.string.air_utilization),
                    label = stringResource(Res.string.air_utilization),
                    text = MetricFormatter.percent(thatNode.deviceMetrics.air_util_tx ?: 0f),
                    contentColor = contentColor,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (thatNode.hopsAway > 0) {
                    HopsInfo(hops = thatNode.hopsAway, contentColor = contentColor)
                } else if (thatNode.hopsAway == 0 && !thatNode.viaMqtt) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (thatNode.snr < 100f) Snr(thatNode.snr)
                        if (thatNode.rssi < 0) Rssi(thatNode.rssi)
                        if (thatNode.snr < 100f && thatNode.rssi < 0) {
                            val quality = determineSignalQuality(thatNode.snr, thatNode.rssi)
                            IconInfo(
                                icon = vectorResource(quality.icon),
                                contentDescription = stringResource(Res.string.signal_quality),
                                contentColor = quality.color.invoke(),
                                text = stringResource(quality.nameRes),
                            )
                        }
                    }
                }
                if (thatNode.channel > 0) {
                    ChannelInfo(channel = thatNode.channel, contentColor = contentColor)
                }
            }
        }

        val satCount = thatNode.validPosition?.sats_in_view ?: 0
        if (satCount > 0) {
            SatelliteCountInfo(satCount = satCount, contentColor = contentColor)
        } else {
            Spacer(Modifier)
        }
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
private fun gatherSensors(node: Node, tempInFahrenheit: Boolean, contentColor: Color): List<@Composable () -> Unit> {
    val items = mutableListOf<@Composable () -> Unit>()
    val env = node.environmentMetrics
    val pax = node.paxcounter

    if (pax.ble != 0 || pax.wifi != 0) {
        items.add { PaxcountInfo(pax = "B:${pax.ble} W:${pax.wifi}", contentColor = contentColor) }
    }

    if ((env.temperature ?: 0f) != 0f) {
        val temp = MetricFormatter.temperature(env.temperature ?: 0f, tempInFahrenheit)
        items.add { TemperatureInfo(temp = temp, contentColor = contentColor) }
    }
    if ((env.relative_humidity ?: 0f) != 0f) {
        items.add {
            HumidityInfo(humidity = MetricFormatter.humidity(env.relative_humidity ?: 0f), contentColor = contentColor)
        }
    }
    if ((env.barometric_pressure ?: 0f) != 0f) {
        items.add {
            PressureInfo(
                pressure = MetricFormatter.pressure(env.barometric_pressure ?: 0f),
                contentColor = contentColor,
            )
        }
    }
    if ((env.soil_temperature ?: 0f) != 0f) {
        val temp = MetricFormatter.temperature(env.soil_temperature ?: 0f, tempInFahrenheit)
        items.add { SoilTemperatureInfo(temp = temp, contentColor = contentColor) }
    }
    if ((env.soil_moisture ?: 0) != 0 && (env.soil_temperature ?: 0f) != 0f) {
        items.add { SoilMoistureInfo(moisture = "${env.soil_moisture}%", contentColor = contentColor) }
    }
    if ((env.voltage ?: 0f) != 0f) {
        items.add {
            PowerInfo(
                value = MetricFormatter.voltage(env.voltage ?: 0f),
                label = stringResource(Res.string.voltage),
                contentColor = contentColor,
            )
        }
    }
    if ((env.current ?: 0f) != 0f) {
        items.add {
            PowerInfo(
                value = MetricFormatter.current(env.current ?: 0f),
                label = stringResource(Res.string.current),
                contentColor = contentColor,
            )
        }
    }
    if ((env.iaq ?: 0) != 0) {
        items.add { AirQualityInfo(iaq = "${env.iaq}", contentColor = contentColor) }
    }

    return items
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricsGrid(items: List<@Composable () -> Unit>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        maxItemsInEachRow = GRID_COLUMNS,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val remainder = items.size % GRID_COLUMNS
        items.forEach { item -> Box(Modifier.weight(1f)) { item() } }
        if (remainder != 0) {
            repeat(GRID_COLUMNS - remainder) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun NodeItemHeader(
    thatNode: Node,
    isThisNode: Boolean,
    longName: String,
    style: FontStyle,
    isIgnored: Boolean,
    isFavorite: Boolean,
    isMuted: Boolean,
    isUnmessageable: Boolean,
    connectionState: ConnectionState,
    deviceType: DeviceType?,
    contentColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NodeChip(node = thatNode)

        NodeKeyStatusIcon(
            hasPKC = thatNode.hasPKC,
            mismatchKey = thatNode.mismatchKey,
            publicKey = thatNode.user.public_key,
            modifier = Modifier.size(24.dp),
        )

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = longName,
                    style = MaterialTheme.typography.titleMediumEmphasized.copy(fontStyle = style),
                    textDecoration = TextDecoration.LineThrough.takeIf { isIgnored },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TransportIcon(
                    transport = thatNode.lastTransport,
                    viaMqtt = thatNode.viaMqtt,
                    modifier = Modifier.size(16.dp),
                )
            }
            LastHeardInfo(lastHeard = thatNode.lastHeard, showLabel = false, contentColor = contentColor)
        }

        NodeStatusIcons(
            isThisNode = isThisNode,
            isFavorite = isFavorite,
            isMuted = isMuted,
            isUnmessageable = isUnmessageable,
            connectionState = connectionState,
            deviceType = deviceType,
            contentColor = contentColor,
        )
    }
}

@Composable
private fun NodeItemFooter(thatNode: Node, contentColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HardwareInfo(hwModel = thatNode.user.hw_model.name, contentColor = contentColor)
        RoleInfo(role = thatNode.user.role, contentColor = contentColor)
        NodeIdInfo(id = thatNode.user.id.ifEmpty { "???" }, contentColor = contentColor)
    }
}
