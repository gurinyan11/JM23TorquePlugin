# JM23 開発・解析記録
第一期開発記録 — 2026年10月8日時点

対象車両：**MAZDA AZ-OFFROAD JM23W 7型**（OEM元：SUZUKI Jimny JB23W）

## 開発の出発点
SZ Viewerで取得可能な車両固有ECU情報を解析し、Torque Pro上で表示することを目標としました。通信、応答解釈、Androidプラグイン、独自PID公開、実車検証を繰り返して現在の構成に至りました。

## 使用ツールと役割
- **SZ Viewer**：ECU応答と表示値の照合・解析の参照。
- **DeAPK**：APK内部構造・実装の調査。
- **Serial Bluetooth系ツール**：ELM327直接通信や通信経路の切り分け。
- **Torque Pro**：ECU通信サービスおよび独自PIDのGUI表示。
- **AndroidIDE / Lenovo TB320FC**：Androidアプリ編集、ビルド、実機側解析。
- **Termux / GitHub / Codex**：後期に導入試験したソース管理と開発支援。
- **MacroDroid**：S8MS上での起動・復帰補助。

## 主な技術的到達点
ECUヘッダー`7E0`、コマンド`2100`による応答取得と解析を実装し、`ITorqueService`の`setPIDInformation()`を介してRPM等の値をTorque Proダッシュボードへ表示できました。項目の拡張はJM23側の解析・公開処理を増やす方式です。

## AndroidIDEとBinder
TB320FCはARM64ですが、当時導入されたSDKの`aidl`実行ファイルはx86_64用で実行できませんでした。この問題を避けるため、`app/src/main/java/org/prowl/torque/remote/ITorqueService.java`に必要なBinderクライアントをJavaで同梱しています。

JDK17を指定したビルド手順：
```sh
export JAVA_HOME=/opt/java/jdk-17.0.20.1+1
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew assembleDebug
```
出力：`app/build/outputs/apk/debug/JM23TorquePlugin-debug.apk`。

## ATOTO S8MSでの試験
S8MSには**Torque Lite**がプリインストールされていました。当時の環境ではJM23に必要な独自PID登録・利用機能を使えず、Torque Liteをアンインストールして手持ちのTorque Pro APKを導入したところ、JM23の独自PID公開・表示が動作しました。Torque Liteの製品仕様全体を検証したものではありません。

短時間スリープ後に自動復帰した実績がある一方、長時間スリープ後の復旧は不安定です。MacroDroidによる補助動作とJM23単独の復帰は区別します。

## ソース管理
GitHub：`gurinyan11/JM23TorquePlugin`。第一期後半にGitHub・Codexの導入試験を実施しました。現行開発環境への全面移行が完了したことを意味しません。

## 記録の限界
本資料は完全な時系列実験日誌ではありません。個々のコマンドや試験順序など、裏付けのない細部は断定しません。
