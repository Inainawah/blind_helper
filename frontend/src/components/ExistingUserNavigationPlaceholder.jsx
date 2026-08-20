/**
 * 這個檔案本身不是「既有系統」的一部分。
 *
 * 目前 repo 中沒有找到既有的網頁版導航 UI（實際的視障導航體驗在
 * Android App，見 app/src/main/java/.../ui/main/MainScreen.kt），
 * 所以這裡先放一個誠實的佔位畫面，並保留掛載點。
 *
 * 若未來要接上真正的既有網頁 UI，只要在 App.jsx 把這個元件換成
 * 原本的頁面元件即可 —— ModeSwitcher／FamilyDashboard 都不需要跟著改動。
 */
export default function ExistingUserNavigationPlaceholder() {
  return (
    <div className="placeholder-panel">
      <h2>使用者導航模式</h2>
      <p>
        此區塊為既有導航系統的掛載點（目前專案中的視障導航體驗主要在
        Android App）。家屬模式切換器不會影響、也不會渲染任何既有元件的內容，
        僅單純顯示或隱藏這個區塊。
      </p>
    </div>
  );
}
