const express = require("express");
const cors = require("cors");
const axios = require("axios");
require("dotenv").config();

const db = require("./db");
const familyRouter = require("./family");
const familyPairingRouter = require("./family_pairing");
const { distanceMeters } = require("./geo");

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

/*
 * Gemini 語音助理用的模型，寫成環境變數可調整——避免 Google 之後下架或
 * 更換模型版本時，要改程式碼才能換，直接改 .env 的 GEMINI_MODEL 即可。
 * gemini-flash-latest 是 Google 官方提供的別名，會自動對應到目前最新的
 * 穩定版 Flash 模型。
 */
const GEMINI_API_BASE_URL =
    "https://generativelanguage.googleapis.com/v1beta/models";
const GEMINI_MODEL =
    process.env.GEMINI_MODEL || "gemini-flash-latest";
const GEMINI_API_TIMEOUT_MS = 8000;

/*
 * Places API（新版，POST + JSON，跟 Geocoding/Directions 那套舊版
 * GET 查詢參數的 API 不是同一代，所以沒有共用 requestGoogleApi()）。
 * 用來查「使用者問題關鍵字」附近真實存在的地點，餵給 Gemini 當作
 * 真實資料，避免它自己憑空編造店家。跟 Geocoding/Directions 共用
 * 同一個 GOOGLE_MAPS_API_KEY，只是 Places 這個 API 要另外在
 * Google Cloud 主控台確認有啟用。
 */
const PLACES_SEARCH_TEXT_URL =
    "https://places.googleapis.com/v1/places:searchText";
// 這個半徑只是「優先偏向」這個範圍（locationBias），Google 官方文件明講
// 「結果可能超出這個範圍」，不是硬性範圍——實測發現使用者問一些查詢字串
// 剛好跟很遠地方的店家名稱很像時，真的會查到幾十、幾百公里外的真實地點。
// 所以另外用 PLACES_MAX_DISTANCE_M 當「硬性」門檻，查到的結果如果真實
// 距離超過這個數字就直接丟棄，不會進到餵給 Gemini 的清單裡——對走路的
// 視障使用者來說，幾公里外的地點本來就不會是「附近」。
const PLACES_SEARCH_RADIUS_M = 1000;
const PLACES_MAX_DISTANCE_M = 2000;
const PLACES_MAX_RESULTS = 5;

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
 * 用使用者的問題文字當查詢關鍵字，查詢使用者目前座標附近真實存在的地點
 * （Places API 文字搜尋），並換算成以使用者座標為準的真實距離（公尺）。
 *
 * 查不到、或 API 本身出錯都回傳空陣列，不拋出例外——這個資料只是
 * 「有的話可以讓 Gemini 回答更準」，不應該讓使用者整個問不到答案。
 *
 * @param {number} latitude
 * @param {number} longitude
 * @param {string} query
 * @returns {Promise<Array<{name: string, address: string, distanceMeters: number}>>}
 */
async function searchNearbyPlaces(latitude, longitude, query) {
    try {
        const response = await axios.post(
            PLACES_SEARCH_TEXT_URL,
            {
                textQuery: query,
                languageCode: "zh-TW",
                locationBias: {
                    circle: {
                        center: { latitude, longitude },
                        radius: PLACES_SEARCH_RADIUS_M
                    }
                }
            },
            {
                headers: {
                    "X-Goog-Api-Key": process.env.GOOGLE_MAPS_API_KEY,
                    "X-Goog-FieldMask":
                        "places.displayName,places.formattedAddress,places.location"
                },
                timeout: GEMINI_API_TIMEOUT_MS
            }
        );

        const places = response.data?.places || [];

        return places
            .filter((place) => place.location)
            .map((place) => ({
                name: place.displayName?.text || "未命名地點",
                address: place.formattedAddress || "",
                distanceMeters: Math.round(
                    distanceMeters(
                        { latitude, longitude },
                        place.location
                    )
                )
            }))
            .filter((place) => place.distanceMeters <= PLACES_MAX_DISTANCE_M)
            .sort((a, b) => a.distanceMeters - b.distanceMeters)
            .slice(0, PLACES_MAX_RESULTS);

    } catch (error) {
        console.error(
            "Places API 查詢失敗，將以沒有地點資料的方式繼續:",
            error?.response?.data || error.message
        );
        return [];
    }
}

/*
 * 讓 Gemini 可以「呼叫」的工具：使用者想去某個地方時，不是只用文字講，
 * 而是明確回傳一個「請開始導航」的結構化指令（Function Calling），
 * App 端收到後會直接接去既有、已經驗證過的導航功能（見 /api/navigation/
 * directions 跟 Android 端的 TurnByTurnGuide），不是另外做一套導航邏輯。
 */
const ASSISTANT_TOOLS = [
    {
        functionDeclarations: [
            {
                name: "start_navigation",
                description:
                    "當使用者的意圖是想要「前往」某個地點（不只是單純查詢資訊）" +
                    "時呼叫這個工具，開始導航到該地點。目的地必須是下面提供的" +
                    "真實地點清單裡的其中一個，不可以自己編造。",
                parameters: {
                    type: "OBJECT",
                    properties: {
                        destination_name: {
                            type: "STRING",
                            description: "目的地名稱，必須完全照抄清單裡的名稱"
                        },
                        destination_address: {
                            type: "STRING",
                            description: "目的地地址，必須完全照抄清單裡的地址"
                        }
                    },
                    required: ["destination_name", "destination_address"]
                }
            }
        ]
    }
];

/*
 * 適用整段對話、不會每一輪都重複的系統規則。原本這些規則跟每一輪的
 * 真實資料混在同一段文字裡，現在既然支援多輪對話（見 history 參數），
 * 規則只需要講一次，放進 systemInstruction，contents 裡每一輪才單純是
 * 對話內容本身，比較不會讓模型搞混「這是規則」還是「這是對話歷史」。
 */
const ASSISTANT_SYSTEM_INSTRUCTION =
    "你是視障導航 App 裡的語音助理。使用者正在走路、無法看螢幕，只能用" +
    "耳朵聽你的回答，請用繁體中文、口語、簡短（最多兩到三句話）回答，" +
    "不要使用條列、標題或任何螢幕排版符號。\n\n" +
    "最重要的規則：每一輪對話裡提供的地址跟地點清單，是系統先查證過的" +
    "真實資料，你只能根據這份真實資料回答或決定導航目的地，絕對不能自己" +
    "憑空想像或編造清單以外的店家名稱、地址或距離。如果清單裡沒有能回答" +
    "這個問題的地點，就老實告訴使用者「附近沒有查到相關地點」，不要為了" +
    "聽起來有幫助而亂猜。\n\n" +
    "另一個重要規則：如果使用者的意圖是「想要去」某個地方（不是單純" +
    "查詢資訊），請呼叫 start_navigation 這個工具，從清單裡選一個最" +
    "符合的地點（例如使用者說「最近的」就選距離最短的那個），不要只是" +
    "用文字回答、也不要為了確認而反問使用者要選哪一個。除此之外的情況" +
    "（單純問資訊、問距離、問有沒有、延續前面話題的追問等）才用文字" +
    "回答。這個 App 支援連續對話，你可以利用之前的對話內容理解使用者" +
    "這次問題裡「那個」「那家店」之類指代的是什麼；但你仍然沒有辦法" +
    "執行文字以外、工具以外的任何操作（例如撥打電話、設定提醒），遇到" +
    "這類需求就老實說做不到即可。";

/**
 * 呼叫 Gemini API，把使用者的語音問題、先查好的真實位置資料、以及這次
 * App 使用期間累積的對話歷史送出去。
 *
 * 回傳值有兩種：
 * - { type: "speak", text } ——純粹口頭回答一段資訊
 * - { type: "navigate", destinationName, destinationAddress } ——使用者
 *   想去某個地方，呼叫端要接去既有的導航流程，不是播放語音
 *
 * 更重要的是：提示詞明確要求 Gemini「只能根據提供的真實資料回答」、
 * 查不到就老實說查不到——否則 Gemini 會依據自己的一般知識瞎猜一個聽起來
 * 合理但實際上不存在的店家或距離，對看不到螢幕、只能照著語音指示行動的
 * 視障使用者來說是真的會被誤導去錯的地方，不是單純答錯而已。
 *
 * @param {string} question
 * @param {string|null} currentAddress 使用者目前座標反查到的真實地址，查不到則為 null
 * @param {Array<{name: string, address: string, distanceMeters: number}>} nearbyPlaces
 * @param {Array<{role: string, text: string}>} history 這次 App 使用期間之前問過的幾輪對話，最舊到最新排序
 * @returns {Promise<{type: "speak", text: string} | {type: "navigate", destinationName: string, destinationAddress: string}>}
 */
async function askGemini(question, currentAddress, nearbyPlaces, history) {
    const url =
        `${GEMINI_API_BASE_URL}/${GEMINI_MODEL}:generateContent`;

    const placesText =
        nearbyPlaces.length > 0
            ? nearbyPlaces
                .map(
                    (place) =>
                        `- ${place.name}（${place.address}，距離使用者約 ${place.distanceMeters} 公尺）`
                )
                .join("\n")
            : "（這次查詢沒有找到相關的真實地點資料）";

    const locationContext = currentAddress
        ? `使用者目前所在的真實地址：${currentAddress}`
        : "（目前沒有取得使用者座標或地址資料）";

    const currentTurnText =
        `${locationContext}\n\n` +
        `附近真實查到的地點（依距離排序）：\n${placesText}\n\n` +
        `使用者的問題：${question}`;

    const historyContents = (history || []).map((turn) => ({
        role: turn.role === "model" ? "model" : "user",
        parts: [{ text: turn.text }]
    }));

    const response = await axios.post(
        url,
        {
            systemInstruction: {
                parts: [{ text: ASSISTANT_SYSTEM_INSTRUCTION }]
            },
            contents: [
                ...historyContents,
                {
                    role: "user",
                    parts: [{ text: currentTurnText }]
                }
            ],
            tools: ASSISTANT_TOOLS
        },
        {
            params: {
                key: process.env.GEMINI_API_KEY
            },
            timeout: GEMINI_API_TIMEOUT_MS
        }
    );

    const parts =
        response.data?.candidates?.[0]?.content?.parts || [];

    const functionCallPart = parts.find((part) => part.functionCall);

    if (
        functionCallPart &&
        functionCallPart.functionCall.name === "start_navigation"
    ) {
        const args = functionCallPart.functionCall.args || {};

        if (!args.destination_name || !args.destination_address) {
            throw new Error(
                "Gemini 呼叫 start_navigation 但缺少目的地參數"
            );
        }

        return {
            type: "navigate",
            destinationName: args.destination_name,
            destinationAddress: args.destination_address
        };
    }

    const answer = parts.find((part) => part.text)?.text;

    if (!answer) {
        throw new Error(
            "Gemini API 回傳格式異常，找不到回答文字"
        );
    }

    return { type: "speak", text: answer.trim() };
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
 * 將值轉成大於 0 的整數。
 * 失敗時回傳 null。
 */
function parsePositiveInteger(value) {
    const number = Number(value);

    if (
        !Number.isInteger(number) ||
        number <= 0
    ) {
        return null;
    }

    return number;
}

/**
 * 檢查 YYYY-MM-DD 日期格式。
 */
function isValidDateString(value) {
    if (
        typeof value !== "string" ||
        !/^\d{4}-\d{2}-\d{2}$/.test(value)
    ) {
        return false;
    }

    const date = new Date(
        `${value}T00:00:00Z`
    );

    return (
        !Number.isNaN(date.getTime()) &&
        date.toISOString().slice(0, 10) === value
    );
}

/**
 * 處理外鍵錯誤。
 */
function handleForeignKeyError(
    error,
    res
) {
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

    return null;
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
   3. 步行導航、轉彎提示與導航紀錄
========================================================= */

app.post(
    "/api/navigation/directions",
    async (req, res) => {
        try {
            const {
                user_id,
                start,
                destination
            } = req.body;

            /*
             * 暫時允許舊版 App 不傳 user_id。
             *
             * 有 user_id：
             * 導航成功會寫入 navigation_records。
             *
             * 沒有 user_id：
             * 仍可取得路線，但不會產生導航紀錄。
             */
            const userId =
                user_id == null
                    ? null
                    : parsePositiveInteger(
                        user_id
                    );

            if (
                user_id != null &&
                userId == null
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_USER_ID",
                        message:
                            "user_id 必須是大於 0 的整數",
                        retryable: false
                    }
                });
            }

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
                        origin:
                            start.trim(),

                        destination:
                            destination.trim(),

                        mode:
                            "walking",

                        language:
                            "zh-TW",

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
                                null,

                            /*
                             * 特殊轉彎圖示（如 turn-left、turn-right、
                             * roundabout-left、uturn-right）。
                             * 直行步驟通常不會有此欄位，因此預設 null。
                             * 提供給 Android 端三階段轉彎提示模組作為輔助判斷。
                             */
                            maneuver:
                                step.maneuver ||
                                null
                        })
                    )
                    : [];

            let navigationId = null;

            /*
             * 有 user_id 時，
             * 將規劃結果寫進 navigation_records。
             */
            if (userId != null) {

                const routeSummary =
                    JSON.stringify({
                        summary:
                            route.summary || null,

                        steps:
                            steps.map(
                                (step) => ({
                                    step_order:
                                        step.step_order,

                                    instruction:
                                        step.instruction,

                                    distance:
                                        step.distance,

                                    duration:
                                        step.duration,

                                    /*
                                     * 保留每個 step 的座標，供家屬模式「查看詳情」
                                     * 畫出這趟導航的規劃路線地圖使用
                                     * （見 family_pairing.js 的 navigation-history 詳情 API）。
                                     */
                                    start_location:
                                        step.start_location,

                                    end_location:
                                        step.end_location
                                })
                            )
                    });

                const insertSql = `
                    INSERT INTO navigation_records
                    (
                        user_id,
                        start_address,
                        end_address,
                        start_latitude,
                        start_longitude,
                        end_latitude,
                        end_longitude,
                        distance_meters,
                        duration_seconds,
                        route_summary,
                        status
                    )
                    VALUES
                    (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'planned'
                    )
                `;

                const [navigationResult] =
                    await db.execute(
                        insertSql,
                        [
                            userId,

                            leg.start_address ??
                            null,

                            leg.end_address ??
                            null,

                            leg.start_location
                                ?.lat ??
                            null,

                            leg.start_location
                                ?.lng ??
                            null,

                            leg.end_location
                                ?.lat ??
                            null,

                            leg.end_location
                                ?.lng ??
                            null,

                            leg.distance
                                ?.value ??
                            0,

                            leg.duration
                                ?.value ??
                            0,

                            routeSummary
                        ]
                    );

                navigationId =
                    navigationResult.insertId;
            }

            return res.status(200).json({
                success: true,

                navigation_id:
                    navigationId,

                start_address:
                    leg.start_address ||
                    null,

                end_address:
                    leg.end_address ||
                    null,

                distance:
                    leg.distance?.text ||
                    null,

                distance_meters:
                    leg.distance?.value ||
                    0,

                duration:
                    leg.duration?.text ||
                    null,

                duration_seconds:
                    leg.duration?.value ||
                    0,

                steps
            });

        } catch (error) {

            const foreignKeyResponse =
                handleForeignKeyError(
                    error,
                    res
                );

            if (foreignKeyResponse) {
                return foreignKeyResponse;
            }

            console.error(
                "Directions request failed:",
                error
            );

            if (shouldRetry(error)) {
                return sendGoogleNetworkError(
                    error,
                    res,
                    "導航服務回應逾時",
                    "導航服務暫時無法使用，請稍後再試"
                );
            }

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "DIRECTIONS_FAILED",

                    message:
                        "導航服務處理失敗",

                    retryable:
                        false
                }
            });
        }
    }
);

/* =========================================================
   4. 環境辨識紀錄寫入 object_detections
========================================================= */


app.post("/api/environment-logs", async (req, res) => {
    try {
        const {
            user_id,
            object_name,
            confidence,
            description,
            image_url,
            latitude,
            longitude,
            navigation_id
        } = req.body;

        // 基本必填欄位檢查
        if (
            user_id == null ||
            typeof object_name !== "string" ||
            !object_name.trim()
        ) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_REQUEST",
                    message: "user_id 與 object_name 為必填欄位",
                    retryable: false
                }
            });
        }

        const userId =
            parsePositiveInteger(user_id);

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

        // 信心度數字轉型與檢查 (範圍 0.0 ~ 1.0)
        const confidenceNum = confidence == null ? null : Number(confidence);
        if (
            confidenceNum != null &&
            (!Number.isFinite(confidenceNum) || confidenceNum < 0 || confidenceNum > 1)
        ) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_CONFIDENCE",
                    message: "confidence 必須介於 0.0 與 1.0 之間",
                    retryable: false
                }
            });
        }

        // 經緯度可選檢查
        const latNum = latitude == null ? null : Number(latitude);
        const lngNum = longitude == null ? null : Number(longitude);

        if (
            (latNum != null && (!Number.isFinite(latNum) || latNum < -90 || latNum > 90)) ||
            (lngNum != null && (!Number.isFinite(lngNum) || lngNum < -180 || lngNum > 180))
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

        /*
         * navigation_id 為選填，若這次警報是在導航進行中發生，
         * App 會一併帶上目前的 navigation_id，讓家屬模式的導航紀錄
         * 能算出「N 次警報」並在詳情地圖上標出警報位置。
         */
        const navigationId =
            navigation_id == null
                ? null
                : parsePositiveInteger(navigation_id);

        if (navigation_id != null && navigationId == null) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_NAVIGATION_ID",
                    message: "navigation_id 必須是大於 0 的整數",
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
                longitude,
                navigation_id
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        `;

        const [result] = await db.execute(sql, [
            userId,
            object_name.trim(),
            confidenceNum,

            typeof description === "string"
                ? description.trim() || null
                : null,

            typeof image_url === "string"
                ? image_url.trim() || null
                : null,

            latNum,
            lngNum,
            navigationId
        ]);

        return res.status(201).json({
            success: true,
            message: "Environment log saved successfully",
            detection_id: result.insertId
        });

    } catch (error) {
        const foreignKeyResponse = handleForeignKeyError(error, res);
        if (foreignKeyResponse) return foreignKeyResponse;

        console.error("Save environment log failed:", error);

        return res.status(500).json({
            success: false,
            error: {
                code: "ENVIRONMENT_LOG_FAILED",
                message: "環境辨識紀錄寫入失敗",
                retryable: false
            }
        });
    }
});
/* =========================================================
   5. App 使用時間
========================================================= */

/*
 * App 開始使用
 */
app.post(
    "/api/app-sessions/start",
    async (req, res) => {
        try {
            const {
                user_id,
                app_version,
                device_platform
            } = req.body;

            const userId =
                parsePositiveInteger(
                    user_id
                );

            if (userId == null) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_USER_ID",

                        message:
                            "user_id 必須是大於 0 的整數",

                        retryable:
                            false
                    }
                });
            }

            const sql = `
                INSERT INTO app_sessions
                (
                    user_id,
                    app_version,
                    device_platform,
                    status
                )
                VALUES (?, ?, ?, 'active')
            `;

            const [result] =
                await db.execute(
                    sql,
                    [
                        userId,
                        app_version ??
                        null,

                        device_platform ??
                        "android"
                    ]
                );

            return res.status(201).json({
                success: true,

                session_id:
                    result.insertId,

                status:
                    "active"
            });

        } catch (error) {

            const foreignKeyResponse =
                handleForeignKeyError(
                    error,
                    res
                );

            if (foreignKeyResponse) {
                return foreignKeyResponse;
            }

            console.error(
                "Start app session failed:",
                error
            );

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "SESSION_START_FAILED",

                    message:
                        "建立 App 使用紀錄失敗",

                    retryable:
                        false
                }
            });
        }
    }
);


/*
 * App 使用中 heartbeat
 *
 * 建議手機每 60 秒呼叫一次。
 */
app.patch(
    "/api/app-sessions/:session_id/heartbeat",
    async (req, res) => {
        try {
            const sessionId =
                parsePositiveInteger(
                    req.params.session_id
                );

            const userId =
                parsePositiveInteger(
                    req.body.user_id
                );

            if (
                sessionId == null ||
                userId == null
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_REQUEST",

                        message:
                            "session_id 與 user_id 格式不正確",

                        retryable:
                            false
                    }
                });
            }

            const sql = `
                UPDATE app_sessions

                SET
                    last_active_at =
                        CURRENT_TIMESTAMP,

                    duration_seconds =
                        GREATEST(
                            TIMESTAMPDIFF(
                                SECOND,
                                started_at,
                                CURRENT_TIMESTAMP
                            ),
                            0
                        )

                WHERE session_id = ?
                AND user_id = ?
                AND status = 'active'
            `;

            const [result] =
                await db.execute(
                    sql,
                    [
                        sessionId,
                        userId
                    ]
                );

            if (
                result.affectedRows === 0
            ) {
                return res.status(404).json({
                    success: false,
                    error: {
                        code:
                            "SESSION_NOT_FOUND",

                        message:
                            "找不到使用中的 App session",

                        retryable:
                            false
                    }
                });
            }

            return res.status(200).json({
                success: true,
                session_id:
                    sessionId
            });

        } catch (error) {

            console.error(
                "App session heartbeat failed:",
                error
            );

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "SESSION_HEARTBEAT_FAILED",

                    message:
                        "更新 App 使用時間失敗",

                    retryable:
                        false
                }
            });
        }
    }
);


/*
 * App 結束使用
 */
app.patch(
    "/api/app-sessions/:session_id/end",
    async (req, res) => {
        try {
            const sessionId =
                parsePositiveInteger(
                    req.params.session_id
                );

            const userId =
                parsePositiveInteger(
                    req.body.user_id
                );

            if (
                sessionId == null ||
                userId == null
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_REQUEST",

                        message:
                            "session_id 與 user_id 格式不正確",

                        retryable:
                            false
                    }
                });
            }

            const sql = `
                UPDATE app_sessions

                SET
                    ended_at =
                        CURRENT_TIMESTAMP,

                    last_active_at =
                        CURRENT_TIMESTAMP,

                    duration_seconds =
                        GREATEST(
                            TIMESTAMPDIFF(
                                SECOND,
                                started_at,
                                CURRENT_TIMESTAMP
                            ),
                            0
                        ),

                    status =
                        'ended'

                WHERE session_id = ?
                AND user_id = ?
                AND status = 'active'
            `;

            const [result] =
                await db.execute(
                    sql,
                    [
                        sessionId,
                        userId
                    ]
                );

            if (
                result.affectedRows === 0
            ) {
                return res.status(404).json({
                    success: false,
                    error: {
                        code:
                            "SESSION_NOT_FOUND",

                        message:
                            "找不到使用中的 App session",

                        retryable:
                            false
                    }
                });
            }

            const [rows] =
                await db.execute(
                    `
                        SELECT
                            session_id,
                            started_at,
                            ended_at,
                            duration_seconds,
                            status

                        FROM app_sessions

                        WHERE session_id = ?
                        AND user_id = ?
                    `,
                    [
                        sessionId,
                        userId
                    ]
                );

            return res.status(200).json({
                success: true,
                session:
                    rows[0] || null
            });

        } catch (error) {

            console.error(
                "End app session failed:",
                error
            );

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "SESSION_END_FAILED",

                    message:
                        "結束 App 使用紀錄失敗",

                    retryable:
                        false
                }
            });
        }
    }
);


/* =========================================================
   6. 功能使用事件
========================================================= */

app.post(
    "/api/usage-events",
    async (req, res) => {
        try {
            const {
                user_id,
                session_id,
                event_type,
                screen_name,
                event_data
            } = req.body;

            const userId =
                parsePositiveInteger(
                    user_id
                );

            const sessionId =
                session_id == null
                    ? null
                    : parsePositiveInteger(
                        session_id
                    );

            if (
                userId == null ||
                (
                    session_id != null &&
                    sessionId == null
                ) ||
                typeof event_type !==
                "string" ||
                !event_type.trim()
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_REQUEST",

                        message:
                            "user_id 與 event_type 為必填欄位",

                        retryable:
                            false
                    }
                });
            }

            const eventDataJson =
                event_data == null
                    ? null
                    : JSON.stringify(
                        event_data
                    );

            const sql = `
                INSERT INTO usage_events
                (
                    user_id,
                    session_id,
                    event_type,
                    screen_name,
                    event_data
                )
                VALUES (?, ?, ?, ?, ?)
            `;

            const [result] =
                await db.execute(
                    sql,
                    [
                        userId,
                        sessionId,

                        event_type.trim(),

                        typeof screen_name ===
                            "string"
                            ? screen_name.trim()
                            : null,

                        eventDataJson
                    ]
                );

            return res.status(201).json({
                success: true,

                event_id:
                    result.insertId
            });

        } catch (error) {

            const foreignKeyResponse =
                handleForeignKeyError(
                    error,
                    res
                );

            if (foreignKeyResponse) {
                return foreignKeyResponse;
            }

            console.error(
                "Create usage event failed:",
                error
            );

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "USAGE_EVENT_CREATE_FAILED",

                    message:
                        "功能使用紀錄寫入失敗",

                    retryable:
                        false
                }
            });
        }
    }
);


/* =========================================================
   7. 導航開始與結束
========================================================= */

/*
 * 開始導航
 */
app.patch(
    "/api/navigation/:navigation_id/start",
    async (req, res) => {
        try {
            const navigationId =
                parsePositiveInteger(
                    req.params.navigation_id
                );

            const userId =
                parsePositiveInteger(
                    req.body.user_id
                );

            if (
                navigationId == null ||
                userId == null
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_REQUEST",

                        message:
                            "navigation_id 與 user_id 格式不正確",

                        retryable:
                            false
                    }
                });
            }

            const sql = `
                UPDATE navigation_records

                SET
                    status = 'active',

                    started_at =
                        COALESCE(
                            started_at,
                            CURRENT_TIMESTAMP
                        )

                WHERE navigation_id = ?
                AND user_id = ?
                AND status IN (
                    'planned',
                    'active'
                )
            `;

            const [result] =
                await db.execute(
                    sql,
                    [
                        navigationId,
                        userId
                    ]
                );

            if (
                result.affectedRows === 0
            ) {
                return res.status(404).json({
                    success: false,
                    error: {
                        code:
                            "NAVIGATION_NOT_FOUND",

                        message:
                            "找不到此導航紀錄",

                        retryable:
                            false
                    }
                });
            }

            return res.status(200).json({
                success: true,

                navigation_id:
                    navigationId,

                status:
                    "active"
            });

        } catch (error) {

            console.error(
                "Start navigation failed:",
                error
            );

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "NAVIGATION_START_FAILED",

                    message:
                        "開始導航紀錄失敗",

                    retryable:
                        false
                }
            });
        }
    }
);


/*
 * 結束導航
 *
 * status 可以是：
 * completed
 * cancelled
 */
app.patch(
    "/api/navigation/:navigation_id/finish",
    async (req, res) => {
        try {
            const navigationId =
                parsePositiveInteger(
                    req.params.navigation_id
                );

            const userId =
                parsePositiveInteger(
                    req.body.user_id
                );

            const status =
                req.body.status ||
                "completed";

            const actualDistance =
                req.body
                    .actual_distance_meters ==
                    null
                    ? 0
                    : Number(
                        req.body
                            .actual_distance_meters
                    );

            if (
                navigationId == null ||
                userId == null
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_REQUEST",

                        message:
                            "navigation_id 與 user_id 格式不正確",

                        retryable:
                            false
                    }
                });
            }

            if (
                ![
                    "completed",
                    "cancelled"
                ].includes(status)
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_STATUS",

                        message:
                            "status 只能是 completed 或 cancelled",

                        retryable:
                            false
                    }
                });
            }

            if (
                !Number.isFinite(
                    actualDistance
                ) ||
                actualDistance < 0
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_DISTANCE",

                        message:
                            "actual_distance_meters 必須大於或等於 0",

                        retryable:
                            false
                    }
                });
            }

            const sql = `
                UPDATE navigation_records

                SET
                    status = ?,

                    ended_at =
                        CURRENT_TIMESTAMP,

                    actual_distance_meters =
                        ?,

                    actual_duration_seconds =
                        COALESCE(
                            TIMESTAMPDIFF(
                                SECOND,
                                COALESCE(
                                    started_at,
                                    created_at
                                ),
                                CURRENT_TIMESTAMP
                            ),
                            0
                        ),

                    started_at =
                        COALESCE(
                            started_at,
                            created_at
                        )

                WHERE navigation_id = ?
                AND user_id = ?
                AND status IN (
                    'planned',
                    'active'
                )
            `;

            const [result] =
                await db.execute(
                    sql,
                    [
                        status,

                        Math.round(
                            actualDistance
                        ),

                        navigationId,
                        userId
                    ]
                );

            if (
                result.affectedRows === 0
            ) {
                return res.status(404).json({
                    success: false,
                    error: {
                        code:
                            "NAVIGATION_NOT_FOUND",

                        message:
                            "找不到可以結束的導航紀錄",

                        retryable:
                            false
                    }
                });
            }

            const [rows] =
                await db.execute(
                    `
                        SELECT
                            navigation_id,
                            status,
                            started_at,
                            ended_at,
                            distance_meters,
                            duration_seconds,
                            actual_distance_meters,
                            actual_duration_seconds

                        FROM navigation_records

                        WHERE navigation_id = ?
                        AND user_id = ?
                    `,
                    [
                        navigationId,
                        userId
                    ]
                );

            return res.status(200).json({
                success: true,

                navigation:
                    rows[0] || null
            });

        } catch (error) {

            console.error(
                "Finish navigation failed:",
                error
            );

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "NAVIGATION_FINISH_FAILED",

                    message:
                        "結束導航紀錄失敗",

                    retryable:
                        false
                }
            });
        }
    }
);


/* =========================================================
   8. 使用者回饋
========================================================= */

/*
 * 新增回饋
 */
app.post(
    "/api/feedback",
    async (req, res) => {
        try {
            const {
                user_id,
                category,
                rating,
                title,
                message
            } = req.body;

            const userId =
                parsePositiveInteger(
                    user_id
                );

            const allowedCategories = [
                "navigation",
                "voice",
                "camera",
                "location",
                "suggestion",
                "bug",
                "other"
            ];

            const feedbackCategory =
                category ||
                "other";

            if (
                userId == null ||
                typeof message !==
                "string" ||
                !message.trim()
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_REQUEST",

                        message:
                            "user_id 與 message 為必填欄位",

                        retryable:
                            false
                    }
                });
            }

            if (
                !allowedCategories.includes(
                    feedbackCategory
                )
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_CATEGORY",

                        message:
                            "回饋 category 格式不正確",

                        retryable:
                            false
                    }
                });
            }

            let ratingNumber = null;

            if (
                rating != null
            ) {
                ratingNumber =
                    Number(rating);

                if (
                    !Number.isInteger(
                        ratingNumber
                    ) ||
                    ratingNumber < 1 ||
                    ratingNumber > 5
                ) {
                    return res.status(400).json({
                        success: false,
                        error: {
                            code:
                                "INVALID_RATING",

                            message:
                                "rating 必須介於 1 到 5",

                            retryable:
                                false
                        }
                    });
                }
            }

            const sql = `
                INSERT INTO feedback
                (
                    user_id,
                    category,
                    rating,
                    title,
                    message
                )
                VALUES (?, ?, ?, ?, ?)
            `;

            const [result] =
                await db.execute(
                    sql,
                    [
                        userId,
                        feedbackCategory,
                        ratingNumber,

                        typeof title ===
                            "string"
                            ? title.trim()
                            : null,

                        message.trim()
                    ]
                );

            return res.status(201).json({
                success: true,

                feedback_id:
                    result.insertId,

                message:
                    "Feedback created successfully"
            });

        } catch (error) {

            const foreignKeyResponse =
                handleForeignKeyError(
                    error,
                    res
                );

            if (foreignKeyResponse) {
                return foreignKeyResponse;
            }

            console.error(
                "Create feedback failed:",
                error
            );

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "FEEDBACK_CREATE_FAILED",

                    message:
                        "新增回饋失敗",

                    retryable:
                        false
                }
            });
        }
    }
);


/*
 * 查詢某位使用者的回饋
 */
app.get(
    "/api/feedback/user/:user_id",
    async (req, res) => {
        try {
            const userId =
                parsePositiveInteger(
                    req.params.user_id
                );

            if (userId == null) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_USER_ID",

                        message:
                            "user_id 格式不正確",

                        retryable:
                            false
                    }
                });
            }

            const [rows] =
                await db.execute(
                    `
                        SELECT
                            feedback_id,
                            category,
                            rating,
                            title,
                            message,
                            status,
                            admin_reply,
                            created_at,
                            updated_at

                        FROM feedback

                        WHERE user_id = ?

                        ORDER BY
                            created_at DESC
                    `,
                    [
                        userId
                    ]
                );

            return res.status(200).json({
                success: true,
                count:
                    rows.length,
                data:
                    rows
            });

        } catch (error) {

            console.error(
                "Get feedback failed:",
                error
            );

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "FEEDBACK_QUERY_FAILED",

                    message:
                        "查詢回饋失敗",

                    retryable:
                        false
                }
            });
        }
    }
);


/*
 * 管理端更新回饋狀態與回覆
 */
app.patch(
    "/api/feedback/:feedback_id",
    async (req, res) => {
        try {
            const feedbackId =
                parsePositiveInteger(
                    req.params.feedback_id
                );

            const {
                status,
                admin_reply
            } = req.body;

            const allowedStatuses = [
                "pending",
                "reviewing",
                "resolved",
                "rejected"
            ];

            if (
                feedbackId == null ||
                !allowedStatuses.includes(
                    status
                )
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_REQUEST",

                        message:
                            "feedback_id 或 status 格式不正確",

                        retryable:
                            false
                    }
                });
            }

            const [result] =
                await db.execute(
                    `
                        UPDATE feedback

                        SET
                            status = ?,
                            admin_reply = ?

                        WHERE feedback_id = ?
                    `,
                    [
                        status,

                        typeof admin_reply ===
                            "string"
                            ? admin_reply.trim()
                            : null,

                        feedbackId
                    ]
                );

            if (
                result.affectedRows === 0
            ) {
                return res.status(404).json({
                    success: false,
                    error: {
                        code:
                            "FEEDBACK_NOT_FOUND",

                        message:
                            "找不到此回饋",

                        retryable:
                            false
                    }
                });
            }

            return res.status(200).json({
                success: true,
                feedback_id:
                    feedbackId,

                status
            });

        } catch (error) {

            console.error(
                "Update feedback failed:",
                error
            );

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "FEEDBACK_UPDATE_FAILED",

                    message:
                        "更新回饋失敗",

                    retryable:
                        false
                }
            });
        }
    }
);


/* =========================================================
   9. 每日紀錄
========================================================= */

app.get(
    "/api/records/daily",
    async (req, res) => {
        try {
            const userId =
                parsePositiveInteger(
                    req.query.user_id
                );

            const date =
                String(
                    req.query.date ||
                    ""
                );

            if (
                userId == null ||
                !isValidDateString(date)
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_REQUEST",

                        message:
                            "user_id 與 date 為必填欄位，date 格式為 YYYY-MM-DD",

                        retryable:
                            false
                    }
                });
            }

            /*
             * App 使用時間
             */
            const [sessionRows] =
                await db.execute(
                    `
                        SELECT
                            COALESCE(
                                SUM(
                                    duration_seconds
                                ),
                                0
                            )
                            AS app_usage_seconds,

                            COUNT(*)
                            AS session_count

                        FROM app_sessions

                        WHERE user_id = ?

                        AND DATE(
                            CONVERT_TZ(
                                started_at,
                                '+00:00',
                                '+08:00'
                            )
                        ) = ?
                    `,
                    [
                        userId,
                        date
                    ]
                );

            /*
             * 導航統計
             */
            const [navigationSummaryRows] =
                await db.execute(
                    `
                        SELECT
                            COUNT(*)
                                AS navigation_count,

                            COALESCE(
                                SUM(
                                    status =
                                        'completed'
                                ),
                                0
                            )
                                AS completed_navigation_count,

                            COALESCE(
                                SUM(
                                    distance_meters
                                ),
                                0
                            )
                                AS planned_distance_meters,

                            COALESCE(
                                SUM(
                                    duration_seconds
                                ),
                                0
                            )
                                AS planned_navigation_seconds,

                            COALESCE(
                                SUM(
                                    actual_distance_meters
                                ),
                                0
                            )
                                AS actual_distance_meters,

                            COALESCE(
                                SUM(
                                    actual_duration_seconds
                                ),
                                0
                            )
                                AS actual_navigation_seconds

                        FROM navigation_records

                        WHERE user_id = ?

                        AND DATE(
                            CONVERT_TZ(
                                created_at,
                                '+00:00',
                                '+08:00'
                            )
                        ) = ?
                    `,
                    [
                        userId,
                        date
                    ]
                );

            /*
             * 功能使用次數
             */
            const [eventSummaryRows] =
                await db.execute(
                    `
                        SELECT
                            COUNT(*)
                                AS usage_event_count

                        FROM usage_events

                        WHERE user_id = ?

                        AND DATE(
                            CONVERT_TZ(
                                created_at,
                                '+00:00',
                                '+08:00'
                            )
                        ) = ?
                    `,
                    [
                        userId,
                        date
                    ]
                );

            /*
             * 各功能事件統計
             */
            const [eventBreakdown] =
                await db.execute(
                    `
                        SELECT
                            event_type,

                            COUNT(*)
                                AS count

                        FROM usage_events

                        WHERE user_id = ?

                        AND DATE(
                            CONVERT_TZ(
                                created_at,
                                '+00:00',
                                '+08:00'
                            )
                        ) = ?

                        GROUP BY
                            event_type

                        ORDER BY
                            count DESC
                    `,
                    [
                        userId,
                        date
                    ]
                );

            /*
             * 回饋數量
             */
            const [feedbackRows] =
                await db.execute(
                    `
                        SELECT
                            COUNT(*)
                                AS feedback_count

                        FROM feedback

                        WHERE user_id = ?

                        AND DATE(
                            CONVERT_TZ(
                                created_at,
                                '+00:00',
                                '+08:00'
                            )
                        ) = ?
                    `,
                    [
                        userId,
                        date
                    ]
                );

            /*
             * 當天導航詳細紀錄
             */
            const [navigationRecords] =
                await db.execute(
                    `
                        SELECT
                            navigation_id,
                            start_address,
                            end_address,

                            start_latitude,
                            start_longitude,

                            end_latitude,
                            end_longitude,

                            distance_meters,
                            duration_seconds,

                            actual_distance_meters,
                            actual_duration_seconds,

                            status,
                            started_at,
                            ended_at,
                            created_at

                        FROM navigation_records

                        WHERE user_id = ?

                        AND DATE(
                            CONVERT_TZ(
                                created_at,
                                '+00:00',
                                '+08:00'
                            )
                        ) = ?

                        ORDER BY
                            created_at DESC
                    `,
                    [
                        userId,
                        date
                    ]
                );

            const sessions =
                sessionRows[0];

            const navigation =
                navigationSummaryRows[0];

            const events =
                eventSummaryRows[0];

            const feedbackSummary =
                feedbackRows[0];

            return res.status(200).json({
                success: true,

                date,

                summary: {
                    app_usage_seconds:
                        Number(
                            sessions
                                .app_usage_seconds
                        ),

                    session_count:
                        Number(
                            sessions
                                .session_count
                        ),

                    navigation_count:
                        Number(
                            navigation
                                .navigation_count
                        ),

                    completed_navigation_count:
                        Number(
                            navigation
                                .completed_navigation_count
                        ),

                    planned_distance_meters:
                        Number(
                            navigation
                                .planned_distance_meters
                        ),

                    planned_navigation_seconds:
                        Number(
                            navigation
                                .planned_navigation_seconds
                        ),

                    actual_distance_meters:
                        Number(
                            navigation
                                .actual_distance_meters
                        ),

                    actual_navigation_seconds:
                        Number(
                            navigation
                                .actual_navigation_seconds
                        ),

                    usage_event_count:
                        Number(
                            events
                                .usage_event_count
                        ),

                    feedback_count:
                        Number(
                            feedbackSummary
                                .feedback_count
                        )
                },

                event_breakdown:
                    eventBreakdown.map(
                        (item) => ({
                            event_type:
                                item.event_type,

                            count:
                                Number(
                                    item.count
                                )
                        })
                    ),

                navigation_records:
                    navigationRecords
            });

        } catch (error) {

            console.error(
                "Get daily records failed:",
                error
            );

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "DAILY_RECORDS_FAILED",

                    message:
                        "查詢每日紀錄失敗",

                    retryable:
                        false
                }
            });
        }
    }
);


/* =========================================================
   10. 使用分析
========================================================= */

app.get(
    "/api/analytics/usage",
    async (req, res) => {
        try {
            const userId =
                parsePositiveInteger(
                    req.query.user_id
                );

            const from =
                String(
                    req.query.from ||
                    ""
                );

            const to =
                String(
                    req.query.to ||
                    ""
                );

            if (
                userId == null ||
                !isValidDateString(from) ||
                !isValidDateString(to) ||
                from > to
            ) {
                return res.status(400).json({
                    success: false,
                    error: {
                        code:
                            "INVALID_REQUEST",

                        message:
                            "user_id、from、to 格式不正確，日期格式為 YYYY-MM-DD",

                        retryable:
                            false
                    }
                });
            }

            /*
             * App 使用總時間
             */
            const [sessionTotalsRows] =
                await db.execute(
                    `
                        SELECT
                            COALESCE(
                                SUM(
                                    duration_seconds
                                ),
                                0
                            )
                                AS app_usage_seconds,

                            COUNT(*)
                                AS session_count,

                            COUNT(
                                DISTINCT DATE(
                                    CONVERT_TZ(
                                        started_at,
                                        '+00:00',
                                        '+08:00'
                                    )
                                )
                            )
                                AS active_days

                        FROM app_sessions

                        WHERE user_id = ?

                        AND DATE(
                            CONVERT_TZ(
                                started_at,
                                '+00:00',
                                '+08:00'
                            )
                        )
                        BETWEEN ? AND ?
                    `,
                    [
                        userId,
                        from,
                        to
                    ]
                );

            /*
             * 導航統計
             */
            const [navigationTotalsRows] =
                await db.execute(
                    `
                        SELECT
                            COUNT(*)
                                AS navigation_count,

                            COALESCE(
                                SUM(
                                    status =
                                        'completed'
                                ),
                                0
                            )
                                AS completed_navigation_count,

                            COALESCE(
                                SUM(
                                    distance_meters
                                ),
                                0
                            )
                                AS planned_distance_meters,

                            COALESCE(
                                SUM(
                                    actual_distance_meters
                                ),
                                0
                            )
                                AS actual_distance_meters,

                            COALESCE(
                                SUM(
                                    actual_duration_seconds
                                ),
                                0
                            )
                                AS actual_navigation_seconds

                        FROM navigation_records

                        WHERE user_id = ?

                        AND DATE(
                            CONVERT_TZ(
                                created_at,
                                '+00:00',
                                '+08:00'
                            )
                        )
                        BETWEEN ? AND ?
                    `,
                    [
                        userId,
                        from,
                        to
                    ]
                );

            /*
             * 功能事件數量
             */
            const [eventTotalsRows] =
                await db.execute(
                    `
                        SELECT
                            COUNT(*)
                                AS usage_event_count

                        FROM usage_events

                        WHERE user_id = ?

                        AND DATE(
                            CONVERT_TZ(
                                created_at,
                                '+00:00',
                                '+08:00'
                            )
                        )
                        BETWEEN ? AND ?
                    `,
                    [
                        userId,
                        from,
                        to
                    ]
                );

            /*
             * 回饋數量
             */
            const [feedbackTotalsRows] =
                await db.execute(
                    `
                        SELECT
                            COUNT(*)
                                AS feedback_count

                        FROM feedback

                        WHERE user_id = ?

                        AND DATE(
                            CONVERT_TZ(
                                created_at,
                                '+00:00',
                                '+08:00'
                            )
                        )
                        BETWEEN ? AND ?
                    `,
                    [
                        userId,
                        from,
                        to
                    ]
                );

            /*
             * 每天 App 使用時間
             */
            const [dailySessions] =
                await db.execute(
                    `
            SELECT
                DATE_FORMAT(
                    CONVERT_TZ(
                        started_at,
                        '+00:00',
                        '+08:00'
                    ),
                    '%Y-%m-%d'
                ) AS date,

                COALESCE(
                    SUM(duration_seconds),
                    0
                ) AS app_usage_seconds

            FROM app_sessions

            WHERE user_id = ?

            AND DATE(
                CONVERT_TZ(
                    started_at,
                    '+00:00',
                    '+08:00'
                )
            ) BETWEEN ? AND ?

            GROUP BY
                DATE_FORMAT(
                    CONVERT_TZ(
                        started_at,
                        '+00:00',
                        '+08:00'
                    ),
                    '%Y-%m-%d'
                )

            ORDER BY date ASC
        `,
                    [
                        userId,
                        from,
                        to
                    ]
                );

            /*
             * 每天導航資料
             */
            const [dailyNavigation] =
                await db.execute(
                    `
            SELECT
                DATE_FORMAT(
                    CONVERT_TZ(
                        created_at,
                        '+00:00',
                        '+08:00'
                    ),
                    '%Y-%m-%d'
                ) AS date,

                COUNT(*) AS navigation_count,

                COALESCE(
                    SUM(actual_distance_meters),
                    0
                ) AS actual_distance_meters,

                COALESCE(
                    SUM(actual_duration_seconds),
                    0
                ) AS actual_navigation_seconds

            FROM navigation_records

            WHERE user_id = ?

            AND DATE(
                CONVERT_TZ(
                    created_at,
                    '+00:00',
                    '+08:00'
                )
            ) BETWEEN ? AND ?

            GROUP BY
                DATE_FORMAT(
                    CONVERT_TZ(
                        created_at,
                        '+00:00',
                        '+08:00'
                    ),
                    '%Y-%m-%d'
                )

            ORDER BY date ASC
        `,
                    [
                        userId,
                        from,
                        to
                    ]
                );

            /*
             * 常用目的地
             */
            const [topDestinations] =
                await db.execute(
                    `
                        SELECT
                            end_address,

                            COUNT(*)
                                AS count

                        FROM navigation_records

                        WHERE user_id = ?

                        AND end_address IS NOT NULL

                        AND DATE(
                            CONVERT_TZ(
                                created_at,
                                '+00:00',
                                '+08:00'
                            )
                        )
                        BETWEEN ? AND ?

                        GROUP BY
                            end_address

                        ORDER BY
                            count DESC

                        LIMIT 10
                    `,
                    [
                        userId,
                        from,
                        to
                    ]
                );

            /*
             * 常用功能
             */
            const [topFeatures] =
                await db.execute(
                    `
                        SELECT
                            event_type,

                            COUNT(*)
                                AS count

                        FROM usage_events

                        WHERE user_id = ?

                        AND DATE(
                            CONVERT_TZ(
                                created_at,
                                '+00:00',
                                '+08:00'
                            )
                        )
                        BETWEEN ? AND ?

                        GROUP BY
                            event_type

                        ORDER BY
                            count DESC

                        LIMIT 20
                    `,
                    [
                        userId,
                        from,
                        to
                    ]
                );

            const sessionTotals =
                sessionTotalsRows[0];

            const navigationTotals =
                navigationTotalsRows[0];

            const eventTotals =
                eventTotalsRows[0];

            const feedbackTotals =
                feedbackTotalsRows[0];

            /*
             * 合併每天使用時間與導航資料
             */
            const dailyMap =
                new Map();

            for (
                const item of dailySessions
            ) {
                dailyMap.set(
                    item.date,
                    {
                        date:
                            item.date,

                        app_usage_seconds:
                            Number(
                                item
                                    .app_usage_seconds
                            ),

                        navigation_count:
                            0,

                        actual_distance_meters:
                            0,

                        actual_navigation_seconds:
                            0
                    }
                );
            }

            for (
                const item of dailyNavigation
            ) {
                const current =
                    dailyMap.get(
                        item.date
                    ) || {
                        date:
                            item.date,

                        app_usage_seconds:
                            0,

                        navigation_count:
                            0,

                        actual_distance_meters:
                            0,

                        actual_navigation_seconds:
                            0
                    };

                current.navigation_count =
                    Number(
                        item.navigation_count
                    );

                current.actual_distance_meters =
                    Number(
                        item
                            .actual_distance_meters
                    );

                current.actual_navigation_seconds =
                    Number(
                        item
                            .actual_navigation_seconds
                    );

                dailyMap.set(
                    item.date,
                    current
                );
            }

            const daily =
                Array.from(
                    dailyMap.values()
                ).sort(
                    (a, b) =>
                        a.date.localeCompare(
                            b.date
                        )
                );

            const activeDays =
                Number(
                    sessionTotals
                        .active_days
                );

            const appUsageSeconds =
                Number(
                    sessionTotals
                        .app_usage_seconds
                );

            const navigationCount =
                Number(
                    navigationTotals
                        .navigation_count
                );

            const actualDistance =
                Number(
                    navigationTotals
                        .actual_distance_meters
                );

            const actualNavigationSeconds =
                Number(
                    navigationTotals
                        .actual_navigation_seconds
                );

            return res.status(200).json({
                success: true,

                period: {
                    from,
                    to
                },

                totals: {
                    app_usage_seconds:
                        appUsageSeconds,

                    session_count:
                        Number(
                            sessionTotals
                                .session_count
                        ),

                    active_days:
                        activeDays,

                    navigation_count:
                        navigationCount,

                    completed_navigation_count:
                        Number(
                            navigationTotals
                                .completed_navigation_count
                        ),

                    planned_distance_meters:
                        Number(
                            navigationTotals
                                .planned_distance_meters
                        ),

                    actual_distance_meters:
                        actualDistance,

                    actual_navigation_seconds:
                        actualNavigationSeconds,

                    usage_event_count:
                        Number(
                            eventTotals
                                .usage_event_count
                        ),

                    feedback_count:
                        Number(
                            feedbackTotals
                                .feedback_count
                        )
                },

                averages: {
                    average_daily_usage_seconds:
                        activeDays > 0
                            ? Math.round(
                                appUsageSeconds /
                                activeDays
                            )
                            : 0,

                    average_navigation_distance_meters:
                        navigationCount > 0
                            ? Math.round(
                                actualDistance /
                                navigationCount
                            )
                            : 0,

                    average_navigation_duration_seconds:
                        navigationCount > 0
                            ? Math.round(
                                actualNavigationSeconds /
                                navigationCount
                            )
                            : 0
                },

                daily,

                top_destinations:
                    topDestinations.map(
                        (item) => ({
                            end_address:
                                item.end_address,

                            count:
                                Number(
                                    item.count
                                )
                        })
                    ),

                top_features:
                    topFeatures.map(
                        (item) => ({
                            event_type:
                                item.event_type,

                            count:
                                Number(
                                    item.count
                                )
                        })
                    )
            });

        } catch (error) {

            console.error(
                "Get usage analytics failed:",
                error
            );

            return res.status(500).json({
                success: false,
                error: {
                    code:
                        "ANALYTICS_FAILED",

                    message:
                        "查詢使用分析失敗",

                    retryable:
                        false
                }
            });
        }
    }
);
/* =========================================================
   11. 家屬地圖儀表板（定位追蹤心跳 / 靜止保底心跳 / 儀表板讀取）

   路由實作於 family.js，此處僅掛載，不更動既有路由與邏輯。
========================================================= */

app.use("/api", familyRouter);

/* =========================================================
   12. App 內建家屬模式（配對碼 / 導航紀錄列表 / 單趟詳情）

   路由實作於 family_pairing.js，此處僅掛載，不更動既有路由與邏輯。
========================================================= */

app.use("/api", familyPairingRouter);

/* =========================================================
   13. 語音助理問答（Gemini）
========================================================= */

app.post("/api/assistant/ask", async (req, res) => {
    try {
        const { user_id, question, latitude, longitude, history } = req.body;

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

        if (typeof question !== "string" || !question.trim()) {
            return res.status(400).json({
                success: false,
                error: {
                    code: "INVALID_QUESTION",
                    message: "question 為必填欄位",
                    retryable: false
                }
            });
        }

        // 經緯度為選填——沒有的話照樣能問（例如定位還沒取得），只是
        // Gemini 會被明確告知「沒有位置資料」，不會因此亂猜地點。
        const latitudeNumber = Number(latitude);
        const longitudeNumber = Number(longitude);
        const hasLocation =
            Number.isFinite(latitudeNumber) &&
            Number.isFinite(longitudeNumber) &&
            latitudeNumber >= -90 &&
            latitudeNumber <= 90 &&
            longitudeNumber >= -180 &&
            longitudeNumber <= 180;

        if (!process.env.GEMINI_API_KEY) {
            console.error("Missing GEMINI_API_KEY in backend/.env");

            return res.status(500).json({
                success: false,
                error: {
                    code: "MISSING_GEMINI_API_KEY",
                    message: "伺服器未設定 GEMINI_API_KEY",
                    retryable: false
                }
            });
        }

        // history 為選填，且不信任前端送來的內容——只挑出 role/text 都是
        // 字串的項目，並限制最多取最近 20 輪（10 次問答），避免有人塞進
        // 異常大的內容拖慢或拖垮 Gemini 請求。
        const safeHistory = Array.isArray(history)
            ? history
                .filter(
                    (turn) =>
                        turn &&
                        typeof turn.role === "string" &&
                        typeof turn.text === "string"
                )
                .slice(-20)
            : [];

        let currentAddress = null;
        let nearbyPlaces = [];

        // Places 文字搜尋用的關鍵字，不是只用這一句問題——如果使用者是在
        // 追問前一句（例如先問「附近有超商嗎」，接著說「那幫我導航過去」），
        // 單獨看「那幫我導航過去」這句話，Google 完全查不到相關地點，會
        // 讓這一輪的真實地點清單變成空的，使用者前一句問到的店反而被擋掉
        // 沒辦法導航過去。把最近一句使用者說過的話也併進查詢文字，讓這種
        // 常見的「先問有沒有、再說帶我去」的追問一樣查得到真實資料。
        const lastUserHistoryText = safeHistory
            .filter((turn) => turn.role === "user")
            .map((turn) => turn.text)
            .slice(-1)[0];
        const placesQuery = lastUserHistoryText
            ? `${lastUserHistoryText} ${question.trim()}`
            : question.trim();

        if (hasLocation) {
            const [addressResult, placesResult] = await Promise.all([
                requestGoogleApi(GOOGLE_GEOCODING_URL, {
                    latlng: `${latitudeNumber},${longitudeNumber}`,
                    language: "zh-TW",
                    key: process.env.GOOGLE_MAPS_API_KEY
                }).catch((error) => {
                    console.error(
                        "問問題時反查地址失敗，將以沒有地址資料的方式繼續:",
                        error?.response?.data || error.message
                    );
                    return null;
                }),
                searchNearbyPlaces(
                    latitudeNumber,
                    longitudeNumber,
                    placesQuery
                )
            ]);

            currentAddress =
                addressResult?.data?.results?.[0]?.formatted_address || null;
            nearbyPlaces = placesResult;
        }

        const result = await askGemini(
            question.trim(),
            currentAddress,
            nearbyPlaces,
            safeHistory
        );

        if (result.type === "navigate") {
            return res.status(200).json({
                success: true,
                action: "navigate",
                destination_name: result.destinationName,
                destination_address: result.destinationAddress
            });
        }

        return res.status(200).json({
            success: true,
            action: "speak",
            answer: result.text
        });

    } catch (error) {
        console.error(
            "Assistant ask failed:",
            error?.response?.data || error.message
        );

        return res.status(502).json({
            success: false,
            error: {
                code: "ASSISTANT_REQUEST_FAILED",
                message: "語音助理暫時無法回答，請稍後再試",
                retryable: shouldRetry(error)
            }
        });
    }
});

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