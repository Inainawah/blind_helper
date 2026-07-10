const express = require("express");
const cors = require("cors");
const axios = require("axios");
require("dotenv").config();

const db = require("./db");

const app = express();

app.use(cors());
app.use(express.json());

// ==================== 1. 儲存位置 ====================
app.post("/api/locations", async (req, res) => {
    try {
        const { user_id, latitude, longitude, accuracy } = req.body;

        if (!user_id || latitude == null || longitude == null) {
            return res.status(400).json({
                success: false,
                message: "user_id, latitude, and longitude are required"
            });
        }

        const geoResponse = await axios.get(
            "https://maps.googleapis.com/maps/api/geocode/json",
            {
                params: {
                    latlng: `${latitude},${longitude}`,
                    language: "zh-TW",
                    key: process.env.GOOGLE_MAPS_API_KEY
                }
            }
        );

        if (geoResponse.data.status !== "OK") {
            return res.status(400).json({
                success: false,
                message: geoResponse.data.status,
                error_message: geoResponse.data.error_message
            });
        }

        const address = geoResponse.data.results[0].formatted_address;

        const sql = `
            INSERT INTO locations
            (user_id, latitude, longitude, accuracy, address)
            VALUES (?, ?, ?, ?, ?)
        `;

        const [result] = await db.execute(sql, [
            user_id,
            latitude,
            longitude,
            accuracy || null,
            address
        ]);

        res.status(201).json({
            success: true,
            message: "Location created successfully",
            location_id: result.insertId,
            address: address
        });

    } catch (error) {
        if (error.code === "ER_NO_REFERENCED_ROW_2") {
            return res.status(404).json({
                success: false,
                message: "User not found"
            });
        }
        console.error(error);
        res.status(500).json({ success: false, message: "Internal server error" });
    }
});

// ==================== 2. 反向地理解析 ====================
app.post("/api/locations/reverse-geocode", async (req, res) => {
    try {
        const { latitude, longitude } = req.body;

        if (latitude == null || longitude == null) {
            return res.status(400).json({
                success: false,
                message: "latitude and longitude are required"
            });
        }

        const response = await axios.get(
            "https://maps.googleapis.com/maps/api/geocode/json",
            {
                params: {
                    latlng: `${latitude},${longitude}`,
                    language: "zh-TW",
                    key: process.env.GOOGLE_MAPS_API_KEY
                }
            }
        );

        if (response.data.status !== "OK") {
            return res.status(400).json({
                success: false,
                message: response.data.status,
                error_message: response.data.error_message
            });
        }

        const address = response.data.results[0].formatted_address;

        res.status(200).json({ success: true, address: address });

    } catch (error) {
        console.error(error);
        res.status(500).json({ success: false, message: "Internal server error" });
    }
});

// ==================== 3. 完美相容：二合一導航 API ====================
// 不管 start 是傳文字地址還是 "緯度,經度" 字串，通通走這條！
app.post("/api/navigation/directions", async (req, res) => {
    try {
        const { start, destination } = req.body;

        // 驗證欄位是否存在
        if (!start || !destination) {
            return res.status(400).json({
                success: false,
                message: "start and destination are required"
            });
        }

        // 直接發送給 Google Maps API，Google 會自動辨識 start 是文字還是經緯度
        const response = await axios.get(
            "https://maps.googleapis.com/maps/api/directions/json",
            {
                params: {
                    origin: start,
                    destination: destination,
                    mode: "walking",
                    language: "zh-TW",
                    key: process.env.GOOGLE_MAPS_API_KEY
                }
            }
        );

        if (response.data.status !== "OK") {
            return res.status(400).json({
                success: false,
                message: response.data.status,
                error_message: response.data.error_message
            });
        }

        const route = response.data.routes[0];
        const leg = route.legs[0];

        const steps = leg.steps.map((step, index) => ({
            step_order: index + 1,
            instruction: step.html_instructions.replace(/<[^>]*>/g, ""),
            distance: step.distance.text,
            duration: step.duration.text,
            start_location: step.start_location,
            end_location: step.end_location
        }));

        res.status(200).json({
            success: true,
            start_address: leg.start_address,
            end_address: leg.end_address,
            distance: leg.distance.text,
            duration: leg.duration.text,
            steps: steps
        });

    } catch (error) {
        console.error(error);
        res.status(500).json({ success: false, message: "Internal server error" });
    }
});

app.listen(process.env.PORT || 3000, () => {
    console.log(`Server running on port ${process.env.PORT || 3000}`);
});