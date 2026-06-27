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
