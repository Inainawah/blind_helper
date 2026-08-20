-- Blind Helper Cloud Relational Database Schema
-- Database: MySQL 8.x
-- 說明：本 Schema 對應 Blind Helper RESTful API。

CREATE DATABASE IF NOT EXISTS blind_helper
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE blind_helper;

-- 1. users 使用者資料表
CREATE TABLE users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    phone VARCHAR(30),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 2. user_preferences 使用者偏好設定表
CREATE TABLE user_preferences (
    preference_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    voice_speed ENUM('slow', 'normal', 'fast') DEFAULT 'normal',
    navigation_mode ENUM('walking', 'indoor', 'outdoor') DEFAULT 'walking',
    vibration_enabled BOOLEAN DEFAULT TRUE,
    sound_enabled BOOLEAN DEFAULT TRUE,
    obstacle_alert_distance INT DEFAULT 3,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_preferences_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
);

-- 3. locations 定位紀錄表
CREATE TABLE locations (
    location_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    accuracy FLOAT,
    address VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_locations_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
);

-- 4. navigation_records 導航路徑紀錄表
CREATE TABLE navigation_records (
    navigation_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    start_address VARCHAR(255),
    end_address VARCHAR(255),
    start_latitude DECIMAL(10,7),
    start_longitude DECIMAL(10,7),
    end_latitude DECIMAL(10,7),
    end_longitude DECIMAL(10,7),
    distance_meters INT,
    duration_seconds INT,
    route_summary TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_navigation_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
);

-- 5. emergency_contacts 緊急聯絡人資料表
CREATE TABLE emergency_contacts (
    contact_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    contact_name VARCHAR(100) NOT NULL,
    phone VARCHAR(30) NOT NULL,
    relationship VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_emergency_contacts_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
);

-- 6. emergency_logs 緊急求助紀錄表
CREATE TABLE emergency_logs (
    emergency_log_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    contact_id INT,
    latitude DECIMAL(10,7),
    longitude DECIMAL(10,7),
    address VARCHAR(255),
    message TEXT,
    status ENUM('pending', 'sent', 'failed', 'resolved') DEFAULT 'pending',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_emergency_logs_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_emergency_logs_contact
        FOREIGN KEY (contact_id) REFERENCES emergency_contacts(contact_id)
        ON DELETE SET NULL
);

-- 7. object_detections 相機/物件辨識紀錄表
CREATE TABLE object_detections (
    detection_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    object_name VARCHAR(100) NOT NULL,
    confidence DECIMAL(5,4),
    description TEXT,
    image_url VARCHAR(255),
    latitude DECIMAL(10,7),
    longitude DECIMAL(10,7),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_object_detections_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
);

-- 8. voice_commands 語音指令紀錄表
CREATE TABLE voice_commands (
    voice_command_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    command_text TEXT NOT NULL,
    intent VARCHAR(100),
    response_text TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_voice_commands_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
);

-- 9. auth_tokens 登入 Token 紀錄表（選用）
CREATE TABLE auth_tokens (
    token_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expired_at DATETIME,
    revoked BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_auth_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
);

-- 10. system_logs 系統操作紀錄表
CREATE TABLE system_logs (
    system_log_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT,
    action VARCHAR(100) NOT NULL,
    description TEXT,
    ip_address VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_system_logs_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE SET NULL
);

-- Indexes
CREATE INDEX idx_locations_user_created ON locations(user_id, created_at);
CREATE INDEX idx_navigation_user_created ON navigation_records(user_id, created_at);
CREATE INDEX idx_emergency_logs_user_created ON emergency_logs(user_id, created_at);
CREATE INDEX idx_object_detections_user_created ON object_detections(user_id, created_at);
CREATE INDEX idx_voice_commands_user_created ON voice_commands(user_id, created_at);
CREATE INDEX idx_system_logs_user_created ON system_logs(user_id, created_at);

-- =========================================================
-- 家屬地圖儀表板模組（新增，增量開發，不影響上方既有資料表）
-- 對應 backend/family.js
-- =========================================================

-- 11. location_logs 移動軌跡紀錄表
-- 僅在兩點距離 > 5 公尺時才會寫入一筆（過濾 GPS 微小漂移），
-- 因此可直接依 recorded_at 排序後用 Polyline 畫出今日移動軌跡。
CREATE TABLE location_logs (
    log_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    accuracy FLOAT,
    recorded_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_location_logs_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
);

-- 12. stay_points 停留點紀錄表
-- 使用者在同一個 5 公尺半徑內持續停留達門檻時間即產生一筆紀錄。
-- left_at 為 NULL 代表使用者「目前仍在」此停留點（尚未離開），
-- 家屬端可用 arrived_at 與 NOW() 即時算出目前已停留多久。
CREATE TABLE stay_points (
    stay_point_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL,
    center_latitude DECIMAL(10,7) NOT NULL,
    center_longitude DECIMAL(10,7) NOT NULL,
    radius_meters SMALLINT UNSIGNED DEFAULT 5,
    arrived_at TIMESTAMP NOT NULL,
    left_at TIMESTAMP NULL,
    address VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_stay_points_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
);

-- 13. device_status 裝置狀態表（每位使用者一列）
-- 同時作為「目前所在停留群集」的持久化狀態（cluster_started_at /
-- cluster_latitude / cluster_longitude / open_stay_point_id），
-- 讓停留點偵測演算法可以無狀態地運作於任何一台 API 伺服器，
-- 並在伺服器重啟後仍能正確還原偵測進度。
CREATE TABLE device_status (
    user_id INT PRIMARY KEY,
    battery_level TINYINT UNSIGNED,
    is_charging BOOLEAN,
    last_latitude DECIMAL(10,7),
    last_longitude DECIMAL(10,7),
    last_seen_at TIMESTAMP NULL,
    app_version VARCHAR(50),

    -- 停留點演算法的群集狀態（實作細節，不對外部 API 曝露）
    cluster_started_at TIMESTAMP NULL,
    cluster_latitude DECIMAL(10,7),
    cluster_longitude DECIMAL(10,7),
    open_stay_point_id INT NULL,

    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT fk_device_status_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_device_status_open_stay_point
        FOREIGN KEY (open_stay_point_id) REFERENCES stay_points(stay_point_id)
        ON DELETE SET NULL
);

CREATE INDEX idx_location_logs_user_recorded ON location_logs(user_id, recorded_at);
CREATE INDEX idx_stay_points_user_arrived ON stay_points(user_id, arrived_at);
CREATE INDEX idx_device_status_last_seen ON device_status(last_seen_at);

-- =========================================================
-- App 內建家屬模式（配對碼 + 導航紀錄列表）
-- 對應 backend/family_pairing.js
-- =========================================================

-- 14. device_profiles 裝置配對碼表
-- App 目前沒有登入機制，因此每台裝置第一次啟動時，會呼叫
-- POST /api/devices/register 自動建立一組 users 資料列（供既有的
-- navigation_records / object_detections 等資料表沿用既有的 user_id 外鍵，
-- 不需要更動任何既有資料表結構），並產生一組 6 碼配對碼給家屬輸入查詢。
CREATE TABLE device_profiles (
    profile_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NOT NULL UNIQUE,
    device_id VARCHAR(100) NOT NULL UNIQUE,
    pairing_code CHAR(6) NOT NULL UNIQUE,
    display_name VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_device_profiles_user
        FOREIGN KEY (user_id) REFERENCES users(user_id)
        ON DELETE CASCADE
);

-- object_detections 新增欄位：把相機警報跟某一趟導航關聯起來，
-- 才能在家屬模式的導航紀錄列表算出「N 次警報」，並在詳情地圖上標出警報位置。
-- 為 NULL 代表沒有 App 都可以正常寫入（既有呼叫端不受影響）。
ALTER TABLE object_detections
    ADD COLUMN navigation_id INT NULL AFTER user_id,
    ADD CONSTRAINT fk_object_detections_navigation
        FOREIGN KEY (navigation_id) REFERENCES navigation_records(navigation_id)
        ON DELETE SET NULL;

CREATE INDEX idx_device_profiles_pairing_code ON device_profiles(pairing_code);
CREATE INDEX idx_object_detections_navigation ON object_detections(navigation_id);
