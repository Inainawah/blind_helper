# Blind Helper 家屬地圖儀表板 API

本文件說明家屬地圖儀表板模組（`backend/family.js`）提供的三支 API：定位追蹤心跳、
靜止保底心跳，以及家屬端讀取入口。此模組為新增功能，掛載於既有 `server.js` 的
`/api` 路徑之下，未修改任何既有路由。

資料表：`location_logs`、`stay_points`、`device_status`（見 `doc/api/blind_helper_schema.sql`）。

---

# 1. POST /api/tracking/ping

## 功能

App 於前景/導航中定期回報目前位置（建議每 10~30 秒一次）。後端據此執行：

- **移動判斷**：與上一筆已記錄座標距離 > 5 公尺才寫入 `location_logs`（過濾微小漂移）。
- **停留點判斷**：若使用者持續停留在同一個 5 公尺半徑內達 5 分鐘以上，自動建立 `stay_points`
  紀錄；持續停留時 `left_at` 維持 `NULL`，直到使用者離開該範圍才關閉。
- **心跳**：同時更新 `device_status` 的電量、充電狀態與 `last_seen_at`。

## Request

```http
POST /api/tracking/ping
Content-Type: application/json
```

```json
{
  "user_id": 1,
  "latitude": 25.033964,
  "longitude": 121.564468,
  "accuracy": 5.0,
  "battery_level": 82,
  "is_charging": false,
  "recorded_at": "2026-08-17T09:30:00Z"
}
```

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| user_id | Integer | 是 | 使用者 ID |
| latitude / longitude | Number | 是 | GPS 座標 |
| accuracy | Number | 否 | GPS 定位精準度（公尺） |
| battery_level | Integer 0~100 | 否 | 目前電量百分比 |
| is_charging | Boolean | 否 | 是否正在充電 |
| recorded_at | ISO 8601 字串 | 否 | 定位時間，預設為伺服器收到請求的時間 |

## Success Response

HTTP `200 OK`

```json
{
  "success": true,
  "logged_movement": true,
  "opened_stay_point_id": null,
  "closed_stay_point_id": null
}
```

---

# 2. POST /api/device/heartbeat

## 功能

App 靜止或背景執行時的保底心跳，僅回報電量與狀態、不含定位，建議**每 3 分鐘**呼叫一次。
後端若超過 6 分鐘未收到任何心跳（包含 `/api/tracking/ping`），家屬端會將裝置顯示為離線。

## Request

```http
POST /api/device/heartbeat
Content-Type: application/json
```

```json
{
  "user_id": 1,
  "battery_level": 76,
  "is_charging": true,
  "app_version": "1.0.0"
}
```

## Success Response

HTTP `200 OK`

```json
{ "success": true, "message": "Heartbeat recorded" }
```

---

# 3. GET /api/family/overview

## 功能

家屬模式儀表板的唯一讀取入口，一次回傳：裝置連線/電量狀態、目前位置、
今日移動軌跡（`location_logs`）與今日停留點（`stay_points`，含尚未離開的進行中停留）。

## Request

```http
GET /api/family/overview?user_id=1
```

## Success Response

HTTP `200 OK`

```json
{
  "success": true,
  "device_status": {
    "battery_level": 82,
    "is_charging": false,
    "is_online": true,
    "last_seen_at": "2026-08-17T09:30:00.000Z"
  },
  "current_position": { "latitude": 25.033964, "longitude": 121.564468 },
  "path": [
    { "log_id": 101, "latitude": 25.0330, "longitude": 121.5640, "accuracy": 5.0, "recorded_at": "2026-08-17T01:00:00.000Z" }
  ],
  "stay_points": [
    {
      "stay_point_id": 12,
      "latitude": 25.0335,
      "longitude": 121.5645,
      "radius_meters": 5,
      "arrived_at": "2026-08-17T01:10:00.000Z",
      "left_at": null,
      "is_ongoing": true,
      "duration_seconds": 480,
      "address": null
    }
  ]
}
```

`is_online` 由 `last_seen_at` 是否在 6 分鐘內即時計算得出，不需另外查表。
`stay_points[].address` 只有在該停留點「已結束」後，才會透過反向地理編碼非同步補上。
