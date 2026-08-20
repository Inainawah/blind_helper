const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:3000";

/**
 * 讀取家屬儀表板資料：裝置狀態、目前位置、今日軌跡、今日停留點。
 * 對應 backend/family.js -> GET /api/family/overview
 */
export async function fetchFamilyOverview(userId) {
  const response = await fetch(
    `${API_BASE_URL}/api/family/overview?user_id=${encodeURIComponent(userId)}`
  );

  const body = await response.json().catch(() => null);

  if (!response.ok || !body?.success) {
    const message = body?.error?.message || `伺服器錯誤 (${response.status})`;
    throw new Error(message);
  }

  return body;
}
