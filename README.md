# CSV連絡先一括登録 API

![ビルド・テスト](https://github.com/did9984/csv-contact-importer/actions/workflows/test.yml/badge.svg)

CSV一括登録APIを開発し、事前検証・エラー報告・全件成功または全件失敗となるトランザクション処理を実装する。

Java 17 · Spring Boot 3.5.16 · Spring JDBC · H2 · Maven Wrapper · Apache Commons CSV

## 実行方法

Java 17以上が必要です。MavenはWrapperが自動でダウンロードします。別途DBをインストールする必要はありません。

```powershell
# Windows PowerShell：リポジトリのルートで実行
.\mvnw.cmd verify
.\mvnw.cmd spring-boot:run
```

```bash
# macOS / Linux
chmod +x mvnw
./mvnw verify
./mvnw spring-boot:run
```

サーバーURL：http://localhost:8083。H2のインメモリDBを使用するため、アプリを終了するとデータは初期化されます。3つのプロジェクトはそれぞれ異なるポートを使用します。

## 主な機能

- UTF-8のCSVをアップロード。BOMや引用符で囲まれたカンマを含むフィールドに対応
- dryRun=trueが既定値：検証結果のみを返し、DBには保存しない
- 氏名・部署の文字数、メールアドレスの形式、ファイル内とDB内の重複を検証
- メールアドレスは小文字に正規化して比較・保存
- エラーが1件でもある場合は422と行ごとのメッセージを返し、保存件数は0件
- すべての検証に成功した場合のみ、1つのトランザクションで保存
- ファイルサイズは最大1 MB、データ行数は最大1,000件

## API

| メソッド | パス | 説明 |
|---|---|---|
| POST | /api/imports | multipart/form-data。file必須、dryRunの既定値はtrue |
| GET | /api/contacts | 登録済みの連絡先一覧を取得 |

入力・CSV形式のエラーは400、データ競合は409、CSVの行ごとの検証エラーは422、アップロードサイズの超過は413を返します。

## リクエスト例

以下のcurlコマンドはBash / Git Bash用です。Windows PowerShellでは[examples/demo.ps1](examples/demo.ps1)を実行するか、[examples/requests.http](examples/requests.http)を利用してください。

```bash
curl -F "file=@examples/contacts.csv" http://localhost:8083/api/imports
# 検証成功後に実際に登録
curl -F "file=@examples/contacts.csv" "http://localhost:8083/api/imports?dryRun=false"
# エラー報告の例
curl -F "file=@examples/invalid.csv" "http://localhost:8083/api/imports?dryRun=false"
```

## 設計とテスト

Controller → Service → JdbcTemplate → H2の構成です。サービス層のトランザクション境界でDBの読み書きを処理します。

検証プレビュー、実際の登録、不正な行がある場合の保存0件、大文字・小文字を区別しない重複検出、CSV構文・UTF-8のエラー、BOM、行数上限を検証します。共通のメールアドレスを含む2つのCSVを同時登録し、失敗したファイルの一部の行もDBに残らないことを確認します。

自動統合テストを**8件**用意しています。GitHub ActionsでpushおよびPRごとにJava 17でビルドとテストを実行します。

ヘッダーはname,email,departmentの順で完全に一致する必要があります。エラーのrowは、ヘッダーを1として数えた論理レコード番号です。引用符で囲まれたフィールドに改行がある場合、ファイルの物理的な行番号とは異なることがあります。メールアドレスの検証は一般的なASCII形式を対象とする簡易検証です。非同期の大容量バッチ処理は今後の学習課題です。

このリポジトリは架空のデータを使用する小規模な学習用プロジェクトです。ログイン・権限管理や本番環境へのデプロイ設定は含みません。

## 半期評価の目標として活用

[目標・検証記録のテンプレート](docs/evaluation.md)に、目標文と完了基準をまとめています。実際に実行し、説明できる内容に基づいて、ご自身の取り組みを記録してください。

Spring BootのJava・ビルドツール要件：[公式ドキュメント](https://docs.spring.io/spring-boot/3.5/system-requirements.html)。
