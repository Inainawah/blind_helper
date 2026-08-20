import { useEffect, useMemo } from "react";
import { MapContainer, TileLayer, Polyline, Marker, Popup, useMap } from "react-leaflet";
import L from "leaflet";

const STAY_POINT_ICON = L.divIcon({
  className: "family-map-icon family-map-icon--stay",
  html: '<span class="family-map-icon__dot"></span>',
  iconSize: [18, 18],
  iconAnchor: [9, 9]
});

const CURRENT_POSITION_ICON = L.divIcon({
  className: "family-map-icon family-map-icon--current",
  html: '<span class="family-map-icon__pulse"></span><span class="family-map-icon__dot"></span>',
  iconSize: [22, 22],
  iconAnchor: [11, 11]
});

const DEFAULT_CENTER = [25.0330, 121.5654]; // 台北市，找不到任何資料時的預設中心點

function formatTime(isoString) {
  if (!isoString) return "—";
  return new Date(isoString).toLocaleTimeString("zh-TW", {
    hour: "2-digit",
    minute: "2-digit"
  });
}

function formatDuration(seconds) {
  if (!Number.isFinite(seconds)) return "—";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} 分鐘`;
  const hours = Math.floor(minutes / 60);
  return `${hours} 小時 ${minutes % 60} 分鐘`;
}

/** 資料更新時自動把地圖視野調整到能看見整條軌跡與所有停留點。 */
function AutoFitBounds({ points }) {
  const map = useMap();

  useEffect(() => {
    if (points.length === 0) return;
    if (points.length === 1) {
      map.setView(points[0], 17);
      return;
    }
    map.fitBounds(L.latLngBounds(points), { padding: [48, 48] });
  }, [points, map]);

  return null;
}

export default function FamilyMap({ path, stayPoints, currentPosition }) {
  const polylinePositions = useMemo(
    () => path.map((point) => [point.latitude, point.longitude]),
    [path]
  );

  const allPoints = useMemo(() => {
    const points = [...polylinePositions];
    stayPoints.forEach((sp) => points.push([sp.latitude, sp.longitude]));
    if (currentPosition) points.push([currentPosition.latitude, currentPosition.longitude]);
    return points;
  }, [polylinePositions, stayPoints, currentPosition]);

  const initialCenter = allPoints[0] || DEFAULT_CENTER;

  return (
    <MapContainer
      center={initialCenter}
      zoom={16}
      className="family-map"
      scrollWheelZoom
    >
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
      />

      <AutoFitBounds points={allPoints} />

      {polylinePositions.length > 1 && (
        <Polyline
          positions={polylinePositions}
          pathOptions={{ color: "#4DD0E1", weight: 4, opacity: 0.85 }}
        />
      )}

      {stayPoints.map((stayPoint) => (
        <Marker
          key={stayPoint.stay_point_id}
          position={[stayPoint.latitude, stayPoint.longitude]}
          icon={STAY_POINT_ICON}
        >
          <Popup>
            <div className="stay-point-popup">
              <strong>{stayPoint.is_ongoing ? "停留中" : "停留點"}</strong>
              <div>{stayPoint.address || "地址擷取中…"}</div>
              <div>抵達時間：{formatTime(stayPoint.arrived_at)}</div>
              <div>離開時間：{stayPoint.is_ongoing ? "尚未離開" : formatTime(stayPoint.left_at)}</div>
              <div>停留時長：{formatDuration(stayPoint.duration_seconds)}</div>
            </div>
          </Popup>
        </Marker>
      ))}

      {currentPosition && (
        <Marker
          position={[currentPosition.latitude, currentPosition.longitude]}
          icon={CURRENT_POSITION_ICON}
        >
          <Popup>目前位置</Popup>
        </Marker>
      )}
    </MapContainer>
  );
}
