# 오늘러닝

러닝 기록과 인바디·체성분 변화를 관리하는 개인용 네이티브 앱입니다. Android 14(API 34) 이상 휴대폰과 Wear OS(API 30) 이상 워치를 지원합니다. 계정이나 자체 서버는 사용하지 않습니다.

현재 소스는 휴대폰·워치 모두 **2.0.0 (`versionCode 10`)**입니다. 문서 기준일은 2026-10-20이며, 실제 두 기기의 업데이트 설치·앱 실행을 마지막으로 확인한 날짜는 2026-09-25입니다. 설치 확인과 별개로 야외 장시간 러닝·실제 센서 정확도·오프라인 재연결 전달의 종합 실기기 검증은 남아 있습니다.

## 문서 안내

- [인바디 CSV와 변화 그래프](docs/inbody-csv.md): 지원 항목, 같은 날 여러 측정, 원본·시간대 보존
- [워치 상세 센서 기록](docs/running-sensors.md): 수집 범위, 저장·전송, 미지원 센서와 해석 한계
- [상세 분석 JSON](docs/analysis-export.md): 복원용 백업과 분석 자료의 차이, 단위·개인정보
- [기술 스택](TECH_STACK_LOG.md), [개발 인수인계](SESSION_HANDOFF.md), [날짜별 검증 이력](WORK_PROGRESS.md)

## 2.0 구성

- `러닝 / 러닝 기록 / 인바디 / 설정` 네 개 탭
- 차콜·라임과 밝은 배경으로 통일한 휴대폰 UI: 러닝 대시보드, 누적 요약·펼쳐보는 기록 카드, 날짜별 인바디 목록·추이 그래프, 달력 측정일 선택
- 첫 실행부터 질문 없이 러닝 화면으로 진입. 주간 목표 설정이나 달성률 없이 원하는 때에 자유롭게 기록
- 휴대폰 GPS 러닝 시작·일시정지·재개·완료, 거리·현재/평균 페이스·배경 지도·이동 경로
- 화면이 꺼져도 기록하는 전경 서비스, 1km 자동 랩, 자동 일시정지·재개와 한국어 음성 안내
- 누적 러닝 횟수·거리·시간, 기록별 경로·랩·심박·칼로리 확인
- 워치 단독 자유 러닝·30분·5km 러닝, GPS·심박·칼로리와 완료 기록의 재연결 자동 전송
- 워치 러닝 중 앱 화면 유지와 저전력 표시, 시계 화면에서 진행 중 러닝으로 돌아오는 바로가기
- 인바디 직접 입력과 캡처 인식: 체중, 체지방률, 체지방량, 골격근량, 내장지방 레벨, 인바디 점수
- 인바디 CSV 가져오기: 43개 상세 지표와 측정 시각·장비·원본 전체 열 보존, 저장 전 확인·중복 방지·누락 항목 보충. [사용 안내](docs/inbody-csv.md)
- 인바디 변화/측정 기록 탭: 항목별 세로 그래프 카드, 점별 수치·날짜/시간 말풍선, 최근 10회/전체 가로 스크롤, 표시 항목 설정 저장. 같은 날 여러 측정도 각각 표시하고 원본 수정/삭제 기능은 유지. 외부 연동 값은 원본 앱에서 관리
- 삼성 헬스·Nike Run Club의 **러닝만** Health Connect에서 읽고 로컬 러닝만 쓰기. 삼성 체중·체지방 연동 유지
- Room 로컬 저장, 진행 중 러닝 복원, 원본 백업과 상세 분석 데이터를 함께 담는 JSON 내보내기/가져오기

근력 루틴·세트·중량·휴식 타이머·타바타·근력 계획·운동 가이드는 휴대폰과 워치에서 제거했습니다. 기존 사용자의 DB와 백업을 유지하기 위해 예전 테이블 및 전송 필드는 호환용으로 남겼습니다. 이전 근력 기록은 삭제하지 않고 보관하지만 새 화면·통계·Health Connect 업로드에서 제외합니다. 기존 `legacy-v1`, schemaVersion 2/3/4 백업도 계속 읽으며 내보내기는 상세 센서·인바디 CSV 원본을 포함한 schemaVersion 5입니다.

외부 앱의 캐시 기록은 내보내기의 분석용 영역에 포함하지만 복원용 영역에는 넣지 않으며, 복원 후 Health Connect에서 다시 읽습니다. 권한이나 연결이 없어도 로컬 러닝과 인바디 입력은 사용할 수 있습니다.

휴대폰 러닝은 위치 권한을 허용한 뒤 `러닝` 탭에서 시작합니다. Android 위치 전경 서비스를 사용하므로 화면을 끄거나 다른 앱을 열어도 상단 알림에서 시간이 계속 기록됩니다. GPS 오차가 큰 점, 정지 중 흔들림과 비현실적인 순간 이동은 거리에서 제외하며 이동 좌표와 1km 랩은 계정이나 별도 서버로 보내지 않고 기기의 운동 기록에만 저장합니다. 자동 일시정지와 1km 음성 안내는 `설정 > 러닝 안내`에서 각각 끌 수 있습니다.

러닝 화면과 `러닝 기록` 탭에서 펼친 러닝 기록에는 OpenStreetMap 배경 위에 경로가 표시됩니다. 기존에 저장했거나 워치에서 전송받은 좌표도 그대로 사용합니다. `크게 보기`에서 확대·축소·이동하고 `전체 경로`로 돌아갈 수 있습니다. 초록색은 시작, 빨간색은 도착 지점입니다. 오프라인이나 지도 로딩 실패 시 저장된 궤적을 표시하며 GPS 기록 자체는 인터넷 없이 동작합니다.

러닝 화면의 `내 주변 샘플 지도`는 위치 권한으로 현재 위치를 한 번 확인하고 주변 약 0.8km의 가상 한 바퀴를 표시합니다. 실제 도로를 따라 만든 추천 코스가 아닙니다. 샘플은 화면에만 존재하며 운동 기록·통계·Health Connect·워치에는 저장/전송하지 않습니다. 닫거나 20초가 지나면 남은 위치 요청을 취소하며 위치를 구하지 못한 경우 다른 장소로 대체하지 않고 재시도를 안내합니다.

지도에는 API 키나 결제 계정이 필요하지 않습니다. 지도를 볼 때만 `tile.openstreetmap.org`에 표시 영역의 지도 타일을 요청하므로 해당 서버에 IP 주소와 조회한 대략적인 영역이 전달됩니다. 운동 경로 전체나 심박 기록은 지도 서버로 전송하지 않습니다. 공개 타일 정책에 맞춰 앱을 식별하는 User-Agent, 출처 표시, HTTP 캐싱을 적용하고 사전 다운로드·오프라인 지도 저장 기능은 제공하지 않습니다. 참고: [OSM 타일 정책](https://operations.osmfoundation.org/policies/tiles/), [MapLibre Android](https://maplibre.org/maplibre-native/android/examples/getting-started/).

## 상세 데이터 내보내기

내보내기는 `설정 → JSON 백업 → 상세 데이터 내보내기 (JSON)`에서 사용합니다. 복원용 schemaVersion 5와 읽기 전용 analysis schemaVersion 3를 포함합니다. 전체 저장 기간의 원본 수치·출처·메모·시간, GPS 좌표·랩, 심박·칼로리, 인바디 전 항목·CSV 원본 열, 중복 제외 일/주/월 요약을 담습니다. 새 워치 러닝은 시간별 심박·속도·케이던스·걸음 수·고도·상승/하강·GPS 정확도·센서 가용성·정지/재개 이벤트도 기기 지원 범위에서 보존합니다. 0과 미측정(null)을 구분하며 과거에 저장하지 않은 정보는 만들지 않습니다. 파일에는 정확한 위치와 건강 정보가 비암호화 상태로 포함됩니다. [분석용 내보내기 안내](docs/analysis-export.md), [워치 상세 센서 기록](docs/running-sensors.md)을 참고하세요.

## 개발 환경

- Android Studio Quail 3 Patch 1 이상
- Android Studio 번들 JDK
- 빌드용 Android SDK Platform 36 (`compileSdk 36`, `targetSdk 36`; 실행 기기의 최소 API는 휴대폰 34, 워치 30)
- Gradle 9.4.1 / Android Gradle Plugin 9.2.0

Android Studio에서 저장소 루트를 열어 Gradle Sync 후 실행합니다. CLI에서는 번들 JDK를 지정합니다.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:testDebugUnitTest :wear:testDebugUnitTest :shared:testDebugUnitTest
./gradlew :app:lintDebug :wear:lintDebug
./gradlew :app:assembleDebug :wear:assembleDebug
```

디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`, `wear/build/outputs/apk/debug/wear-debug.apk`에 생성됩니다. 개발 환경별 SDK 경로인 `local.properties`, 서명 설정·키스토어 및 빌드 결과물은 Git에 넣지 않습니다.

계측 테스트는 테스트 전용 휴대폰/워치 에뮬레이터에서 각각 실행합니다. 아래 `PHONE_EMULATOR_SERIAL`, `WATCH_EMULATOR_SERIAL`은 `adb devices`로 확인한 실제 에뮬레이터 식별자로 바꿉니다.

```bash
ANDROID_SERIAL=PHONE_EMULATOR_SERIAL ./gradlew :app:connectedDebugAndroidTest
ANDROID_SERIAL=WATCH_EMULATOR_SERIAL ./gradlew :wear:connectedDebugAndroidTest
```

계측 테스트에는 저장·복원·삭제·GPS·화면 전환 검증이 포함되므로 개인 기록이 있는 실제 기기에 일괄 실행하지 마세요. 빌드/단위 테스트와 기기에서 실행하는 계측 테스트는 서로 다른 검증입니다.

## 이전 근력운동 자료

APK에 포함되던 26개 MP4와 SVG 가이드는 [legacy/strength-assets](legacy/strength-assets/README.md)에 보관했습니다. Media3 플레이어와 운동 가이드 화면은 앱에서 제거했습니다. Blender 제작 원본과 검수 문서는 `design/motion`, `tools/blender`, `docs/motions`에 남아 있으며 현재 앱의 기능이 아닙니다.

## Wear OS 워치 앱

휴대폰과 워치 앱은 동일한 패키지(`com.hanshin.healthtask`)와 서명 키를 사용합니다. 휴대폰은 자유 러닝 프리셋을 Wear OS Data Layer로 보내며, 워치는 연결이 끊겨도 러닝을 기록한 뒤 경로와 완료 데이터를 전송 대기열에 보관합니다. 휴대폰이 이를 Room에 저장하고 다음 포그라운드 동기화 때 Health Connect로 쓰므로 워치가 Health Connect에 별도로 중복 전송하지 않습니다.

```bash
./gradlew :wear:assembleDebug
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/debug/wear-debug.apk
```

워치에서 활동 인식, 심박, 알림 및 정밀 위치 권한을 허용합니다. 홈에서 자유 러닝·30분·5km 중 하나를 선택해 시작합니다. 워치 내장 GPS로 거리와 페이스를 기록하므로 휴대폰을 가지고 달릴 필요가 없습니다. 워치의 `개발자 옵션 → 무선 디버깅`에서 페어링 정보와 연결 정보를 확인합니다. 페어링 포트와 실제 연결 포트는 다를 수 있으며 Wi-Fi 재연결 시 바뀔 수 있습니다.

```bash
adb pair WATCH_IP:PAIRING_PORT
adb connect WATCH_IP:DEBUG_PORT
adb devices -l
```

첫 명령이 요청하면 워치에 표시된 일회용 페어링 코드를 입력합니다. 설치 시 `-s WATCH_SERIAL`에는 연결 후 표시되는 워치 식별자를 사용합니다. 주소·코드·실제 운동 데이터는 저장소에 기록하지 않습니다.

러닝 중에는 Ongoing Activity로 앱 화면을 유지합니다(Wear OS 5 이상). 일시정지해도 유지하며 러닝을 완료하면 해제됩니다. 직접 홈 버튼을 눌러 나간 경우에는 시계 화면의 진행 중 러닝 아이콘을 눌러 돌아옵니다. 알림 권한을 허용해야 이 알림과 바로가기를 사용할 수 있습니다.

손목을 내리면 검정 배경의 저전력 화면으로 전환하고, 최근 거리·분 단위 누적 시간·갱신 시각을 표시합니다. 저전력 상태에서는 시스템의 분 단위 갱신에 맞추며 초 단위 타이머·실시간 페이스·애니메이션은 표시하지 않습니다. 화면을 깨우면 기존 실시간 화면으로 복귀합니다. 손목을 내린 상태에서도 화면이 보이려면 워치의 Always On Display가 켜져 있어야 하며 절전·극장 모드 등 시스템 설정을 따릅니다. 앱에서 화면 밝기나 시스템 설정을 강제로 고정하지 않습니다. 참고: [Wear OS 화면 유지와 저전력 모드](https://developer.android.com/training/wearables/always-on), [Ongoing Activity](https://developer.android.com/training/wearables/notifications/ongoing-activity).

## Health Connect 사용 전 준비

1. 삼성 헬스가 Health Connect에 운동·체성분 데이터를 쓸 수 있도록 허용합니다.
2. 오늘러닝의 `설정` 탭에서 `권한 연결`을 누릅니다.
3. 운동 읽기/쓰기, 거리·칼로리·체중·체지방·과거 데이터 읽기를 허용합니다.
4. `새로고침 · 오류 재시도`를 누릅니다.

삼성 헬스·NRC의 실외/트레드밀 러닝만 가져옵니다. 걷기와 근력운동은 제외합니다. 시간 구간이 70% 이상 겹치는 로컬/외부 러닝을 연결해 중복 집계하지 않습니다. 체중·체지방률은 같은 날짜의 삼성 값을 우선합니다. 오늘러닝의 GPS 러닝은 Health Connect나 NRC 연결 없이 독립적으로 기록됩니다.

실제 쓰기 범위는 러닝 운동 세션과 값이 있는 거리입니다. 상세 GPS 경로·심박 시계열·케이던스·인바디 CSV 원본은 앱 내부와 JSON 백업에서 관리하며 Health Connect에 업로드하지 않습니다. 외부 앱이 Health Connect에 제공하지 않았거나 권한이 없는 데이터는 읽을 수 없습니다.

NRC가 기록한 운동은 데이터 출처 패키지 `com.nike.plusgps`로 구분합니다. 운동 세션과 같은 시간 구간의 거리·칼로리를 함께 읽으며, 최근 7일의 NRC 세션을 다시 확인해 늦게 기록된 세부 정보도 갱신합니다. 동일한 Health Connect 레코드 ID는 덮어쓰므로 중복 생성되지 않습니다. Google 계정이나 Google Cloud OAuth 설정은 필요하지 않습니다.

## 개인 릴리스 서명

키스토어와 비밀번호는 저장소 밖에 둡니다. 최초 한 번 다음 작업을 실행하면 기존 파일을 덮어쓰지 않고 `~/.android/healthtask-release.jks`와 권한이 `600`인 `~/.gradle/healthtask-signing.properties`를 만듭니다.

```bash
./gradlew :app:generatePersonalReleaseKey
```

환경 변수 `HEALTHTASK_SIGNING_PROPERTIES`로 다른 설정 파일을 지정할 수도 있습니다. 저장소 루트의 `keystore.properties`도 호환용으로 지원하며 Git에서 제외됩니다. 형식은 다음과 같습니다.

```properties
storeFile=/absolute/path/outside/repository/healthtask-release.jks
storePassword=...
keyAlias=healthtask
keyPassword=...
```

```bash
./gradlew :app:assembleRelease :wear:assembleRelease
adb -s PHONE_SERIAL install -r app/build/outputs/apk/release/app-release.apk
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/release/wear-release.apk
```

동일한 키를 계속 보관해야 Room 데이터를 유지한 채 APK를 업데이트할 수 있습니다. 기존 설치가 있다면 새 키를 만들거나 다른 서명의 debug APK로 덮어쓰지 마세요. 운동을 종료하고 백업한 뒤 `install -r`로 업데이트하며, 서명 오류를 해결하려고 앱을 삭제하거나 데이터를 초기화하지 않습니다. 현재 DB는 Room v7, 복원용 백업은 v5, 분석 영역은 v3입니다. 구버전 앱으로의 다운그레이드는 지원하지 않습니다.

## PWA 보존

전환 직전 웹앱은 Git 태그 `pwa-final-2026-08-21`에 보존되어 있습니다. Android로 가져올 데이터는 기존 PWA 설정에서 JSON을 내보낸 뒤 오늘러닝 설정의 파일 선택기로 불러옵니다.
