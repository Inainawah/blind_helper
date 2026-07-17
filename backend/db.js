const mysql = require("mysql2/promise");
require("dotenv").config();

/*
 * 本機環境使用：
 * DB_HOST、DB_PORT、DB_USER、DB_PASSWORD、DB_NAME
 *
 * Railway 環境使用：
 * MYSQLHOST、MYSQLPORT、MYSQLUSER、MYSQLPASSWORD、MYSQLDATABASE
 */
const dbConfig = {
    host:
        process.env.DB_HOST ||
        process.env.MYSQLHOST,

    port: Number(
        process.env.DB_PORT ||
        process.env.MYSQLPORT ||
        3306
    ),

    user:
        process.env.DB_USER ||
        process.env.MYSQLUSER,

    password:
        process.env.DB_PASSWORD ||
        process.env.MYSQLPASSWORD,

    database:
        process.env.DB_NAME ||
        process.env.MYSQLDATABASE,

    waitForConnections: true,
    connectionLimit: 10,
    queueLimit: 0,

    enableKeepAlive: true,
    keepAliveInitialDelay: 0
};

/*
 * 啟動前檢查必要的資料庫設定。
 * 避免部署後因環境變數缺少而難以查錯。
 */
const requiredSettings = [
    ["host", dbConfig.host],
    ["user", dbConfig.user],
    ["password", dbConfig.password],
    ["database", dbConfig.database]
];

const missingSettings = requiredSettings
    .filter(([, value]) => !value)
    .map(([name]) => name);

if (missingSettings.length > 0) {
    throw new Error(
        `Missing database configuration: ${missingSettings.join(", ")}`
    );
}

const pool = mysql.createPool(dbConfig);

module.exports = pool;