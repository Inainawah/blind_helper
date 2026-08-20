import { useEffect, useState } from "react";
import FamilyMap from "./FamilyMap.jsx";
import { fetchFamilyOverview } from "../api/familyApi.js";

const POLL_INTERVAL_MS = 15000;

function formatLastUpdated(isoString) {
  if (!isoString) return "尚無資料";
  return new Date(isoString).toLocaleString("zh-TW", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });
}

export default function FamilyDashboard({ userId }) {
  const [overview, setOverview] = useState(null);
  const [error, setError] = useState(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const data = await fetchFamilyOverview(userId);
        if (!cancelled) {
          setOverview(data);
          setError(null);
        }
      } catch (err) {
        if (!cancelled) setError(err.message);
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }

    load();
    const timer = setInterval(load, POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [userId]);

  const deviceStatus = overview?.device_status;

  return (
    <div className="family-dashboard">
      <div className="family-dashboard__status-bar">
        <div className={`status-pill ${deviceStatus?.is_online ? "status-pill--online" : "status-pill--offline"}`}>
          <span className="status-pill__dot" />
          {deviceStatus?.is_online ? "連線中" : "離線"}
        </div>

        <div className="status-metric">
          <span className="status-metric__label">電量</span>
          <span className="status-metric__value">
            {deviceStatus?.battery_level != null ? `${deviceStatus.battery_level}%` : "—"}
            {deviceStatus?.is_charging ? " ⚡" : ""}
          </span>
        </div>

        <div className="status-metric">
          <span className="status-metric__label">最後更新</span>
          <span className="status-metric__value">{formatLastUpdated(deviceStatus?.last_seen_at)}</span>
        </div>
      </div>

      {error && <div className="family-dashboard__error">讀取失敗：{error}</div>}
      {isLoading && !overview && <div className="family-dashboard__loading">載入中…</div>}

      {overview && (
        <FamilyMap
          path={overview.path}
          stayPoints={overview.stay_points}
          currentPosition={overview.current_position}
        />
      )}
    </div>
  );
}
