/*
 * App 內建家屬模式（配對碼 + 導航紀錄列表 + 單趟詳情）。
 *
 * 與 family.js（即時地圖儀表板，目前前端網頁版本先擱置）是兩個獨立模組，
 * 各自掛載於 /api 之下，互不影響、互不依賴。
 *
 * App 目前沒有登入機制，因此採用「裝置配對碼」：
 *   1. App 第一次啟動時呼叫 POST /api/devices/register，
 *      後端自動建立一組 users 資料列並產生 6 碼配對碼。
 *   2. 家屬模式畫面顯示這組配對碼（也可以 POST /api/devices/:user_id/regenerate-code 換一組）。
 *   3. 家屬模式的導航紀錄列表/詳情，皆以這組配對碼查詢
 *      （GET /api/family/navigation-history?pairing_code=XXXXXX）。
 *
 * 因為建立裝置時就會建立一筆真正的 users 資料列並回傳 user_id，
 * 所以既有的 /api/navigation/directions、/api/environment-logs 等 API
 * 完全不用修改資料表結構，App 只要把這個 user_id 帶進既有呼叫即可。
 */

const express = require("express");
const crypto = require("crypto");

const db = require("./db");
const { computeStayPoints } = require("./geo");

const router = express.Router();

const PAIRING_CODE_PATTERN = /^\d{6}$/;

function parsePositiveInteger(value) {
    const number = Number(value);
    if (!Number.isInteger(number) || number <= 0) {
        return null;
    }
    return number;
}

function isValidPairingCode(value) {
    return typeof value === "string" && PAIRING_CODE_PATTERN.test(value);
}

/**
 * 算出這趟導航「目前顯示用」的時長。
 *
 * 導航還在進行中（status = 'active'，還沒有人按「結束導航」，也還沒被
 * 自動判定抵達）時，actual_duration_seconds 是 NULL，原本會退回顯示
 * duration_seconds——但那其實是規劃路線當下 Google 估計的「預期」時間，
 * 是一個固定不變的數字，不會隨著實際走了多久而更新，家屬中途查看時看到
 * 的內容跟真實進度無關，容易誤會成「走了這麼久」。
 *
 * 改成：導航還在進行中時，直接用「現在時間 - started_at」即時算出真正
 * 已經走了多久，家屬不用等視障使用者按下「結束導航」，隨時查看都能看到
 * 正確、會持續增加的進行中時長。
 */
function resolveDisplayDurationSeconds(row) {
    if (row.actual_duration_seconds != null) return row.actual_duration_seconds;

    if (row.status === "active" && row.started_at) {
        const elapsedSeconds = Math.floor(
            (Date.now() - new Date(row.started_at).getTime()) / 1000
        );
        if (elapsedSeconds >= 0) return elapsedSeconds;
    }

    return row.duration_seconds;
}

/**
 * 產生一組尚未被使用的 6 碼配對碼。
 * 碼空間有 900000 組，此 App 的預期使用量極低，用「先查詢再使用」已經足夠，
 * 真正的併發碰撞則交給 device_profiles.pairing_code 的 UNIQUE 限制擋下。
 */
async function generateUniquePairingCode(connection) {
    for (let attempt = 0; attempt < 10; attempt++) {
        const code = String(Math.floor(100000 + Math.random() * 900000));
        const [rows] = await connection.execute(
            `SELECT 1 FROM device_profiles WHERE pairing_code = ? LIMIT 1`,
            [code]
        );
        if (rows.length === 0) return code;
    }
    throw new Error("Failed to generate a unique pairing code");
}

async function resolveUserIdFromPairingCode(pairingCode) {
    const [rows] = await db.execute(
        `SELECT user_id FROM device_profiles WHERE pairing_code = ?`,
        [pairingCode]
    );
    return rows[0]?.user_id ?? null;
}

/* =========================================================
   1. 裝置註冊（沒有帳號的裝置，第一次啟動時呼叫一次）
========================================================= */

router.post("/devices/register", async (req, res) => {
    try {
        const { device_id, display_name } = req.body;

        if (typeof device_id !== "string" || !device_id.trim()) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_REQUEST",
                    message: "device_id 為必填欄位",
                    retryable: false
                }
            });
        }
        const deviceId = device_id.trim();

        const [existingRows] = await db.execute(
            `SELECT user_id, pairing_code, display_name
             FROM device_profiles WHERE device_id = ?`,
            [deviceId]
        );

        if (existingRows.length > 0) {
            const existing = existingRows[0];
            return res.status(200).json({
                success: true,
                user_id: existing.user_id,
                pairing_code: existing.pairing_code,
                display_name: existing.display_name
            });
        }

        const name =
            typeof display_name === "string" && display_name.trim()
                ? display_name.trim()
                : "視障使用者";

        // 此裝置沒有真實帳號，合成一組不會用來登入的 email/密碼雜湊，
        // 只是為了滿足既有 users 表 email/password_hash 為必填的限制。
        const syntheticEmail = `device-${deviceId}@blindhelper.local`;
        const syntheticPasswordHash = crypto.randomBytes(32).toString("hex");

        const connection = await db.getConnection();
        try {
            await connection.beginTransaction();

            const [userResult] = await connection.execute(
                `INSERT INTO users (name, email, password_hash) VALUES (?, ?, ?)`,
                [name, syntheticEmail, syntheticPasswordHash]
            );
            const userId = userResult.insertId;

            const pairingCode = await generateUniquePairingCode(connection);

            await connection.execute(
                `INSERT INTO device_profiles (user_id, device_id, pairing_code, display_name)
                 VALUES (?, ?, ?, ?)`,
                [userId, deviceId, pairingCode, name]
            );

            await connection.commit();

            return res.status(201).json({
                success: true,
                user_id: userId,
                pairing_code: pairingCode,
                display_name: name
            });
        } catch (error) {
            await connection.rollback();

            // 極少數併發下，兩個請求同時註冊同一個 device_id：
            // 其中一個會撞到 UNIQUE 限制，直接回查既有資料即可。
            if (error?.code === "ER_DUP_ENTRY") {
                const [rows] = await db.execute(
                    `SELECT user_id, pairing_code, display_name
                     FROM device_profiles WHERE device_id = ?`,
                    [deviceId]
                );
                if (rows.length > 0) {
                    return res.status(200).json({
                        success: true,
                        user_id: rows[0].user_id,
                        pairing_code: rows[0].pairing_code,
                        display_name: rows[0].display_name
                    });
                }
            }

            throw error;
        } finally {
            connection.release();
        }
    } catch (error) {
        console.error("Register device failed:", error);
        return res.status(500).json({
            success: false,
            error: {
                code: "DEVICE_REGISTER_FAILED",
                message: "裝置註冊失敗",
                retryable: false
            }
        });
    }
});

/* =========================================================
   2. 重新產生配對碼（對應「修改配對碼」按鈕）
========================================================= */

router.post("/devices/:user_id/regenerate-code", async (req, res) => {
    const userId = parsePositiveInteger(req.params.user_id);

    if (userId == null) {
        return res.status(400).json({
            success: false,
            error: {
                code: "INVALID_USER_ID",
                message: "user_id 必須是大於 0 的整數",
                retryable: false
            }
        });
    }

    const connection = await db.getConnection();
    try {
        await connection.beginTransaction();

        const newCode = await generateUniquePairingCode(connection);

        const [result] = await connection.execute(
            `UPDATE device_profiles SET pairing_code = ? WHERE user_id = ?`,
            [newCode, userId]
        );

        if (result.affectedRows === 0) {
            await connection.rollback();
            return res.status(404).json({
                success: false,
                error: {
                    code: "DEVICE_PROFILE_NOT_FOUND",
                    message: "找不到此裝置的配對資料",
                    retryable: false
                }
            });
        }

        await connection.commit();
        return res.status(200).json({ success: true, pairing_code: newCode });
    } catch (error) {
        await connection.rollback();
        console.error("Regenerate pairing code failed:", error);
        return res.status(500).json({
            success: false,
            error: {
                code: "REGENERATE_CODE_FAILED",
                message: "重新產生配對碼失敗",
                retryable: false
            }
        });
    } finally {
        connection.release();
    }
});

/* =========================================================
   3. 導航紀錄列表（家屬模式主畫面）
========================================================= */

router.get("/family/navigation-history", async (req, res) => {
    try {
        const pairingCode = String(req.query.pairing_code || "").trim();

        if (!isValidPairingCode(pairingCode)) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_PAIRING_CODE",
                    message: "pairing_code 必須是 6 碼數字",
                    retryable: false
                }
            });
        }

        const userId = await resolveUserIdFromPairingCode(pairingCode);
        if (userId == null) {
            return res.status(404).json({
                success: false,
                error: {
                    code: "PAIRING_CODE_NOT_FOUND",
                    message: "找不到此配對碼對應的裝置",
                    retryable: false
                }
            });
        }

        const [rows] = await db.execute(
            `SELECT
                nr.navigation_id, nr.start_address, nr.end_address,
                nr.distance_meters, nr.duration_seconds,
                nr.actual_distance_meters, nr.actual_duration_seconds,
                nr.status, nr.started_at, nr.ended_at, nr.created_at,
                COUNT(od.detection_id) AS alert_count
             FROM navigation_records nr
             LEFT JOIN object_detections od ON od.navigation_id = nr.navigation_id
             WHERE nr.user_id = ?
             GROUP BY nr.navigation_id
             ORDER BY nr.created_at DESC
             LIMIT 100`,
            [userId]
        );

        return res.status(200).json({
            success: true,
            pairing_code: pairingCode,
            records: rows.map((row) => ({
                navigation_id: row.navigation_id,
                start_address: row.start_address,
                end_address: row.end_address,
                distance_meters: row.actual_distance_meters ?? row.distance_meters,
                duration_seconds: resolveDisplayDurationSeconds(row),
                alert_count: Number(row.alert_count),
                status: row.status,
                started_at: row.started_at,
                ended_at: row.ended_at,
                created_at: row.created_at
            }))
        });
    } catch (error) {
        console.error("Get navigation history failed:", error);
        return res.status(500).json({
            success: false,
            error: {
                code: "NAVIGATION_HISTORY_FAILED",
                message: "查詢導航紀錄失敗",
                retryable: false
            }
        });
    }
});

/* =========================================================
   4. 單趟導航詳情（「查看詳情」：路徑地圖 + 警報標記）
========================================================= */

router.get("/family/navigation-history/:navigation_id", async (req, res) => {
    try {
        const navigationId = parsePositiveInteger(req.params.navigation_id);
        const pairingCode = String(req.query.pairing_code || "").trim();

        if (navigationId == null || !isValidPairingCode(pairingCode)) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_REQUEST",
                    message: "navigation_id 與 pairing_code 格式不正確",
                    retryable: false
                }
            });
        }

        const userId = await resolveUserIdFromPairingCode(pairingCode);
        if (userId == null) {
            return res.status(404).json({
                success: false,
                error: {
                    code: "PAIRING_CODE_NOT_FOUND",
                    message: "找不到此配對碼對應的裝置",
                    retryable: false
                }
            });
        }

        const [navRows] = await db.execute(
            `SELECT navigation_id, start_address, end_address,
                    start_latitude, start_longitude, end_latitude, end_longitude,
                    distance_meters, duration_seconds,
                    actual_distance_meters, actual_duration_seconds,
                    route_summary, status, started_at, ended_at, created_at
             FROM navigation_records
             WHERE navigation_id = ? AND user_id = ?`,
            [navigationId, userId]
        );

        const record = navRows[0];
        if (!record) {
            return res.status(404).json({
                success: false,
                error: {
                    code: "NAVIGATION_NOT_FOUND",
                    message: "找不到此導航紀錄，或此配對碼無權限查看",
                    retryable: false
                }
            });
        }

        const [alertRows] = await db.execute(
            `SELECT detection_id, object_name, confidence, description,
                    latitude, longitude, created_at
             FROM object_detections
             WHERE navigation_id = ?
             ORDER BY created_at ASC`,
            [navigationId]
        );

        // 這趟導航進行中，App 每隔一段時間回報的實際座標（見下方
        // POST /api/navigation/:navigation_id/location-ping），拿來離線算出
        // 「在同一個地方停留超過 5 分鐘」的停留點，不需要額外的即時狀態。
        const [locationRows] = await db.execute(
            `SELECT latitude, longitude, recorded_at
             FROM location_logs
             WHERE navigation_id = ?
             ORDER BY recorded_at ASC`,
            [navigationId]
        );

        const stayPoints = computeStayPoints(locationRows);

        // 地圖上要畫的路徑，優先用這趟導航實際回報回來的 GPS 軌跡
        // （locationRows，來自 App 導航中定期回報的座標），這樣家屬看到的
        // 才是「導盲人真正走過的路」，不是 Google 規劃出來的理想路線。
        // 只有在完全沒有實際軌跡資料時（例如很舊、這個功能上線前的紀錄，
        // 或這趟導航太短還沒來得及回報半個座標點），才退回用 route_summary
        // 規劃路線當備援，確保地圖至少還有東西可以顯示。
        let path = locationRows.map((row) => ({
            lat: Number(row.latitude),
            lng: Number(row.longitude)
        }));

        if (path.length === 0) {
            try {
                const summary = record.route_summary ? JSON.parse(record.route_summary) : null;
                for (const step of summary?.steps ?? []) {
                    if (step.start_location) path.push(step.start_location);
                    if (step.end_location) path.push(step.end_location);
                }
            } catch (parseError) {
                console.error("Parse route_summary failed:", parseError.message);
            }
        }

        return res.status(200).json({
            success: true,
            navigation: {
                navigation_id: record.navigation_id,
                start_address: record.start_address,
                end_address: record.end_address,
                start_location:
                    record.start_latitude != null
                        ? { lat: Number(record.start_latitude), lng: Number(record.start_longitude) }
                        : null,
                end_location:
                    record.end_latitude != null
                        ? { lat: Number(record.end_latitude), lng: Number(record.end_longitude) }
                        : null,
                distance_meters: record.actual_distance_meters ?? record.distance_meters,
                duration_seconds: resolveDisplayDurationSeconds(record),
                status: record.status,
                started_at: record.started_at,
                ended_at: record.ended_at
            },
            path,
            alerts: alertRows.map((row) => ({
                detection_id: row.detection_id,
                object_name: row.object_name,
                confidence: row.confidence == null ? null : Number(row.confidence),
                description: row.description,
                latitude: row.latitude == null ? null : Number(row.latitude),
                longitude: row.longitude == null ? null : Number(row.longitude),
                occurred_at: row.created_at
            })),
            stay_points: stayPoints.map((stay, index) => ({
                stay_point_id: index + 1,
                latitude: stay.latitude,
                longitude: stay.longitude,
                arrived_at: stay.arrived_at,
                left_at: stay.left_at,
                duration_seconds: stay.duration_seconds
            }))
        });
    } catch (error) {
        console.error("Get navigation detail failed:", error);
        return res.status(500).json({
            success: false,
            error: {
                code: "NAVIGATION_DETAIL_FAILED",
                message: "查詢導航詳情失敗",
                retryable: false
            }
        });
    }
});

/* =========================================================
   5. 導航進行中的位置回報（供事後算出停留點）

   App 在導航進行中，每隔約 20 秒呼叫一次（見 MainScreen.kt 的
   reportNavigationLocation()），單純把座標存起來，不在這裡做任何
   即時判斷——停留點是在「查看詳情」當下才一次算出來（見上方
   GET /api/family/navigation-history/:navigation_id）。
========================================================= */

router.post("/navigation/:navigation_id/location-ping", async (req, res) => {
    try {
        const navigationId = parsePositiveInteger(req.params.navigation_id);
        const userId = parsePositiveInteger(req.body.user_id);
        const latitude = Number(req.body.latitude);
        const longitude = Number(req.body.longitude);

        if (
            navigationId == null ||
            userId == null ||
            !Number.isFinite(latitude) ||
            latitude < -90 ||
            latitude > 90 ||
            !Number.isFinite(longitude) ||
            longitude < -180 ||
            longitude > 180
        ) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_REQUEST",
                    message: "navigation_id、user_id、latitude、longitude 格式不正確",
                    retryable: false
                }
            });
        }

        const recordedAt = req.body.recorded_at ? new Date(req.body.recorded_at) : new Date();
        if (Number.isNaN(recordedAt.getTime())) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_TIMESTAMP",
                    message: "recorded_at 格式不正確",
                    retryable: false
                }
            });
        }

        // 只確認這趟導航真的屬於這個 user_id，避免任意寫入別人的紀錄。
        const [navRows] = await db.execute(
            `SELECT navigation_id FROM navigation_records WHERE navigation_id = ? AND user_id = ?`,
            [navigationId, userId]
        );

        if (navRows.length === 0) {
            return res.status(404).json({
                success: false,
                error: {
                    code: "NAVIGATION_NOT_FOUND",
                    message: "找不到此導航紀錄",
                    retryable: false
                }
            });
        }

        await db.execute(
            `INSERT INTO location_logs (user_id, navigation_id, latitude, longitude, recorded_at)
             VALUES (?, ?, ?, ?, ?)`,
            [userId, navigationId, latitude, longitude, recordedAt]
        );

        return res.status(201).json({ success: true });
    } catch (error) {
        console.error("Navigation location ping failed:", error);
        return res.status(500).json({
            success: false,
            error: {
                code: "LOCATION_PING_FAILED",
                message: "位置回報寫入失敗",
                retryable: false
            }
        });
    }
});

module.exports = router;
