const express = require("express");
const cors = require("cors");
const axios = require("axios");
require("dotenv").config();

const db = require("./db");

const app = express();

app.use(cors());
app.use(express.json());
app.use((req, res, next) => {
    const startTime = Date.now();

    console.log(
        `[REQUEST] ${req.method} ${req.originalUrl}`
    );

    res.on("finish", () => {
        const elapsedTime = Date.now() - startTime;

        console.log(
            `[RESPONSE] ${req.method} ${req.originalUrl} ` +
            `${res.statusCode} ${elapsedTime}ms`
        );
    });

    next();
});

/* =========================================================
   共用設定
========================================================= */

const GOOGLE_GEOCODING_URL =
    "https://maps.googleapis.com/maps/api/geocode/json";

const GOOGLE_DIRECTIONS_URL =
    "https://maps.googleapis.com/maps/api/directions/json";

const GOOGLE_API_TIMEOUT_MS = 5000;
const GOOGLE_API_MAX_ATTEMPTS = 3;

/* =========================================================
   共用函式
========================================================= */

/**
 * 等待指定毫秒數。
 *
 * @param {number} ms
 * @returns {Promise<void>}
 */
function delay(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 判斷 Axios 錯誤是否屬於可以重試的暫時性錯誤。
 *
 * 可重試：
 * - 連線逾時
 * - DNS 暫時失敗
 * - 連線中斷
 * - HTTP 429、500、502、503、504
 *
 * @param {unknown} error
 * @returns {boolean}
 */
function shouldRetry(error) {
    const retryableNetworkCodes = [
        "ECONNABORTED",
        "ETIMEDOUT",
        "ECONNRESET",
        "ENOTFOUND",
        "EAI_AGAIN"
    ];

    if (retryableNetworkCodes.includes(error?.code)) {
        return true;
    }

    const httpStatus = error?.response?.status;

    return [429, 500, 502, 503, 504].includes(httpStatus);
}

/**
 * 判斷錯誤是否為 Timeout。
 *
 * @param {unknown} error
 * @returns {boolean}
 */
function isTimeoutError(error) {
    return (
        error?.code === "ECONNABORTED" ||
        error?.code === "ETIMEDOUT"
    );
}

/**
 * 通用 Google API 請求函式。
 *
 * 最多嘗試三次：
 * 第一次失敗後等待 1 秒
 * 第二次失敗後等待 2 秒
 * 第三次失敗後拋出錯誤
 *
 * @param {string} url
 * @param {Record<string, unknown>} params
 * @returns {Promise<import("axios").AxiosResponse>}
 */
async function requestGoogleApi(url, params) {
    for (
        let attempt = 1;
        attempt <= GOOGLE_API_MAX_ATTEMPTS;
        attempt++
    ) {
        try {
            const response = await axios.get(url, {
                params,
                timeout: GOOGLE_API_TIMEOUT_MS
            });

            /*
             * Google Maps API 部分錯誤會以 HTTP 200 回傳，
             * 例如 OVER_QUERY_LIMIT。
             *
             * 將它轉成 HTTP 429 類型的錯誤，
             * 讓重試機制能夠進行處理。
             */
            if (response.data?.status === "OVER_QUERY_LIMIT") {
                const limitError = new Error(
                    "Google API OVER_QUERY_LIMIT"
                );

                limitError.response = {
                    status: 429
                };

                throw limitError;
            }

            return response;

        } catch (error) {
            const canRetry = shouldRetry(error);

            console.error(
                `Google API attempt ${attempt}/${GOOGLE_API_MAX_ATTEMPTS} failed:`,
                error?.code ||
                error?.response?.status ||
                error?.message
            );

            if (
                !canRetry ||
                attempt === GOOGLE_API_MAX_ATTEMPTS
            ) {
                throw error;
            }

            /*
             * 指數退避：
             * 第一次失敗等待 1000ms
             * 第二次失敗等待 2000ms
             */
            const waitTime =
                1000 * Math.pow(2, attempt - 1);

            console.log(
                `${waitTime}ms 後重新連線...`
            );

            await delay(waitTime);
        }
    }

    throw new Error(
        "Google API retry loop ended unexpectedly"
    );
}

/**
 * 移除 Google Directions API 回傳文字中的 HTML 標籤。
 *
 * @param {string} html
 * @returns {string}
 */
function removeHtmlTags(html) {
    return String(html || "")
        .replace(/<[^>]*>/g, "")
        .replace(/&nbsp;/g, " ")
        .replace(/&amp;/g, "&")
        .trim();
}

/**
 * 回傳 Google Maps 暫時性網路錯誤。
 *
 * @param {unknown} error
 * @param {import("express").Response} res
 * @param {string} timeoutMessage
 * @param {string} unavailableMessage
 * @returns {import("express").Response}
 */
function sendGoogleNetworkError(
    error,
    res,
    timeoutMessage,
    unavailableMessage
) {
    const timeout = isTimeoutError(error);

    return res.status(timeout ? 504 : 503).json({
        success: false,
        error: {
            code: timeout
                ? "GOOGLE_MAPS_TIMEOUT"
                : "GOOGLE_MAPS_UNAVAILABLE",
            message: timeout
                ? timeoutMessage
                : unavailableMessage,
            retryable: true
        }
    });
}

/**
 * 檢查 Google Geocoding API 回傳狀態。
 *
 * @param {object} data
 * @param {import("express").Response} res
 * @returns {import("express").Response | null}
 */
function handleGeocodingStatus(data, res) {
    if (data.status === "ZERO_RESULTS") {
        return res.status(404).json({
            success: false,
            error: {
                code: "ADDRESS_NOT_FOUND",
                message: "無法依照此經緯度取得地址",
                retryable: false
            }
        });
    }

    if (data.status !== "OK") {
        return res.status(400).json({
            success: false,
            error: {
                code: data.status || "GEOCODING_FAILED",
                message:
                    data.error_message ||
                    "Google Geocoding API 呼叫失敗",
                retryable: false
            }
        });
    }

    return null;
}

/**
 * 檢查 Google Directions API 回傳狀態。
 *
 * @param {object} data
 * @param {import("express").Response} res
 * @returns {import("express").Response | null}
 */
function handleDirectionsStatus(data, res) {
    if (data.status === "ZERO_RESULTS") {
        return res.status(404).json({
            success: false,
            error: {
                code: "ROUTE_NOT_FOUND",
                message: "找不到可用的步行路線",
                retryable: false
            }
        });
    }

    if (data.status !== "OK") {
        return res.status(400).json({
            success: false,
            error: {
                code:
                    data.status ||
                    "DIRECTIONS_REQUEST_FAILED",
                message:
                    data.error_message ||
                    "Google Maps 導航請求失敗",
                retryable: false
            }
        });
    }

    return null;
}

/* =========================================================
   健康檢查
========================================================= */

app.get("/api/health", (req, res) => {
    return res.status(200).json({
        success: true,
        message: "Blind Helper backend is running"
    });
});

/* =========================================================
   1. 接收 GPS、轉換地址並寫入 locations
========================================================= */

app.post("/api/locations", async (req, res) => {
    let googleRequestCompleted = false;

    try {
        const {
            user_id,
            latitude,
            longitude,
            accuracy
        } = req.body;

        if (
            user_id == null ||
            latitude == null ||
            longitude == null
        ) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_REQUEST",
                    message:
                        "user_id、latitude 與 longitude 為必填欄位",
                    retryable: false
                }
            });
        }

        const latitudeNumber = Number(latitude);
        const longitudeNumber = Number(longitude);

        const accuracyNumber =
            accuracy == null
                ? null
                : Number(accuracy);

        if (
            !Number.isFinite(latitudeNumber) ||
            latitudeNumber < -90 ||
            latitudeNumber > 90 ||
            !Number.isFinite(longitudeNumber) ||
            longitudeNumber < -180 ||
            longitudeNumber > 180
        ) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_COORDINATES",
                    message: "經緯度格式或範圍不正確",
                    retryable: false
                }
            });
        }

        if (
            accuracyNumber != null &&
            (
                !Number.isFinite(accuracyNumber) ||
                accuracyNumber < 0
            )
        ) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_ACCURACY",
                    message:
                        "accuracy 必須是大於或等於 0 的數值",
                    retryable: false
                }
            });
        }

        const geoResponse = await requestGoogleApi(
            GOOGLE_GEOCODING_URL,
            {
                latlng:
                    `${latitudeNumber},${longitudeNumber}`,
                language: "zh-TW",
                key:
                    process.env.GOOGLE_MAPS_API_KEY
            }
        );

        googleRequestCompleted = true;

        const statusError = handleGeocodingStatus(
            geoResponse.data,
            res
        );

        if (statusError) {
            return statusError;
        }

        const firstResult =
            geoResponse.data.results?.[0];

        if (!firstResult?.formatted_address) {
            return res.status(502).json({
                success: false,
                error: {
                    code: "INVALID_GOOGLE_RESPONSE",
                    message:
                        "Google Geocoding API 回傳格式異常",
                    retryable: true
                }
            });
        }

        const address =
            firstResult.formatted_address;

        const sql = `
            INSERT INTO locations
            (
                user_id,
                latitude,
                longitude,
                accuracy,
                address
            )
            VALUES (?, ?, ?, ?, ?)
        `;

        const [result] = await db.execute(sql, [
            user_id,
            latitudeNumber,
            longitudeNumber,
            accuracyNumber,
            address
        ]);

        return res.status(201).json({
            success: true,
            message:
                "Location created successfully",
            location_id: result.insertId,
            address
        });

    } catch (error) {
        if (
            error?.code ===
            "ER_NO_REFERENCED_ROW_2"
        ) {
            return res.status(404).json({
                success: false,
                error: {
                    code: "USER_NOT_FOUND",
                    message: "找不到使用者",
                    retryable: false
                }
            });
        }

        /*
         * Google API 尚未成功完成，而且錯誤屬於
         * 可重試的暫時性網路錯誤。
         */
        if (
            !googleRequestCompleted &&
            shouldRetry(error)
        ) {
            return sendGoogleNetworkError(
                error,
                res,
                "地址轉換服務回應逾時",
                "地址轉換服務暫時無法使用"
            );
        }

        console.error(
            "Create location failed:",
            error
        );

        return res.status(500).json({
            success: false,
            error: {
                code: "LOCATION_CREATE_FAILED",
                message: "定位資料寫入失敗",
                retryable: false
            }
        });
    }
});

/* =========================================================
   2. 經緯度轉換為地址
========================================================= */

app.post(
    "/api/locations/reverse-geocode",
    async (req, res) => {
        try {
            const {
                latitude,
                longitude
            } = req.body;

            if (
                latitude == null ||
                longitude == null
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code: "INVALID_REQUEST",
                        message:
                            "latitude 與 longitude 為必填欄位",
                        retryable: false
                    }
                });
            }

            const latitudeNumber =
                Number(latitude);

            const longitudeNumber =
                Number(longitude);

            if (
                !Number.isFinite(latitudeNumber) ||
                latitudeNumber < -90 ||
                latitudeNumber > 90 ||
                !Number.isFinite(longitudeNumber) ||
                longitudeNumber < -180 ||
                longitudeNumber > 180
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_COORDINATES",
                        message:
                            "經緯度格式或範圍不正確",
                        retryable: false
                    }
                });
            }

            const response =
                await requestGoogleApi(
                    GOOGLE_GEOCODING_URL,
                    {
                        latlng:
                            `${latitudeNumber},${longitudeNumber}`,
                        language: "zh-TW",
                        key:
                            process.env
                                .GOOGLE_MAPS_API_KEY
                    }
                );

            const statusError =
                handleGeocodingStatus(
                    response.data,
                    res
                );

            if (statusError) {
                return statusError;
            }

            const firstResult =
                response.data.results?.[0];

            if (!firstResult?.formatted_address) {
                return res.status(502).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_GOOGLE_RESPONSE",
                        message:
                            "Google Geocoding API 回傳格式異常",
                        retryable: true
                    }
                });
            }

            return res.status(200).json({
                success: true,
                address:
                    firstResult.formatted_address
            });

        } catch (error) {
            console.error(
                "Reverse geocoding failed:",
                error
            );

            /*
             * 只有暫時性網路錯誤才回傳
             * GOOGLE_MAPS_TIMEOUT 或
             * GOOGLE_MAPS_UNAVAILABLE。
             */
            if (shouldRetry(error)) {
                return sendGoogleNetworkError(
                    error,
                    res,
                    "地址轉換服務回應逾時",
                    "地址轉換服務暫時無法使用"
                );
            }

            /*
             * 其他非網路錯誤不應誤判為
             * Google Maps 暫時無法使用。
             */
            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "REVERSE_GEOCODING_FAILED",
                    message:
                        "地址轉換處理失敗",
                    retryable: false
                }
            });
        }
    }
);

/* =========================================================
   3. 步行導航與轉彎提示
========================================================= */

app.post(
    "/api/navigation/directions",
    async (req, res) => {
        try {
            const {
                start,
                destination
            } = req.body;

            if (
                typeof start !== "string" ||
                typeof destination !== "string" ||
                !start.trim() ||
                !destination.trim()
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_REQUEST",
                        message:
                            "start 與 destination 為必填字串",
                        retryable: false
                    }
                });
            }

            const response =
                await requestGoogleApi(
                    GOOGLE_DIRECTIONS_URL,
                    {
                        origin: start.trim(),
                        destination:
                            destination.trim(),
                        mode: "walking",
                        language: "zh-TW",
                        key:
                            process.env
                                .GOOGLE_MAPS_API_KEY
                    }
                );

            const statusError =
                handleDirectionsStatus(
                    response.data,
                    res
                );

            if (statusError) {
                return statusError;
            }

            const route =
                response.data.routes?.[0];

            const leg =
                route?.legs?.[0];

            if (!route || !leg) {
                return res.status(502).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_GOOGLE_RESPONSE",
                        message:
                            "Google Directions API 回傳格式異常",
                        retryable: true
                    }
                });
            }

            const steps =
                Array.isArray(leg.steps)
                    ? leg.steps.map(
                        (step, index) => ({
                            step_order:
                                index + 1,
                            instruction:
                                removeHtmlTags(
                                    step.html_instructions
                                ),
                            distance:
                                step.distance?.text ||
                                null,
                            duration:
                                step.duration?.text ||
                                null,
                            start_location:
                                step.start_location ||
                                null,
                            end_location:
                                step.end_location ||
                                null
                        })
                    )
                    : [];

            return res.status(200).json({
                success: true,
                start_address:
                    leg.start_address ||
                    null,
                end_address:
                    leg.end_address ||
                    null,
                distance:
                    leg.distance?.text ||
                    null,
                duration:
                    leg.duration?.text ||
                    null,
                steps
            });

        } catch (error) {
            console.error(
                "Directions request failed:",
                error
            );

            /*
             * 只有暫時性網路錯誤才回傳
             * GOOGLE_MAPS_TIMEOUT 或
             * GOOGLE_MAPS_UNAVAILABLE。
             */
            if (shouldRetry(error)) {
                return sendGoogleNetworkError(
                    error,
                    res,
                    "導航服務回應逾時",
                    "導航服務暫時無法使用，請稍後再試"
                );
            }

            /*
             * 其他程式或資料處理錯誤，
             * 不應回傳成 Google Maps 網路錯誤。
             */
            return res.status(500).json({
                success: false,
                error: {
                    code: "DIRECTIONS_FAILED",
                    message:
                        "導航服務處理失敗",
                    retryable: false
                }
            });
        }
    }
);

/* =========================================================
   4. 環境辨識紀錄寫入 object_detections
========================================================= */

app.post(
    "/api/environment-logs",
    async (req, res) => {
        try {
            const {
                user_id,
                object_name,
                confidence,
                description,
                image_url,
                latitude,
                longitude
            } = req.body;

            if (
                user_id == null ||
                typeof object_name !== "string" ||
                !object_name.trim()
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_REQUEST",
                        message:
                            "user_id 與 object_name 為必填欄位",
                        retryable: false
                    }
                });
            }

            const confidenceNumber =
                confidence == null
                    ? null
                    : Number(confidence);

            if (
                confidenceNumber != null &&
                (
                    !Number.isFinite(confidenceNumber) ||
                    confidenceNumber < 0 ||
                    confidenceNumber > 1
                )
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_CONFIDENCE",
                        message:
                            "confidence 必須介於 0 到 1",
                        retryable: false
                    }
                });
            }

            const latitudeNumber =
                latitude == null
                    ? null
                    : Number(latitude);

            const longitudeNumber =
                longitude == null
                    ? null
                    : Number(longitude);

            if (
                latitudeNumber != null &&
                (
                    !Number.isFinite(latitudeNumber) ||
                    latitudeNumber < -90 ||
                    latitudeNumber > 90
                )
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_LATITUDE",
                        message:
                            "latitude 格式或範圍不正確",
                        retryable: false
                    }
                });
            }

            if (
                longitudeNumber != null &&
                (
                    !Number.isFinite(longitudeNumber) ||
                    longitudeNumber < -180 ||
                    longitudeNumber > 180
                )
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_LONGITUDE",
                        message:
                            "longitude 格式或範圍不正確",
                        retryable: false
                    }
                });
            }

            const sql = `
                INSERT INTO object_detections
                (
                    user_id,
                    object_name,
                    confidence,
                    description,
                    image_url,
                    latitude,
                    longitude
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
            `;

            const [result] =
                await db.execute(sql, [
                    user_id,
                    object_name.trim(),
                    confidenceNumber,
                    description ?? null,
                    image_url ?? null,
                    latitudeNumber,
                    longitudeNumber
                ]);

            return res.status(201).json({
                success: true,
                message:
                    "Environment detection log created successfully",
                detection_id:
                    result.insertId
            });

        } catch (error) {
            if (
                error?.code ===
                "ER_NO_REFERENCED_ROW_2"
            ) {
                return res.status(404).json({
                    success: false,
                    error: {
                        code:
                            "USER_NOT_FOUND",
                        message:
                            "找不到使用者",
                        retryable: false
                    }
                });
            }

            console.error(
                "Create environment log failed:",
                error
            );

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "DATABASE_ERROR",
                    message:
                        "環境辨識紀錄寫入失敗",
                    retryable: false
                }
            });
        }
    }
);

/* =========================================================
   找不到 API
========================================================= */

app.use((req, res) => {
    return res.status(404).json({
        success: false,
        error: {
            code: "API_NOT_FOUND",
            message: "找不到此 API 路徑",
            retryable: false
        }
    });
});

/* =========================================================
   啟動伺服器
========================================================= */

const port =
    parseInt(process.env.PORT, 10) ||
    3000;

if (!process.env.GOOGLE_MAPS_API_KEY) {
    console.error(
        "Missing GOOGLE_MAPS_API_KEY in backend/.env"
    );

    process.exit(1);
}

app.listen(port, () => {
    console.log(
        `Server running on port ${port}`
    );
});