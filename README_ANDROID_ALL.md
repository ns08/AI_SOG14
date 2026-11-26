# Android 全ファイル統合パッケージ（MorseVoiceAISOG14 v3）

本 ZIP には、これまでの Android 向け全ファイル（Gradle 設定・GitHub Actions ビルドワークフロー・マニフェスト・リソース・Kotlin ソース）をまとめて収録しています。

## 同梱ファイル一覧
- `.github/workflows/android-build.yml` — GitHub Actions での APK 自動ビルドワークフロー
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`, `app/proguard-rules.pro` — Gradle ビルド構成
- `app/src/main/AndroidManifest.xml` — 各種権限・サービス・レシーバー定義
- `app/src/main/res/xml/accessibility_service_config.xml`, `app/src/main/res/values/strings.xml` — リソース・ユーザー補助設定
- `app/src/main/java/com/soni08/morseai/service/AssistantForegroundService.kt` — 常時音声認識・WakeLockスリープ維持・着信元番号＆連絡先読み上げ＆AI自動起動・複数候補下4桁確認・LINE通話・Teams返信・通知履歴読み上げ・Termux自動連携・Webリレー連携
- `app/src/main/java/com/soni08/morseai/service/AppNotificationListenerService.kt` — LINE・Teams・着信通知取得およびトリガー以降の通知蓄積
- `app/src/main/java/com/soni08/morseai/service/DeviceControlAccessibilityService.kt` — 画面タップ/スワイプ/文字入力・着信応答・LINE通話・Teams返信
- `app/src/main/java/com/soni08/morseai/receiver/IncomingCallReceiver.kt`, `BootCompletedReceiver.kt` — 着信検知・端末再起動時自動復帰
- `app/src/main/java/com/soni08/morseai/contacts/ContactPhoneLookup.kt` — 着信番号逆引き・連絡先複数候補検索
- `app/src/main/java/com/soni08/morseai/media/LocalMusicPlayer.kt` — 本体音楽再生
- `app/src/main/java/com/soni08/morseai/shopping/AmazonOrderHelper.kt` — Amazon 検索・注文画面起動
- `app/src/main/java/com/soni08/morseai/ai/LocalAiClient.kt`, `text/HiraganaNormalizer.kt`, `ui/MainActivity.kt` — ローカルAIクライアント・ひらがな正規化・起動画面
