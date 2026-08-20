/*
 * 共用地理計算工具。
 * 供 family.js（家屬地圖儀表板：移動軌跡 / 停留點 / 心跳）使用。
 */

const EARTH_RADIUS_M = 6371000;

/**
 * Haversine 公式計算兩點間距離（公尺）。
 *
 * @param {{latitude:number, longitude:number}} a
 * @param {{latitude:number, longitude:number}} b
 * @returns {number}
 */
function distanceMeters(a, b) {
    const lat1 = (a.latitude * Math.PI) / 180;
    const lat2 = (b.latitude * Math.PI) / 180;
    const deltaLat = ((b.latitude - a.latitude) * Math.PI) / 180;
    const deltaLng = ((b.longitude - a.longitude) * Math.PI) / 180;

    const h =
        Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
        Math.cos(lat1) *
        Math.cos(lat2) *
        Math.sin(deltaLng / 2) *
        Math.sin(deltaLng / 2);

    const c = 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));

    return EARTH_RADIUS_M * c;
}

module.exports = {
    distanceMeters
};
