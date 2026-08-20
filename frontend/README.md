# Blind Helper 家屬地圖儀表板（Family Dashboard）

新增模組，獨立於既有系統之外，透過 [ModeSwitcher](src/components/ModeSwitcher.jsx)
在同一個網頁內做零侵入的條件渲染切換。

> 目前 repo 中沒有找到既有的網頁版導航 UI（本專案的視障導航體驗主要實作在
> Android App，見 `app/src/main/java/.../ui/main/MainScreen.kt`）。因此
> [App.jsx](src/App.jsx) 中「使用者導航模式」暫時掛的是
> [ExistingUserNavigationPlaceholder](src/components/ExistingUserNavigationPlaceholder.jsx)。
> 若之後要接上真正既有的網頁 UI，只需替換這一個元件即可，`ModeSwitcher` 與
> `FamilyDashboard` 都不需要跟著更動。

## 開發

```bash
cd frontend
cp .env.example .env   # 設定 VITE_API_BASE_URL 指向 backend/server.js
npm install
npm run dev
```

## 結構

```
src/
  App.jsx                          # 模式切換的最外層容器
  components/
    ModeSwitcher.jsx                # 使用者模式 / 家屬模式 切換按鈕
    ExistingUserNavigationPlaceholder.jsx
    FamilyDashboard.jsx             # 頂部狀態列 + 地圖
    FamilyMap.jsx                   # Leaflet：軌跡 Polyline / 停留點 Marker / 目前位置
  api/
    familyApi.js                    # 呼叫 GET /api/family/overview
```

家屬儀表板每 15 秒輪詢一次 `GET /api/family/overview?user_id=...`
（見 `backend/family_dashboard_api.md`），對應後端 `backend/family.js`。
