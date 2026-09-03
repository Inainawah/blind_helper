// 最上層建置檔案，可在此加入適用於所有子專案/模組的通用設定選項。
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}