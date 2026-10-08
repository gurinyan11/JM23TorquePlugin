# JM23 技術構成資料
第一期開発記録 — 2026年10月8日時点

対象車両：**MAZDA AZ-OFFROAD JM23W 7型**（OEM元：SUZUKI Jimny JB23W）

## 目的と構成
JM23 Torque Bridgeは、SZ Viewer等の解析を手掛かりにJM23WのECUデータを解読し、Torque Proの独自PIDとして公開してダッシュボードで可視化するAndroidアプリです。Trip・燃料管理はこの基盤を利用した応用機能です。

```text
JM23W ECU → OBD2 → ELM327 → Torque Pro通信サービス
                                 ↓ Binder IPC
                       JM23 BridgeService
                    応答解析・変換・PID公開
                                 ↓
                      Torque Proダッシュボード
```

## 通信と解析
現行実装ではTorque Proの`ITorqueService`に接続し、`sendCommandGetResponse(null, "2100")`で応答を取得します。ECUヘッダーは`7E0`、応答識別は`6100`です。ISO-TP応答の再構成と項目抽出はJM23側で処理します。

確認済みの例：
- RPM：`word[35] × 0.25 rpm`
- 噴射時間：`word[23] × 0.001 ms`

抽出結果は`setPIDInformation()`でTorque Proへ公開します。抽出処理が実装されている項目すべてが実車検証済みという意味ではありません。

## サービス構造
中心実装：`app/src/main/java/jp/jb23/torqueplugin/BridgeService.java`。
`bindTorque()`がサービス接続、`pollOnce()`が取得・解析、`reconnectTorque()`が再接続を担当します。コード上は500ミリ秒の固定遅延で処理をスケジュールしますが、ECU実測更新周期を保証するものではありません。

## データ保存
Android `SharedPreferences`を使用します。
- `jm23_rx_test`：ECU受信情報
- `jm23_trip`：Trip積算
- `jm23_fuel`：燃料管理
- `debug`：デバッグログ

## 拡張の基本経路
SZ Viewer等で項目調査 → ECU応答位置と変換式の特定 → JM23の抽出処理追加 → 独自PID公開 → JM23W実車検証。

## 制約
S8MSの長時間スリープ後の復帰は未解決です。通信断の期間の走行距離・噴射積算は復元できません。車両固有PIDの実装有無と実車検証済みの範囲を区別します。
