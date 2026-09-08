/*
 * Copyright 2026, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.lawnchair.report.AppClickCount
import app.lawnchair.report.DayReport
import app.lawnchair.report.LawnchairReportRepository
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import com.android.launcher3.R

@Composable
fun ReportPreference() {
    val context = LocalContext.current
    val dateOptions = remember { LawnchairReportRepository.getDateOptions() }
    var selectedDateKey by remember { mutableStateOf(LawnchairReportRepository.getTodayKey()) }
    val report = remember(selectedDateKey) {
        LawnchairReportRepository.getReportForDate(context, selectedDateKey)
    }

    PreferenceLayout(
        label = stringResource(id = R.string.report_label),
    ) {
        // Date Selector Dropdown
        PreferenceGroup(heading = stringResource(id = R.string.report_select_date)) {
            var expanded by remember { mutableStateOf(false) }
            val selectedOption = dateOptions.find { it.key == selectedDateKey } ?: dateOptions.first()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = selectedOption.label,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        )
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = null,
                        )
                    }
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.85f),
                ) {
                    dateOptions.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.label,
                                    fontWeight = if (option.key == selectedDateKey) FontWeight.Bold else FontWeight.Normal,
                                    color = if (option.key == selectedDateKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                selectedDateKey = option.key
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        // Metrics Grid
        PreferenceGroup(heading = "Launcher Performance Metrics (${report.displayDate})") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Schedule,
                        title = "Total Runtime",
                        value = LawnchairReportRepository.formatDuration(report.totalRuntimeSeconds),
                        subtitle = "Active / Resumed",
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Refresh,
                        title = "Restarts",
                        value = "${report.restartCount} times",
                        subtitle = "Last: ${report.lastRestartTime}",
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.BatteryChargingFull,
                        title = "Battery Consumed",
                        value = "${report.batteryForeground} FG",
                        subtitle = "${report.batteryBackground} BG",
                    )
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.PowerSettingsNew,
                        title = "Last Killed",
                        value = report.lastKilledTime,
                        subtitle = "App closed timestamp",
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetricCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Apps,
                        title = "Apps in Drawer",
                        value = "${report.totalAppsInDrawer}",
                        subtitle = "Visible apps",
                    )
                }
            }
        }

        // Top 10 Most Clicked Apps Section (Last 15 Days)
        PreferenceGroup(heading = "Top 10 Most Clicked Apps (Last 15 Days)") {
            if (report.topClickedApps.isEmpty()) {
                Text(
                    text = "No app launches recorded in the last 15 days.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    report.topClickedApps.forEachIndexed { index, appClick ->
                        AppClickRow(rank = index + 1, appClick = appClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun AppClickRow(
    rank: Int,
    appClick: AppClickCount,
) {
    val context = LocalContext.current
    val iconBitmap = remember(appClick.packageName) {
        try {
            val drawable = context.packageManager.getApplicationIcon(appClick.packageName)
            drawable.toBitmap().asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    PreferenceTemplate(
        title = {
            Text(
                text = "#$rank  ${appClick.appTitle}",
                fontWeight = FontWeight.Medium,
            )
        },
        description = {
            Text(text = appClick.packageName)
        },
        startWidget = {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = appClick.appTitle.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        endWidget = {
            Box(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${appClick.count} clicks",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    )
}
