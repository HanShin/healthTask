# health-web 클라우드 동기화

설정의 **health-web 클라우드 연결**에서 웹이 발급한 32자리 일회용 코드를 입력합니다. 대상 프로젝트는 `mwwrnhhbippprmidgaus`입니다. 별도의 Supabase API 키는 입력하지 않습니다. 웹에서 로그인한 계정에 기기가 연결됩니다. 연결 코드 유효 시간과 계정 제한은 서버가 검사합니다.

- `device-pair` claim은 `{action:"claim", code, deviceName}`을 보내고 `{deviceId, token}`을 받습니다. 이 공개 함수 호출에는 apikey가 필요 없으며, 이후 요청은 기기 Bearer 토큰으로 인증합니다. 업로드 토큰은 Android Keystore의 AES-GCM 키로 암호화해 저장합니다.
- `health-ingest`는 Bearer 기기 토큰과 `{version:1,batchId,records}`를 받습니다. 사용자 ID는 클라이언트에서 보내지 않습니다.
- 기록의 `source`, `externalId`, `kind`, `measuredAt`, `updatedAt`, `timezone`, `data`는 health-web 공통 계약을 따릅니다. 건강 데이터의 원본 출처는 `data.record.source`, `sourcePackage`에도 보존합니다.
- AnalysisExport의 러닝 ID, 체성분 ID와 `inbody:<CSV 기록 ID>`가 웹 JSON 가져오기와 동일합니다. 전체 원본 GPS·랩·센서·알 수 없는 CSV 열을 보존합니다. 큰 데이터는 SHA-256으로 식별한 detail_part 기록을 먼저 전송한 후 요약을 게시합니다. `detailContentHash`와 `detailPartCount`로 원본 JSON을 복원할 수 있습니다.
- 서버가 승인한 배치마다 체크포인트를 기록합니다. 배치 ID는 payload에서 결정하며 전송 전 outbox에 저장합니다. 응답 유실·앱 중단 후 정확히 같은 배치 ID와 payload를 재전송합니다. 재시도는 동일한 외부 ID를 upsert합니다. HTTP 409 서버 lease 경합과 429 호출 제한은 재시도합니다. 이 설치가 이전에 전송한 ID 중 완전한 로컬 스냅샷에서 없어진 ID에만 명시적 삭제 표식을 전송합니다. 읽기 실패 시 삭제를 계산하지 않습니다. 앱을 새로 설치하거나 다시 연결하면 현재 기록을 전체 전송하며 과거 원격 기록은 임의로 삭제하지 않습니다.
- 앱 실행, 기록/체성분/연결/센서 DB 변경, Health Connect 갱신 이후 작업을 예약합니다. 연결된 네트워크에서 WorkManager가 재시도하며 6시간 간격 보충 전송을 합니다. Android 정책에 따라 실행이 지연될 수 있습니다. 백그라운드에서는 Health Connect 자체를 읽지 않고 기존 로컬 캐시를 전송합니다.
- 연결 해제는 `device-pair`의 `revoke-self`로 서버 권한을 폐기한 후 로컬 자격 증명을 삭제합니다. 네트워크 실패 시 자격 증명을 유지하므로 다시 해제할 수 있습니다. 웹에서 이미 권한이 폐기된 경우 로컬 연결만 정리합니다. 기존 원격 기록은 보존합니다.

## 삼성 헬스

기존 러닝·체중·체지방 권한과 별도로 수면·걸음·심박 읽기 권한을 요청합니다. 추가 권한을 거부해도 기존 러닝·체성분 연동은 유지합니다. 사용자가 허용한 삼성 헬스 출처만 읽어 `sync_state`에 캐시합니다. 첫 읽기와 권한 변경 시 전체 읽기, 이후 Health Connect 변경 토큰으로 수정·삭제를 반영합니다. 권한 철회는 데이터 삭제로 취급하지 않습니다. 철회된 선택 항목은 캐시를 보존하되 신규 전송을 중단하고 설정에 권한 부족을 표시합니다. 변경 토큰 만료 후 전체 읽기가 성공하면 권한이 유지된 유형의 EPOCH..현재 범위 캐시만 재조정하여 삭제된 항목을 반영합니다. 원본 앱이 해당 기록을 Health Connect에 공급하지 않았거나 읽기 권한/원본 보관 기간이 바뀌면 원본 앱의 전체 계정 이력과 일치하지 않을 수 있습니다. 수면 `value`는 수면 세션의 전체 시간(초)이며 깨어 있는 시간을 뺀 추정 수면 시간이 아닙니다. 수면 단계와 원본 심박 샘플도 보존합니다. 기존 백업 가져오기는 기록을 upsert하며 비어 있는 백업이 기존 기록을 지우지 않습니다. 클라우드 fullSnapshot 재조정은 Health Connect 선택 항목 캐시에만 적용하고 앱 로컬 기록에는 적용하지 않습니다. 기본 백업의 복원 형식은 변경하지 않았으므로 새 Health Connect 캐시는 기기 복원 후 원본 앱에서 다시 동기화해야 합니다.

## 검증

2026-10-20에 대상 프로젝트를 `mwwrnhhbippprmidgaus`로 변경했습니다. 운영 웹은 https://health-web-rose.vercel.app 이며, 실제 이메일 OTP 로그인·빈 데이터 조회·기기 연결 코드 발급까지 확인했습니다. 이전 프로젝트에서 발급한 연결 코드와 토큰은 사용할 수 없습니다. 앱 업데이트 후 운영 웹에서 새 코드를 발급해 연결하세요.

`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`를 실행합니다. `CloudRecordsTest`는 JSON 가져오기와 같은 ID, 누락 값, 중복 제외 정보, CSV 원본 보존, 대용량 Unicode 데이터 복원과 배치 제한과 재시도 배치 ID의 안정성을 검증합니다.

GitHub의 [Android verification](https://github.com/HanShin/healthTask/actions/workflows/android.yml)은 `main` 변경과 PR, 수동 실행에서 위 검증과 `:app:assembleRelease`를 원격으로 실행합니다. 보고서와 성공한 debug APK, 서명 전 release APK는 실행 결과의 Artifacts에서 7일간 받을 수 있습니다. debug APK는 임시 debug 키로 서명된 테스트용이며 기존 앱 업데이트용이 아닙니다. 서명 전 release APK를 기존 키가 있는 PC에서 서명한 후 업데이트합니다. CI는 Supabase 비밀값이나 기존 릴리스 서명 키를 사용하지 않습니다.

2026-10-20에 이 PC에서 준비하던 JDK·Android SDK·Gradle 도구와 실행한 ADB 서버를 정리했고, 로컬 설치 없이 [GitHub 원격 실행](https://github.com/HanShin/healthTask/actions/runs/39391534589)에서 `3aff8e1`을 검증했습니다. 단위 테스트 69개(CloudRecordsTest 4개 포함)·린트·debug APK 빌드가 모두 통과했습니다. 테스트 실패·오류·건너뜀은 0개이고, 린트는 오류 0개·경고 31개입니다. 이 실행은 기존 릴리스 서명 키를 사용하지 않았습니다.

사용자가 이 PC에서 휴대폰을 연결했던 이력을 알려준 뒤 다시 확인한 결과, 기존 `~/.android/healthtask-release.jks`와 `~/.gradle/healthtask-signing.properties`가 남아 있었고 이 PC의 기존 release APK와 인증서 지문이 일치했습니다. Android Studio 번들 Java, SDK 서명 도구와 ADB도 남아 있습니다. 따라서 새 키 생성이나 개발 도구 재설치 없이 원격에서 만든 release APK를 이 PC에서 기존 키로 서명할 수 있습니다. 이전의 "이 PC에는 기존 서명 키가 없다"는 안내는 잘못이었습니다. 실제 휴대폰에 설치된 앱과의 서명 비교는 기기 연결 후 진행합니다.

실기기 최초 코드 연결과 실제 업로드, 추가 Health Connect 읽기 권한 허용 및 수면·걸음·심박의 공급·전송을 확인했습니다. 부분 권한 거부/철회, 네트워크 중단 후 재시도, 로컬 기록 삭제, 웹에서 기기 폐기 후 업로드 거부는 추가 회귀 검증 항목입니다.

최종 [릴리스 포함 원격 검증](https://github.com/HanShin/healthTask/actions/runs/39392953709)은 `f057c2a`에서 단위 테스트 69개(실패·오류·건너뜀 0개), 린트 오류 0개·경고 31개, debug/release APK 빌드가 모두 통과했습니다. 서명 전 release APK를 이 PC의 기존 키로 서명하고 디버그 비활성·16KB ZIP 정렬·새 Supabase 프로젝트 주소를 검증했습니다. USB로 연결된 SM-F766N의 설치 앱도 같은 인증서임을 확인한 뒤 삭제 없이 `adb install -r`로 업데이트했습니다. 설치된 APK 해시는 서명본과 일치하며 최초 설치일은 유지됐습니다. APK는 `app/build/ci-release-f057c2a/healthtask-cloud-sync-release.apk`에 있으며 Git에는 포함하지 않습니다.

사용자가 운영 웹의 일회용 코드를 앱에 입력한 뒤 기기 등록과 오늘러닝 배치 업로드가 완료됐습니다. 이어 수면·걸음·심박 읽기를 직접 허용하고 삼성 헬스 동기화를 실행했으며 세 항목의 실제 저장을 확인했습니다. 웹에서 Oura·오늘러닝/인바디·삼성 헬스가 모두 연결됨으로 표시됩니다. 서버에서 분할 원본을 순서대로 재구성해 SHA-256을 계산한 결과 게시된 모든 원본 해시가 일치했습니다. 이 검증에는 권한 상태·집계 건수·완료 상태·해시 일치 여부만 사용했으며 실제 건강 수치나 원본 내용은 출력하지 않았습니다.

실기기 업데이트·연결 순서:

1. GitHub 원격 검증을 실행해 서명 전 release APK를 받거나, 기존 개발 PC에서 이 저장소의 `main` 최신 변경 사항을 받고 위 Gradle 검증 명령을 실행합니다.
2. 휴대폰의 기존 오늘러닝 앱을 삭제하지 말고 앱 내 백업을 먼저 보관합니다. 기존 설치와 같은 서명 키로 업데이트 APK를 만들어야 앱 데이터를 유지할 수 있습니다. 서명 키와 비밀번호는 저장소나 채팅으로 공유하지 않습니다.
3. 서명과 버전을 확인한 뒤 기존 앱 위에 업데이트합니다. 서명이 다르면 설치를 중단하고 기존 빌드 PC의 서명 설정을 확인합니다.
4. health-web에 OTP로 로그인하고 **연결 · 설정 → 기기 연결 코드 발급**을 누릅니다. 10분 안에 오늘러닝의 **설정 → health-web 클라우드 연결**에 코드를 입력합니다. 웹 주소와 관계없이 앱은 설정된 Supabase 프로젝트로 직접 업로드합니다.
5. 웹에 기기와 동기화 이력이 나타나는지 확인하고 러닝·인바디 원본 기록 수와 최근 측정값을 대조합니다. 삼성 헬스의 추가 읽기 권한은 휴대폰에서 허용합니다.

WorkManager는 공식 안정 릴리스 2.12.0으로 고정했습니다: https://developer.android.com/jetpack/androidx/releases/work
