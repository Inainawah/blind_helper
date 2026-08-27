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
import com.google.android.gms.maps.model.BitmapDescriptorFactory
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
 * App 內建家屬模式主畫面：頂部配對碼 + 查詢輸入框 + 導航紀錄列表，
 * 點「查看詳情」開啟 [NavigationDetailOverlay]（路線地圖 + 警報標記）。
 *
 * 「本機配對碼」跟「目前查詢的配對碼」是分開的兩件事：
 *   - 本機配對碼：這台裝置自己的碼（給家屬念出來/輸入用）。
 *   - 查詢配對碼：預設等於本機配對碼（自己查自己），但可以手動改輸入
 *     別人的配對碼，改看別人（例如視障者那台裝置）的導航紀錄。
 */
@Composable
fun FamilyModeScreen(
    serverUrl: String,
    deviceProfile: DeviceProfile?,
    onRegenerateCode: () -> Unit,
    onRetryRegistration: () -> Unit,
    onSwitchToBlindMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    var records by remember { mutableStateOf<List<NavigationRecordDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedNavigationId by remember { mutableStateOf<Int?>(null) }

    var queryInput by remember { mutableStateOf("") }
    var activePairingCode by remember { mutableStateOf<String?>(null) }

    // 裝置註冊（拿到本機配對碼）如果還沒成功，進到家屬模式時自動再試一次，
    // 不用等使用者自己重開 App。
    LaunchedEffect(deviceProfile) {
        if (deviceProfile == null) onRetryRegistration()
    }

    // 拿到本機配對碼後，預設拿來查詢自己；使用者之後可以手動改輸入框查別人。
    LaunchedEffect(deviceProfile?.pairingCode) {
        val ownCode = deviceProfile?.pairingCode
        if (ownCode != null && activePairingCode == null) {
            queryInput = ownCode
            activePairingCode = ownCode
        }
    }

    LaunchedEffect(activePairingCode) {
        val code = activePairingCode
        if (code.isNullOrBlank()) return@LaunchedEffect
        isLoading = true
        errorMessage = null
        val response = fetchNavigationHistory(serverUrl, code)
        isLoading = false
        if (response.success) {
            records = response.records.orEmpty()
        } else {
            records = emptyList()
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
                pairingCode = deviceProfile?.pairingCode,
                onRegenerateCode = onRegenerateCode,
                onRetryRegistration = onRetryRegistration,
                onSwitchToBlindMode = onSwitchToBlindMode
            )

            Spacer(Modifier.height(16.dp))

            PairingCodeQueryRow(
                queryInput = queryInput,
                onQueryInputChange = { queryInput = it.filter(Char::isDigit).take(6) },
                onSubmit = { if (queryInput.length == 6) activePairingCode = queryInput }
            )

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📋", fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "導航紀錄列表 (配對碼: ${activePairingCode ?: "------"})",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(12.dp))

            when {
                activePairingCode == null -> CenteredHint("請先輸入 6 碼配對碼查詢")
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
        val detailPairingCode = activePairingCode
        if (navigationId != null && detailPairingCode != null) {
            NavigationDetailOverlay(
                serverUrl = serverUrl,
                navigationId = navigationId,
                pairingCode = detailPairingCode,
                onDismiss = { selectedNavigationId = null }
            )
        }
    }
}

@Composable
private fun PairingCodeQueryRow(
    queryInput: String,
    onQueryInputChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("查詢配對碼：", color = Color.White, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        TextField(
            value = queryInput,
            onValueChange = onQueryInputChange,
            singleLine = true,
            placeholder = { Text("輸入 6 碼配對碼") },
            modifier = Modifier.width(160.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1B2333),
                unfocusedContainerColor = Color(0xFF1B2333),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onSubmit,
            enabled = queryInput.length == 6,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4DD0E1))
        ) {
            Text("查詢", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FamilyModeHeader(
    pairingCode: String?,
    onRegenerateCode: () -> Unit,
    onRetryRegistration: () -> Unit,
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
                    "本機配對碼: 🔑 ${pairingCode ?: "------"}",
                    color = Color(0xFF9AD1FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (pairingCode == null) {
                Spacer(Modifier.width(8.dp))
                // 裝置註冊還沒成功（例如網路不穩），提供手動重試。
                // 「修改配對碼」不需要用到，已移除；這裡只保留註冊失敗時的重試按鈕。
                OutlinedButton(
                    onClick = onRetryRegistration,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("重新取得配對碼", fontSize = 13.sp)
                }
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
                    val stayPoints = response.stay_points.orEmpty()

                    // 橫向裝置畫面較寬，改成左右兩欄：左邊文字資訊、右邊地圖佔滿剩餘高度，
                    // 比原本「文字在上、地圖在下」更能善用橫向空間。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Text(
                                "起點: ${nav?.start_address ?: "未知"}",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "終點: ${nav?.end_address ?: "未知"}",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "時長: ${formatDurationMinutes(nav?.duration_seconds)}",
                                color = Color(0xFF4DD0E1),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "警報: ${alerts.size} 次",
                                color = Color(0xFF4DD0E1),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )

                            if (stayPoints.isNotEmpty()) {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "🕒 停留點（超過 5 分鐘）",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                LazyColumn(
                                    modifier = Modifier.heightIn(max = 160.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(stayPoints, key = { it.stay_point_id }) { stay ->
                                        Column {
                                            Text(
                                                "停留了 ${formatDurationMinutes(stay.duration_seconds)}" +
                                                    "（${formatTimeOnly(stay.arrived_at)} 抵達）",
                                                color = Color(0xFFFFD54F),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                "座標：${formatCoordinate(stay.latitude)}, ${formatCoordinate(stay.longitude)}",
                                                color = Color.White.copy(alpha = 0.6f),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.weight(1f))
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(16.dp))
                        ) {
                            NavigationRouteMap(path = path, stayPoints = stayPoints)
                        }
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
private fun NavigationRouteMap(
    path: List<LatLngDto2>,
    stayPoints: List<StayPointDto> = emptyList()
) {
    val pathLatLngs = remember(path) { path.map { LatLng(it.lat, it.lng) } }
    val stayMarkerPoints = remember(stayPoints) {
        stayPoints.map { it to LatLng(it.latitude, it.longitude) }
    }

    val cameraPositionState = rememberCameraPositionState()
    var isMapLoaded by remember { mutableStateOf(false) }

    // BitmapDescriptorFactory 一定要等地圖真正初始化完成（onMapLoaded 之後）才能呼叫，
    // 太早呼叫（例如剛進畫面、地圖底層元件都還沒建立時）會直接讓 App 閃退
    // （NullPointerException: IBitmapDescriptorFactory is not initialized）。
    // 地圖還沒準備好之前先用 null（Marker 會顯示預設紅色圖示），準備好後再換成藍色。
    val stayIcon = if (isMapLoaded) {
        remember(isMapLoaded) { BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE) }
    } else {
        null
    }

    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        onMapLoaded = { isMapLoaded = true }
    ) {
        if (pathLatLngs.size > 1) {
            Polyline(points = pathLatLngs, color = Color(0xFF4DD0E1), width = 10f)
        }
        // 警報紅點已移除，地圖上只標示停留點（藍色），畫面比較乾淨、聚焦在「停留多久」。
        stayMarkerPoints.forEach { (stay, position) ->
            Marker(
                state = MarkerState(position = position),
                title = "停留 ${formatDurationMinutes(stay.duration_seconds)}",
                snippet = "${formatTimeOnly(stay.arrived_at)} 抵達",
                icon = stayIcon
            )
        }
    }

    LaunchedEffect(isMapLoaded, pathLatLngs, stayMarkerPoints) {
        if (!isMapLoaded) return@LaunchedEffect
        val allPoints = pathLatLngs + stayMarkerPoints.map { it.second }
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
private val timeOnlyFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatDateTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return runCatching { isoParser.parse(iso)?.let(displayFormatter::format) }.getOrNull() ?: iso
}

private fun formatTimeOnly(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return runCatching { isoParser.parse(iso)?.let(timeOnlyFormatter::format) }.getOrNull() ?: iso
}

private fun formatDurationMinutes(seconds: Int?): String {
    if (seconds == null) return "—"
    return "${seconds / 60} 分鐘"
}

private fun formatCoordinate(value: Double): String {
    return String.format(Locale.US, "%.6f", value)
}
