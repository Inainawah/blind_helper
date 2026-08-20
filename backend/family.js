/*
 * 家屬地圖儀表板模組。
 *
 * 獨立掛載於 /api 之下（見 server.js: app.use("/api", require("./family"))），
 * 不修改既有 server.js 中任何路由，符合「新增模組、零侵入」的原則。
 *
 * 提供三支 API：
 *   POST /api/tracking/ping     App 定位追蹤心跳（移動中 + 停留點偵測核心演算法）
 *   POST /api/device/heartbeat  App 靜止保底心跳（僅回報電量/狀態，不含定位）
 *   GET  /api/family/overview   家屬儀表板讀取入口（今日軌跡、停留點、裝置狀態）
 *
 * 資料表：location_logs、stay_points、device_status
 * （schema 定義於 doc/api/blind_helper_schema.sql）
 */

const express = require("express");
const axios = require("axios");

const db = require("./db");
const { distanceMeters } = require("./geo");

const router = express.Router();

/* =========================================================
   演算法參數
========================================================= */

// 判定「同一個停留群集」的半徑。
const STAY_RADIUS_M = 5;

// 移動中：兩點距離超過此值才寫入 location_logs（過濾 GPS 微小漂移）。
const MOVE_THRESHOLD_M = 5;

// 停留點：群集持續時間達到此秒數即產生 stay_point 紀錄。
// 需求描述停留 5~10 分鐘視為停留點，此處採 5 分鐘作為觸發下限；
// 使用者若持續停留更久，同一筆 stay_point 會持續延伸（left_at 維持 NULL），
// 實際停留時長於查詢端即時計算，因此不需要另外處理「10 分鐘」上限。
const STAY_MIN_DURATION_SECONDS = 5 * 60;

// 心跳：App 每 3 分鐘回報一次，超過此秒數未收到任何心跳（含 tracking/ping）視為離線。
const OFFLINE_THRESHOLD_SECONDS = 6 * 60;

/* =========================================================
   共用驗證函式
========================================================= */

function parsePositiveInteger(value) {
    const number = Number(value);
    if (!Number.isInteger(number) || number <= 0) {
        return null;
    }
    return number;
}

function parseLatitude(value) {
    const number = Number(value);
    if (!Number.isFinite(number) || number < -90 || number > 90) {
        return null;
    }
    return number;
}

function parseLongitude(value) {
    const number = Number(value);
    if (!Number.isFinite(number) || number < -180 || number > 180) {
        return null;
    }
    return number;
}

/**
 * 解析電量百分比。
 * 回傳 null 表示「未提供」（合法，允許只回報位置不回報電量）；
 * 回傳 undefined 表示「格式錯誤」，呼叫端應視為 400。
 */
function parseBatteryLevel(value) {
    if (value == null) return null;
    const number = Number(value);
    if (!Number.isFinite(number) || number < 0 || number > 100) {
        return undefined;
    }
    return Math.round(number);
}

function handleForeignKeyError(error, res) {
    if (error?.code === "ER_NO_REFERENCED_ROW_2") {
        return res.status(404).json({
            success: false,
            error: {
                code: "USER_NOT_FOUND",
                message: "找不到使用者",
                retryable: false
            }
        });
    }
    return null;
}

/* =========================================================
   停留點結束後的反向地理編碼（非阻塞，僅在停留點「關閉」時呼叫一次，
   避免對每一個 GPS 點都呼叫 Google API）
========================================================= */

async function reverseGeocodeStayPoint(stayPointId, point) {
    if (!process.env.GOOGLE_MAPS_API_KEY) return;

    const response = await axios.get(
        "https://maps.googleapis.com/maps/api/geocode/json",
        {
            params: {
                latlng: `${point.latitude},${point.longitude}`,
                language: "zh-TW",
                key: process.env.GOOGLE_MAPS_API_KEY
            },
            timeout: 5000
        }
    );

    const address = response.data?.results?.[0]?.formatted_address;
    if (!address) return;

    await db.execute(
        `UPDATE stay_points SET address = ? WHERE stay_point_id = ?`,
        [address, stayPointId]
    );
}

/* =========================================================
   取得（或建立）使用者的 device_status 列，並鎖定該列
   （必須在交易內呼叫）。
========================================================= */

async function getOrCreateDeviceStatusForUpdate(connection, userId) {
    const [existingRows] = await connection.execute(
        `SELECT * FROM device_status WHERE user_id = ? FOR UPDATE`,
        [userId]
    );

    if (existingRows.length > 0) {
        return existingRows[0];
    }

    await connection.execute(
        `INSERT INTO device_status (user_id) VALUES (?)`,
        [userId]
    );

    const [createdRows] = await connection.execute(
        `SELECT * FROM device_status WHERE user_id = ? FOR UPDATE`,
        [userId]
    );

    return createdRows[0];
}

/**
 * 只有在距離「上一筆已寫入 location_logs 的座標」超過 MOVE_THRESHOLD_M 公尺時，
 * 才寫入新的一筆 location_logs（過濾微小漂移）。
 *
 * @returns {Promise<boolean>} 是否實際寫入了新的一筆紀錄
 */
async function logIfMoved(connection, userId, point, recordedAt, accuracy) {
    const [lastRows] = await connection.execute(
        `SELECT latitude, longitude
         FROM location_logs
         WHERE user_id = ?
         ORDER BY recorded_at DESC
         LIMIT 1`,
        [userId]
    );

    const lastPoint = lastRows[0]
        ? {
            latitude: Number(lastRows[0].latitude),
            longitude: Number(lastRows[0].longitude)
        }
        : null;

    if (lastPoint && distanceMeters(lastPoint, point) <= MOVE_THRESHOLD_M) {
        return false;
    }

    const accuracyNumber = accuracy == null ? null : Number(accuracy);

    await connection.execute(
        `INSERT INTO location_logs
         (user_id, latitude, longitude, accuracy, recorded_at)
         VALUES (?, ?, ?, ?, ?)`,
        [
            userId,
            point.latitude,
            point.longitude,
            Number.isFinite(accuracyNumber) ? accuracyNumber : null,
            recordedAt
        ]
    );

    return true;
}

/* =========================================================
   1. 定位追蹤心跳（移動判斷 + 停留點偵測核心演算法）
========================================================= */

router.post("/tracking/ping", async (req, res) => {
    const {
        user_id,
        latitude,
        longitude,
        accuracy,
        battery_level,
        is_charging,
        recorded_at
    } = req.body;

    const userId = parsePositiveInteger(user_id);
    const lat = parseLatitude(latitude);
    const lng = parseLongitude(longitude);

    if (userId == null || lat == null || lng == null) {
        return res.status(400).json({
            success: false,
            error: {
                code: "INVALID_REQUEST",
                message: "user_id、latitude 與 longitude 為必填欄位，且需在合法範圍",
                retryable: false
            }
        });
    }

    const batteryLevel = parseBatteryLevel(battery_level);
    if (batteryLevel === undefined) {
        return res.status(400).json({
            success: false,
            error: {
                code: "INVALID_BATTERY_LEVEL",
                message: "battery_level 必須介於 0 到 100",
                retryable: false
            }
        });
    }

    const isCharging = is_charging == null ? null : Boolean(is_charging);

    const recordedAt = recorded_at ? new Date(recorded_at) : new Date();
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

    const currentPoint = { latitude: lat, longitude: lng };
    const connection = await db.getConnection();

    try {
        await connection.beginTransaction();

        const status = await getOrCreateDeviceStatusForUpdate(connection, userId);

        let loggedMovement = false;
        let openedStayPointId = null;
        let closedStayPointId = null;
        let closedStayPointCenter = null;

        if (status.cluster_started_at == null) {
            /*
             * 尚無停留群集（例如第一次回報，或上次群集剛被關閉）：
             * 以這個點作為新群集的起點，同時視為一次移動事件寫入軌跡。
             */
            await connection.execute(
                `UPDATE device_status
                 SET cluster_started_at = ?, cluster_latitude = ?, cluster_longitude = ?
                 WHERE user_id = ?`,
                [recordedAt, lat, lng, userId]
            );

            loggedMovement = await logIfMoved(
                connection, userId, currentPoint, recordedAt, accuracy
            );
        } else {
            const clusterPoint = {
                latitude: Number(status.cluster_latitude),
                longitude: Number(status.cluster_longitude)
            };
            const distanceFromCluster = distanceMeters(clusterPoint, currentPoint);

            if (distanceFromCluster <= STAY_RADIUS_M) {
                /*
                 * 仍在同一個停留半徑內：不算移動，不寫入 location_logs。
                 * 只要群集持續時間達門檻，就（第一次）建立 stay_points 紀錄，
                 * 之後持續停留只需維持 left_at = NULL，不必每次都寫入。
                 */
                const clusterDurationSeconds =
                    (recordedAt.getTime() -
                        new Date(status.cluster_started_at).getTime()) / 1000;

                if (
                    clusterDurationSeconds >= STAY_MIN_DURATION_SECONDS &&
                    status.open_stay_point_id == null
                ) {
                    const [insertResult] = await connection.execute(
                        `INSERT INTO stay_points
                         (user_id, center_latitude, center_longitude, radius_meters, arrived_at)
                         VALUES (?, ?, ?, ?, ?)`,
                        [
                            userId,
                            clusterPoint.latitude,
                            clusterPoint.longitude,
                            STAY_RADIUS_M,
                            status.cluster_started_at
                        ]
                    );

                    openedStayPointId = insertResult.insertId;

                    await connection.execute(
                        `UPDATE device_status SET open_stay_point_id = ? WHERE user_id = ?`,
                        [openedStayPointId, userId]
                    );
                }
            } else {
                /*
                 * 使用者已離開這個群集半徑：
                 * 若群集先前已成為正式的停留點，就把它關閉（寫入 left_at）；
                 * 接著以目前這個點重新起算一個新的群集，並視為一次移動事件。
                 */
                if (status.open_stay_point_id != null) {
                    await connection.execute(
                        `UPDATE stay_points SET left_at = ? WHERE stay_point_id = ?`,
                        [recordedAt, status.open_stay_point_id]
                    );
                    closedStayPointId = status.open_stay_point_id;
                    closedStayPointCenter = clusterPoint;
                }

                await connection.execute(
                    `UPDATE device_status
                     SET cluster_started_at = ?, cluster_latitude = ?, cluster_longitude = ?,
                         open_stay_point_id = NULL
                     WHERE user_id = ?`,
                    [recordedAt, lat, lng, userId]
                );

                loggedMovement = await logIfMoved(
                    connection, userId, currentPoint, recordedAt, accuracy
                );
            }
        }

        const updateFields = [
            "last_latitude = ?",
            "last_longitude = ?",
            "last_seen_at = ?"
        ];
        const updateParams = [lat, lng, recordedAt];

        if (batteryLevel != null) {
            updateFields.push("battery_level = ?");
            updateParams.push(batteryLevel);
        }
        if (isCharging != null) {
            updateFields.push("is_charging = ?");
            updateParams.push(isCharging);
        }
        updateParams.push(userId);

        await connection.execute(
            `UPDATE device_status SET ${updateFields.join(", ")} WHERE user_id = ?`,
            updateParams
        );

        await connection.commit();

        // 反向地理編碼放在交易 commit 之後、非阻塞執行，避免拖慢回應時間。
        if (closedStayPointId != null && closedStayPointCenter != null) {
            reverseGeocodeStayPoint(closedStayPointId, closedStayPointCenter).catch(
                (err) => {
                    console.error("Stay point reverse geocode failed:", err.message);
                }
            );
        }

        return res.status(200).json({
            success: true,
            logged_movement: loggedMovement,
            opened_stay_point_id: openedStayPointId,
            closed_stay_point_id: closedStayPointId
        });
    } catch (error) {
        await connection.rollback();

        const foreignKeyResponse = handleForeignKeyError(error, res);
        if (foreignKeyResponse) return foreignKeyResponse;

        console.error("Tracking ping failed:", error);

        return res.status(500).json({
            success: false,
            error: {
                code: "TRACKING_PING_FAILED",
                message: "定位追蹤資料寫入失敗",
                retryable: false
            }
        });
    } finally {
        connection.release();
    }
});

/* =========================================================
   2. 靜止保底心跳（僅電量/狀態，不含定位）
   建議 App 背景/靜止時每 3 分鐘呼叫一次。
========================================================= */

router.post("/device/heartbeat", async (req, res) => {
    try {
        const { user_id, battery_level, is_charging, app_version } = req.body;

        const userId = parsePositiveInteger(user_id);
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

        const batteryLevel = parseBatteryLevel(battery_level);
        if (batteryLevel === undefined) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_BATTERY_LEVEL",
                    message: "battery_level 必須介於 0 到 100",
                    retryable: false
                }
            });
        }

        const isCharging = is_charging == null ? null : Boolean(is_charging);

        await db.execute(
            `INSERT INTO device_status
             (user_id, battery_level, is_charging, app_version, last_seen_at)
             VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
             ON DUPLICATE KEY UPDATE
                battery_level = COALESCE(VALUES(battery_level), battery_level),
                is_charging = COALESCE(VALUES(is_charging), is_charging),
                app_version = COALESCE(VALUES(app_version), app_version),
                last_seen_at = CURRENT_TIMESTAMP`,
            [userId, batteryLevel, isCharging, app_version ?? null]
        );

        return res.status(200).json({
            success: true,
            message: "Heartbeat recorded"
        });
    } catch (error) {
        const foreignKeyResponse = handleForeignKeyError(error, res);
        if (foreignKeyResponse) return foreignKeyResponse;

        console.error("Device heartbeat failed:", error);

        return res.status(500).json({
            success: false,
            error: {
                code: "HEARTBEAT_FAILED",
                message: "心跳寫入失敗",
                retryable: false
            }
        });
    }
});

/* =========================================================
   3. 家屬儀表板讀取入口
   回傳今日移動軌跡、今日停留點（含進行中的停留）與裝置連線狀態。
========================================================= */

router.get("/family/overview", async (req, res) => {
    try {
        const userId = parsePositiveInteger(req.query.user_id);

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

        const [statusRows] = await db.execute(
            `SELECT battery_level, is_charging, last_latitude, last_longitude, last_seen_at
             FROM device_status
             WHERE user_id = ?`,
            [userId]
        );

        const status = statusRows[0] || null;

        const isOnline =
            status?.last_seen_at != null &&
            (Date.now() - new Date(status.last_seen_at).getTime()) / 1000 <=
            OFFLINE_THRESHOLD_SECONDS;

        const [pathRows] = await db.execute(
            `SELECT log_id, latitude, longitude, accuracy, recorded_at
             FROM location_logs
             WHERE user_id = ?
             AND DATE(CONVERT_TZ(recorded_at, '+00:00', '+08:00'))
                 = DATE(CONVERT_TZ(NOW(), '+00:00', '+08:00'))
             ORDER BY recorded_at ASC`,
            [userId]
        );

        const [stayPointRows] = await db.execute(
            `SELECT
                stay_point_id, center_latitude, center_longitude, radius_meters,
                arrived_at, left_at, address,
                CASE
                    WHEN left_at IS NULL
                        THEN GREATEST(TIMESTAMPDIFF(SECOND, arrived_at, NOW()), 0)
                    ELSE TIMESTAMPDIFF(SECOND, arrived_at, left_at)
                END AS duration_seconds
             FROM stay_points
             WHERE user_id = ?
             AND DATE(CONVERT_TZ(arrived_at, '+00:00', '+08:00'))
                 = DATE(CONVERT_TZ(NOW(), '+00:00', '+08:00'))
             ORDER BY arrived_at ASC`,
            [userId]
        );

        return res.status(200).json({
            success: true,

            device_status: status && {
                battery_level: status.battery_level,
                is_charging: Boolean(status.is_charging),
                is_online: isOnline,
                last_seen_at: status.last_seen_at
            },

            current_position:
                status?.last_latitude != null
                    ? {
                        latitude: Number(status.last_latitude),
                        longitude: Number(status.last_longitude)
                    }
                    : null,

            path: pathRows.map((row) => ({
                log_id: row.log_id,
                latitude: Number(row.latitude),
                longitude: Number(row.longitude),
                accuracy: row.accuracy == null ? null : Number(row.accuracy),
                recorded_at: row.recorded_at
            })),

            stay_points: stayPointRows.map((row) => ({
                stay_point_id: row.stay_point_id,
                latitude: Number(row.center_latitude),
                longitude: Number(row.center_longitude),
                radius_meters: row.radius_meters,
                arrived_at: row.arrived_at,
                left_at: row.left_at,
                is_ongoing: row.left_at == null,
                duration_seconds: Number(row.duration_seconds),
                address: row.address
            }))
        });
    } catch (error) {
        console.error("Get family overview failed:", error);

        return res.status(500).json({
            success: false,
            error: {
                code: "FAMILY_OVERVIEW_FAILED",
                message: "查詢家屬儀表板資料失敗",
                retryable: false
            }
        });
    }
});

module.exports = router;
