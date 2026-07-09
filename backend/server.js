const express = require("express");
const cors = require("cors");
const axios = require("axios");
require("dotenv").config();

const db = require("./db");

const app = express();

app.use(cors());
app.use(express.json());

app.post("/api/locations", async (req, res) => {
    try {
        const { user_id, latitude, longitude, accuracy, address } = req.body;

        if (!user_id || latitude == null || longitude == null) {
            return res.status(400).json({
                success: false,
                message: "user_id, latitude, and longitude are required"
            });
        }

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
            address || null
        ]);

        res.status(201).json({
            success: true,
            message: "Location created successfully",
            location_id: result.insertId
        });
    } catch (error) {
        if (error.code === "ER_NO_REFERENCED_ROW_2") {
            return res.status(404).json({
                success: false,
                message: "User not found"
            });
        }

        console.error(error);

        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
});
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

        res.status(200).json({
            success: true,
            address: address
        });

    } catch (error) {
        console.error(error);

        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
});
app.post("/api/navigation/directions", async (req, res) => {
    try {
        const {
            current_latitude,
            current_longitude,
            destination
        } = req.body;

        if (current_latitude == null || current_longitude == null || !destination) {
            return res.status(400).json({
                success: false,
                message: "current_latitude, current_longitude, and destination are required"
            });
        }

        const response = await axios.get(
            "https://maps.googleapis.com/maps/api/directions/json",
            {
                params: {
                    origin: `${current_latitude},${current_longitude}`,
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

        res.status(500).json({
            success: false,
            message: "Internal server error"
        });
    }
});

app.listen(process.env.PORT || 3000, () => {
    console.log(`Server running on port ${process.env.PORT || 3000}`);
});

