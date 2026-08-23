# 오늘운동

계정과 서버 없이 한 사람이 휴대폰과 Galaxy Watch에서 운동을 기록하는 네이티브 앱입니다. 휴대폰은 Android 14(API 34) 이상, 워치는 Wear OS(API 30) 이상을 지원하며 Galaxy Watch9을 주 대상으로 합니다.

## 구현 범위

- 온보딩과 `오늘 / 루틴 / 기록 / 설정` 하단 탭
- 루틴 CRUD, 순번형 추천, 세트·횟수·중량, 유산소 시간·거리, 최근 완료 세트 이어받기
- 세트 완료 시 휴대폰·워치 자동 휴식 타이머, 종료 알림·진동, 건너뛰기와 30초 연장
- 통합 운동 기록, 주간 목표, 연속 기록, 체성분 기록
- Room 로컬 저장과 진행 중 세션 복원
- `legacy-v1` PWA JSON 가져오기 및 `schemaVersion: 2` 백업
- Health Connect를 통한 삼성 헬스 운동·체중·체지방 읽기
- Health Connect를 통한 Nike Run Club 러닝 날짜·시간·거리·칼로리 읽기
- 완료한 로컬 운동 요약을 Health Connect에 쓰기
- 같은 날짜·호환 운동·70% 이상 시간 중첩 시 로컬/외부 기록 자동 연결
- 오프라인 SVG 운동 가이드와 외부 YouTube 링크
- 스쿼트·플랫 덤벨 프레스·원암 덤벨 로우 정면/측면 사람형 3D 모션 가이드와 자동 반복 재생
- Galaxy Watch용 Wear OS 앱과 오늘 루틴 자동 전송
- 워치의 오프라인 세트·횟수·중량 기록 및 진행 중 세션 복원
- Health Services 기반 심박·평균 심박·칼로리·유산소 거리 측정
- 워치 완료 기록의 재연결 자동 전송과 휴대폰 단일 Health Connect 쓰기

삼성 헬스 캐시는 백업에 넣지 않으며 복원 뒤 Health Connect에서 다시 읽습니다. 권한이 없거나 동기화가 실패해도 로컬 운동 저장은 독립적으로 작동합니다.

## 개발 환경

- Android Studio Quail 3 Patch 1 이상
- Android Studio 번들 JDK
- Android SDK Platform 34 이상(현재 `compileSdk 36`, `targetSdk 36`, `minSdk 34`)
- Gradle 9.4.1 / Android Gradle Plugin 9.2.0
- 3D 원본 수정 시 Blender 4.5 LTS, MPFB 2.0.17, MakeHuman CC0 system assets

Android Studio에서 저장소 루트를 열어 Gradle Sync 후 실행합니다. CLI에서는 번들 JDK를 지정합니다.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test lintDebug assembleDebug
```

디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.

## 3D 운동 모션 가이드

바벨 스쿼트, 플랫 덤벨 프레스와 원암 덤벨 로우 가이드는 APK에 포함된 정면·측면 H.264 영상을 Media3로 무음 자동 반복합니다. 앱에서 운동 이름 옆 가이드 아이콘을 누른 뒤 `정면 / 측면`을 선택하면 됩니다. 네트워크는 필요하지 않습니다.

모션은 실제 하이바 백스쿼트의 [측면 자세](https://www.youtube.com/shorts/KqbKmBSDVS4)와 [정면·사선 튜토리얼](https://www.youtube.com/shorts/lHyQ4Jy0LSA)을 시각 레퍼런스로 삼아 새로 제작했습니다. 영상 자체는 복제하거나 앱에 포함하지 않았습니다. 발 고정, 무릎의 발끝 방향 진행, 골반의 후하방 이동, 중립 척추, 바벨의 발 중앙 수직 경로와 손 그립을 독립 제어합니다.

최종 Blender 원본은 `design/motion/squat_human_sample.blend`, 재생 리소스는 `app/src/main/res/raw/squat_front.mp4`와 `squat_side.mp4`, 생성 코드는 `tools/blender`에 있습니다. 사람형 베이스와 시스템 자산은 CC0인 MakeHuman Community 자산을 사용하며 자세와 애니메이션은 이 프로젝트에서 제작했습니다.

플랫 덤벨 프레스는 승인된 스쿼트 사람 모델을 그대로 사용하고 벤치, 덤벨, 발·팔 IK와 카메라만 운동별로 구성합니다. 원본은 `design/motion/flat_dumbbell_press_human_sample.blend`, 생성 코드는 `tools/blender/generate_human_flat_dumbbell_press.py`, 동작 정의와 검수 기록은 `docs/motions/FLAT_DUMBBELL_PRESS.md`에 있습니다.

원암 덤벨 로우는 왼손·왼무릎·오른발의 세 지점을 고정하고 오른팔만 당기는 단측 모션입니다. 원본은 `design/motion/one_arm_dumbbell_row_human_sample.blend`, 생성 코드는 `tools/blender/generate_human_one_arm_dumbbell_row.py`, 동작 정의는 `docs/motions/ONE_ARM_DUMBBELL_ROW.md`에 있습니다.

```bash
# 6개 자세와 3개 그립 근접 화면을 먼저 생성
blender -b design/motion/squat_human_sample.blend \
  --python tools/blender/generate_human_flat_dumbbell_press.py -- \
  --output-dir app/src/main/res/raw \
  --blend design/motion/flat_dumbbell_press_human_sample.blend \
  --mode preview

# 검수 후 정면·측면 무음 루프 생성
blender -b design/motion/squat_human_sample.blend \
  --python tools/blender/generate_human_flat_dumbbell_press.py -- \
  --output-dir app/src/main/res/raw \
  --blend design/motion/flat_dumbbell_press_human_sample.blend \
  --mode render
```

새 운동 모션은 [운동 모션 품질 기준](docs/MOTION_QUALITY_STANDARD.md)의 레퍼런스, 인체 비율, 장비 접촉, 정면·측면 단계별 검수, 접촉 부위 근접 검수와 루프 기준을 모두 통과해야 합니다. 스쿼트의 손 그립 정면·사선·후면 근접 렌더를 시각적 하한선으로 사용합니다.

운동별 제작 순서와 재사용할 스튜디오·장비 구성은 [모션 제작 계획](docs/MOTION_PRODUCTION_PLAN.md)에 정리합니다. 현재 루틴에서 자주 쓰는 운동부터 하나씩 제작하고, 각 운동이 검수를 통과한 뒤 다음 운동으로 넘어갑니다.

## Galaxy Watch9 앱

휴대폰과 워치 앱은 동일한 패키지(`com.hanshin.healthtask`)와 서명 키를 사용합니다. 휴대폰이 다음 루틴을 Wear OS Data Layer로 보내며, 워치는 연결이 끊겨도 운동을 기록한 뒤 완료 데이터를 전송 대기열에 보관합니다. 휴대폰이 이를 Room에 저장하고 다음 포그라운드 동기화 때 Health Connect로 쓰므로 워치가 Health Connect에 별도로 중복 전송하지 않습니다.

```bash
./gradlew :wear:assembleDebug
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/debug/wear-debug.apk
```

워치에서 활동 인식, 심박, 알림 권한을 허용합니다. Galaxy Wearable의 무선 디버깅 또는 Android Studio의 Pair Devices Using Wi-Fi로 Watch9을 연결할 수 있습니다.

## Health Connect 사용 전 준비

1. 삼성 헬스와 NRC가 Health Connect에 운동 데이터를 쓸 수 있도록 허용합니다.
2. 오늘운동의 `설정` 탭에서 `권한 연결`을 누릅니다.
3. 운동 읽기/쓰기, 거리·칼로리·체중·체지방·과거 데이터 읽기를 허용합니다.
4. `새로고침 · 오류 재시도`를 누릅니다.

삼성 헬스와 NRC 외부 운동은 10분 이상일 때 목표와 연속 기록에 포함되지만 로컬 루틴 순서는 진행하지 않습니다. 체중·체지방률은 같은 날짜의 삼성 값을 우선합니다.

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
adb install -r app/build/outputs/apk/release/app-release.apk
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/release/wear-release.apk
```

동일한 키를 계속 보관해야 Room 데이터를 유지한 채 APK를 업데이트할 수 있습니다.

## PWA 보존

전환 직전 웹앱은 Git 태그 `pwa-final-2026-08-21`에 보존되어 있습니다. Android로 가져올 데이터는 기존 PWA 설정에서 JSON을 내보낸 뒤 오늘운동 설정의 파일 선택기로 불러옵니다.
