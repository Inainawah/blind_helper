# POST /api/locations

## 功能
接收前端 GPS 經緯度並寫入 MySQL locations 資料表。

---

## Request

POST /api/locations

Body

```json
{
    "user_id": 1,
    "latitude": 25.033964,
    "longitude": 121.564468,
    "accuracy": 3.5,
    "address": "台北市信義區市府路"
}
```

---

## Success Response

HTTP 201

```json
{
    "success": true,
    "message": "Location created successfully",
    "location_id": 1
}
```

---

## Error Response

HTTP 404

```json
{
    "success": false,
    "message": "User not found"
}
```

---

## 測試結果


### 1. Postman 成功新增

成功送出 GPS 經緯度，API 回傳 HTTP 201 Created。

![Postman 成功](images/postman成功.png)
---
### 2. MySQL 資料寫入成功

確認 locations 資料表已成功新增定位資料。

![MySQL 資料寫入成功](images/MySQL.png)
---
### 3. Postman 錯誤測試

當 user_id 不存在時，API 回傳 HTTP 404 與 User not found。

![Postman 失敗](images/postman失敗.png)
---

## Reverse Geocoding API

### POST /api/locations/reverse-geocode

#### Request

```json
{
    "latitude": 25.033964,
    "longitude": 121.564468
}
```

#### Response

```json
{
    "success": true,
    "address": "110台灣臺北市信義區西村里信義路五段7號52樓"
}
```

#### 測試結果
![Reverse Geocoding](images/reverse-geocode-success.png)

# POST /api/navigation/directions

## Request

```json
{
  "current_latitude": 25.175617,
  "current_longitude": 121.450589,
  "destination": "捷運淡水站"
}
```

## Response

```json
{
  "success": true,
  "start_address": "251台灣新北市淡水區英專路151號",
  "end_address": "251台灣新北市淡水區中正路淡水",
  "distance": "1.2 公里",
  "duration": "16 分鐘",
  "steps": [
    {
      "step_order": 1,
      "instruction": "往南",
      "distance": "5 公尺",
      "duration": "1 分鐘"
    }
  ]
}
```
#### 測試結果
![directions](images/directions.png)