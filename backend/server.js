const express = require("express");
const cors = require("cors");
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

app.listen(process.env.PORT || 3000, () => {
    console.log(`Server running on port ${process.env.PORT || 3000}`);
});