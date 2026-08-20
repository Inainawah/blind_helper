# Blind Helper App 內建家屬模式 API

本文件說明 `backend/family_pairing.js` 提供的四支 API：裝置註冊、重新產生配對碼、
導航紀錄列表、單趟導航詳情。這一組 API 是給 **Android App 內建的家屬模式畫面**
使用的（配對碼 + 導航紀錄列表 + 詳情地圖），跟 `backend/family.js`（即時地圖儀表板，
前端網頁版本，目前先擱置）是彼此獨立的兩個模組。

---

# 1. POST /api/devices/register

## 功能

App 第一次啟動時呼叫一次。用裝置本機產生的 `device_id`（例如存在
SharedPreferences 裡的一組 UUID）換取一個 `user_id`（沿用既有的 `users` 表，
讓既有的 `/api/navigation/directions`、`/api/environment-logs` 等 API 不用改資料表
就能直接使用）與一組 6 碼配對碼。同一個 `device_id` 重複呼叫會回傳同一組資料。

## Request

```json
{ "device_id": "b3f1c9de-...", "display_name": "爸爸的手機" }
```

## Success Response

HTTP `201 Created`（第一次註冊）或 `200 OK`（裝置已註冊過）

```json
{ "success": true, "user_id": 42, "pairing_code": "849206", "display_name": "爸爸的手機" }
```

---

# 2. POST /api/devices/:user_id/regenerate-code

## 功能

對應 App 內「修改配對碼」按鈕，重新產生一組配對碼（會讓舊碼立即失效）。

## Success Response

HTTP `200 OK`

```json
{ "success": true, "pairing_code": "273190" }
```

---

# 3. GET /api/family/navigation-history

## 功能

家屬模式主畫面：輸入配對碼查詢這個人的導航紀錄列表，含每趟導航的警報次數。

## Request

```http
GET /api/family/navigation-history?pairing_code=849206
```

## Success Response

HTTP `200 OK`

```json
{
  "success": true,
  "pairing_code": "849206",
  "records": [
    {
      "navigation_id": 101,
      "start_address": "捷運大安站 3號出口",
      "end_address": "大安森林公園入口",
      "distance_meters": 850,
      "duration_seconds": 1020,
      "alert_count": 3,
      "status": "completed",
      "started_at": "2026-08-14T02:15:00.000Z",
      "ended_at": "2026-08-14T02:32:00.000Z",
      "created_at": "2026-08-14T02:14:50.000Z"
    }
  ]
}
```

---

# 4. GET /api/family/navigation-history/:navigation_id

## 功能

對應 App 內「查看詳情」，回傳這趟導航的規劃路線座標（畫路徑用）與每一次警報發生
當下的座標／時間／描述（畫標記用）。

## Request

```http
GET /api/family/navigation-history/101?pairing_code=849206
```

## Success Response

HTTP `200 OK`

```json
{
  "success": true,
  "navigation": {
    "navigation_id": 101,
    "start_address": "捷運大安站 3號出口",
    "end_address": "大安森林公園入口",
    "distance_meters": 850,
    "duration_seconds": 1020,
    "status": "completed",
    "started_at": "2026-08-14T02:15:00.000Z",
    "ended_at": "2026-08-14T02:32:00.000Z"
  },
  "path": [
    { "lat": 25.0330, "lng": 121.5434 },
    { "lat": 25.0333, "lng": 121.5441 }
  ],
  "alerts": [
    {
      "detection_id": 555,
      "object_name": "機車",
      "confidence": 0.91,
      "description": "前方有 機車",
      "latitude": 25.0331,
      "longitude": 121.5437,
      "occurred_at": "2026-08-14T02:16:00.000Z"
    }
  ]
}
```

`path` 是**規劃路線**（來自建立導航時儲存的 Google 路線 step 座標），不是事後回放
的實際 GPS 軌跡；`alerts` 則是這趟導航進行中，相機偵測到危險物體當下的真實座標。
