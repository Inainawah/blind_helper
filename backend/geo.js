/*
 * 共用地理計算工具。
 * 供 family.js（家屬地圖儀表板：移動軌跡 / 停留點 / 心跳）、
 * family_pairing.js（App 內建家屬模式：單趟導航的停留點回放）使用。
 */

const EARTH_RADIUS_M = 6371000;
const DEFAULT_STAY_RADIUS_M = 5;
const DEFAULT_STAY_MIN_DURATION_SECONDS = 5 * 60;

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

/**
 * 事後（不是即時）從一串按時間排序的座標點裡，找出「停留點」：
 * 連續相鄰的點彼此距離都在 radiusMeters 之內、且整段時間跨度達到
 * minDurationSeconds，就視為一個停留點，回傳該群集的平均座標與起訖時間。
 *
 * 跟 family.js 那套「即時、逐點判斷、需要持久化群集狀態」的演算法不同，
 * 這裡是單趟導航結束後，一次把完整的座標序列丟進來離線計算，
 * 不需要額外的狀態欄位。
 *
 * @param {{latitude:number, longitude:number, recorded_at:string|Date}[]} points 依時間由舊到新排序
 * @param {{radiusMeters?:number, minDurationSeconds?:number}} [options]
 * @returns {{latitude:number, longitude:number, arrived_at:*, left_at:*, duration_seconds:number}[]}
 */
function computeStayPoints(points, options = {}) {
    const radiusMeters = options.radiusMeters ?? DEFAULT_STAY_RADIUS_M;
    const minDurationSeconds = options.minDurationSeconds ?? DEFAULT_STAY_MIN_DURATION_SECONDS;

    const stays = [];
    if (!points || points.length === 0) return stays;

    let cluster = [points[0]];

    const flushCluster = () => {
        if (cluster.length < 2) return;

        const arrivedAt = new Date(cluster[0].recorded_at);
        const leftAt = new Date(cluster[cluster.length - 1].recorded_at);
        const durationSeconds = (leftAt.getTime() - arrivedAt.getTime()) / 1000;

        if (durationSeconds >= minDurationSeconds) {
            const avgLatitude =
                cluster.reduce((sum, p) => sum + Number(p.latitude), 0) / cluster.length;
            const avgLongitude =
                cluster.reduce((sum, p) => sum + Number(p.longitude), 0) / cluster.length;

            stays.push({
                latitude: avgLatitude,
                longitude: avgLongitude,
                arrived_at: cluster[0].recorded_at,
                left_at: cluster[cluster.length - 1].recorded_at,
                duration_seconds: Math.round(durationSeconds)
            });
        }
    };

    for (let i = 1; i < points.length; i++) {
        const previousPoint = points[i - 1];
        const currentPoint = points[i];

        const adjacentDistance = distanceMeters(
            { latitude: Number(previousPoint.latitude), longitude: Number(previousPoint.longitude) },
            { latitude: Number(currentPoint.latitude), longitude: Number(currentPoint.longitude) }
        );

        if (adjacentDistance <= radiusMeters) {
            cluster.push(currentPoint);
        } else {
            flushCluster();
            cluster = [currentPoint];
        }
    }
    flushCluster();

    return stays;
}

module.exports = {
    distanceMeters,
    computeStayPoints
};
