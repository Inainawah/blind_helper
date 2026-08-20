/**
 * 模式切換器：使用者導航模式 <-> 家屬模式。
 *
 * 純粹的「哪一個畫面顯示出來」開關，不持有任何導航/家屬資料狀態，
 * 因此可以安全地放在既有系統的最外層，不需要更動既有頁面內部邏輯。
 */
export default function ModeSwitcher({ mode, onChange }) {
  return (
    <nav className="mode-switcher" role="tablist" aria-label="檢視模式切換">
      <button
        type="button"
        role="tab"
        aria-selected={mode === "user"}
        className={mode === "user" ? "mode-switcher__btn is-active" : "mode-switcher__btn"}
        onClick={() => onChange("user")}
      >
        使用者導航模式
      </button>
      <button
        type="button"
        role="tab"
        aria-selected={mode === "family"}
        className={mode === "family" ? "mode-switcher__btn is-active" : "mode-switcher__btn"}
        onClick={() => onChange("family")}
      >
        家屬模式
      </button>
    </nav>
  );
}
