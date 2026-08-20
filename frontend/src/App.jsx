import { useState } from "react";
import ModeSwitcher from "./components/ModeSwitcher.jsx";
import ExistingUserNavigationPlaceholder from "./components/ExistingUserNavigationPlaceholder.jsx";
import FamilyDashboard from "./components/FamilyDashboard.jsx";

function getInitialUserId() {
  const params = new URLSearchParams(window.location.search);
  const fromUrl = Number(params.get("user_id"));
  return Number.isInteger(fromUrl) && fromUrl > 0 ? fromUrl : 1;
}

export default function App() {
  const [mode, setMode] = useState("user");
  const [userId, setUserId] = useState(getInitialUserId);

  return (
    <div className="app-shell">
      <header className="app-header">
        <span className="app-header__title">Blind Helper</span>
        <ModeSwitcher mode={mode} onChange={setMode} />
      </header>

      <main className="app-main">
        {mode === "user" && <ExistingUserNavigationPlaceholder />}

        {mode === "family" && (
          <>
            <div className="family-user-picker">
              <label htmlFor="family-user-id">查看使用者 ID：</label>
              <input
                id="family-user-id"
                type="number"
                min="1"
                value={userId}
                onChange={(event) => setUserId(Number(event.target.value) || 1)}
              />
            </div>
            <FamilyDashboard userId={userId} />
          </>
        )}
      </main>
    </div>
  );
}
