package com.example.blindguideapp.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.blindguideapp.data.DeviceProfile
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * App 內建家屬模式主畫面：頂部配對碼 + 導航紀錄列表，
 * 點「查看詳情」開啟 [NavigationDetailOverlay]（路線地圖 + 警報標記）。
 */
@Composable
fun FamilyModeScreen(
    serverUrl: String,
    deviceProfile: DeviceProfile?,
    onRegenerateCode: () -> Unit,
    onSwitchToBlindMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    var records by remember { mutableStateOf<List<NavigationRecordDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedNavigationId by remember { mutableStateOf<Int?>(null) }

    val pairingCode = deviceProfile?.pairingCode

    LaunchedEffect(pairingCode) {
        if (pairingCode.isNullOrBlank()) return@LaunchedEffect
        isLoading = true
        errorMessage = null
        val response = fetchNavigationHistory(serverUrl, pairingCode)
        isLoading = false
        if (response.success) {
            records = response.records.orEmpty()
        } else {
            errorMessage = response.message ?: "查詢導航紀錄失敗"
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .safeDrawingPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            FamilyModeHeader(
                pairingCode = pairingCode,
                onRegenerateCode = onRegenerateCode,
                onSwitchToBlindMode = onSwitchToBlindMode
            )

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📋", fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "導航紀錄列表 (配對碼: ${pairingCode ?: "------"})",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(12.dp))

            when {
                isLoading -> CenteredHint("載入中…")
                errorMessage != null -> CenteredHint(errorMessage ?: "", isError = true)
                records.isEmpty() -> CenteredHint("尚無導航紀錄")
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(records, key = { it.navigation_id }) { record ->
                        NavigationRecordCard(
                            record = record,
                            onViewDetail = { selectedNavigationId = record.navigation_id }
                        )
                    }
                }
            }
        }

        val navigationId = selectedNavigationId
        if (navigationId != null && pairingCode != null) {
            NavigationDetailOverlay(
                serverUrl = serverUrl,
                navigationId = navigationId,
                pairingCode = pairingCode,
                onDismiss = { selectedNavigationId = null }
            )
        }
    }
}

@Composable
private fun FamilyModeHeader(
    pairingCode: String?,
    onRegenerateCode: () -> Unit,
    onSwitchToBlindMode: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("👪", fontSize = 22.sp)
            Spacer(Modifier.width(8.dp))
            Text("家屬模式", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF29527A))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "盲人配對碼: 🔑 ${pairingCode ?: "------"}",
                    color = Color(0xFF9AD1FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onRegenerateCode,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("修改配對碼", fontSize = 13.sp)
            }
        }

        Button(
            onClick = onSwitchToBlindMode,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD54F)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text("切換回盲人模式", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CenteredHint(text: String, isError: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = if (isError) Color(0xFFE57373) else Color.White.copy(alpha = 0.5f))
    }
}

@Composable
private fun NavigationRecordCard(record: NavigationRecordDto, onViewDetail: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1B2333))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF66BB6A)))
            Spacer(Modifier.width(8.dp))
            Text(
                formatDateTime(record.started_at ?: record.created_at),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Spacer(Modifier.weight(1f))
            if (record.alert_count > 0) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFB71C1C))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "⚠️ ${record.alert_count} 次警報",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "起點: ${record.start_address ?: "未知"} → 終點: ${record.end_address ?: "未知"} " +
                "(${formatDurationMinutes(record.duration_seconds)})",
            color = Color(0xFFCFD8DC),
            fontSize = 14.sp
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onViewDetail),
            horizontalArrangement = Arrangement.End
        ) {
            Text("查看詳情 ›", color = Color(0xFF4DD0E1), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NavigationDetailOverlay(
    serverUrl: String,
    navigationId: Int,
    pairingCode: String,
    onDismiss: () -> Unit
) {
    var detail by remember { mutableStateOf<NavigationDetailResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(navigationId) {
        isLoading = true
        detail = fetchNavigationDetail(serverUrl, navigationId, pairingCode)
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xEE0A0A0A))) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Text("←", color = Color.White, fontSize = 22.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text("導航詳情", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            val response = detail
            when {
                isLoading -> CenteredHint("載入中…")
                response?.success != true -> CenteredHint(response?.message ?: "讀取失敗", isError = true)
                else -> {
                    val nav = response.navigation
                    val path = response.path.orEmpty()
                    val alerts = response.alerts.orEmpty()

                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text("起點: ${nav?.start_address ?: "未知"}", color = Color.LightGray, fontSize = 13.sp)
                        Text("終點: ${nav?.end_address ?: "未知"}", color = Color.LightGray, fontSize = 13.sp)
                        Text(
                            "時長: ${formatDurationMinutes(nav?.duration_seconds)}　警報: ${alerts.size} 次",
                            color = Color(0xFF4DD0E1),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                    ) {
                        NavigationRouteMap(path = path, alerts = alerts)
                    }

                    if (alerts.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(alerts, key = { it.detection_id }) { alert ->
                                Text(
                                    "${formatDateTime(alert.occurred_at)}　${alert.description ?: alert.object_name}",
                                    color = Color(0xFFFF8A80),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * 路線地圖：需要 Maps SDK for Android 金鑰（見 app/build.gradle.kts 讀取
 * local.properties 的 MAPS_API_KEY）。沒有設定金鑰時地圖圖磚會是空白，
 * 但 Polyline/Marker 邏輯不受影響，設定好金鑰後即可正常顯示。
 */
@Composable
private fun NavigationRouteMap(path: List<LatLngDto2>, alerts: List<AlertDto>) {
    val pathLatLngs = remember(path) { path.map { LatLng(it.lat, it.lng) } }
    val alertPoints = remember(alerts) {
        alerts.mapNotNull { alert ->
            val lat = alert.latitude
            val lng = alert.longitude
            if (lat != null && lng != null) alert to LatLng(lat, lng) else null
        }
    }

    val cameraPositionState = rememberCameraPositionState()
    var isMapLoaded by remember { mutableStateOf(false) }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        onMapLoaded = { isMapLoaded = true }
    ) {
        if (pathLatLngs.size > 1) {
            Polyline(points = pathLatLngs, color = Color(0xFF4DD0E1), width = 10f)
        }
        alertPoints.forEach { (alert, position) ->
            Marker(
                state = MarkerState(position = position),
                title = alert.object_name,
                snippet = alert.description
            )
        }
    }

    LaunchedEffect(isMapLoaded, pathLatLngs, alertPoints) {
        if (!isMapLoaded) return@LaunchedEffect
        val allPoints = pathLatLngs + alertPoints.map { it.second }
        if (allPoints.isEmpty()) return@LaunchedEffect

        if (allPoints.size == 1) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(allPoints[0], 17f)
        } else {
            val boundsBuilder = LatLngBounds.Builder()
            allPoints.forEach { boundsBuilder.include(it) }
            runCatching {
                cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 96))
            }
        }
    }
}

private val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
private val displayFormatter = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())

private fun formatDateTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return runCatching { isoParser.parse(iso)?.let(displayFormatter::format) }.getOrNull() ?: iso
}

private fun formatDurationMinutes(seconds: Int?): String {
    if (seconds == null) return "—"
    return "${seconds / 60} 分鐘"
}
