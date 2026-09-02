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

    const toLatLng = (p) => ({ latitude: Number(p.latitude), longitude: Number(p.longitude) });

    const centroidOf = (pts) => {
        const lat = pts.reduce((sum, p) => sum + Number(p.latitude), 0) / pts.length;
        const lng = pts.reduce((sum, p) => sum + Number(p.longitude), 0) / pts.length;
        return { latitude: lat, longitude: lng };
    };

    // clusterPoints：目前這個群集裡「真的拿來算中心點座標」的點，不含被判定
    // 為單次雜訊、跳出去又立刻跳回來的離群點；clusterStart/clusterEnd 則是
    // 這個群集實際涵蓋的起訖時間（連雜訊點本身的時間也算進去，這樣停留了
    // 多久才不會被雜訊誤判打斷）。
    let clusterPoints = [points[0]];
    let clusterStart = points[0];
    let clusterEnd = points[0];

    const flushCluster = () => {
        if (clusterPoints.length < 2) return;

        const arrivedAt = new Date(clusterStart.recorded_at);
        const leftAt = new Date(clusterEnd.recorded_at);
        const durationSeconds = (leftAt.getTime() - arrivedAt.getTime()) / 1000;

        if (durationSeconds >= minDurationSeconds) {
            const center = centroidOf(clusterPoints);
            stays.push({
                latitude: center.latitude,
                longitude: center.longitude,
                arrived_at: clusterStart.recorded_at,
                left_at: clusterEnd.recorded_at,
                duration_seconds: Math.round(durationSeconds)
            });
        }
    };

    let i = 1;
    while (i < points.length) {
        // 跟「目前群集的中心點」比距離，而不是只看前一個點或固定的第一個點：
        // 手機 GPS 就算人完全沒動，連續兩次定位偶爾也會跳動 5~9 公尺（屬於
        // 正常誤差範圍），用平均中心點可以讓單一次的跳動不會影響太大。
        const center = centroidOf(clusterPoints);
        const currentPoint = points[i];
        const distanceFromCenter = distanceMeters(center, toLatLng(currentPoint));

        if (distanceFromCenter <= radiusMeters) {
            clusterPoints.push(currentPoint);
            clusterEnd = currentPoint;
            i++;
            continue;
        }

        // 這個點超出範圍，先看下一個點是不是馬上又跳回範圍內：如果是，
        // 代表這只是單次的定位雜訊（GPS 偶爾的跳動），忽略這個點本身
        // （不拿去平均中心點座標），但時間照樣往前推進、不中斷整段停留；
        // 如果接下來也持續在範圍外，才代表使用者真的離開了，正式結束這個群集。
        const nextPoint = points[i + 1];
        const nextIsBackInRange =
            nextPoint != null && distanceMeters(center, toLatLng(nextPoint)) <= radiusMeters;

        if (nextIsBackInRange) {
            clusterEnd = currentPoint;
            i++;
        } else {
            flushCluster();
            clusterPoints = [currentPoint];
            clusterStart = currentPoint;
            clusterEnd = currentPoint;
            i++;
        }
    }
    flushCluster();

    return stays;
}

module.exports = {
    distanceMeters,
    computeStayPoints
};
