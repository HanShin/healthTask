# Session Handoff

## 현재 상태

`오늘운동`은 PWA가 아닌 Android 네이티브 앱입니다. 기존 웹 버전은 `pwa-final-2026-08-21` 태그에만 남아 있고 현재 브랜치는 `feat/android-native-rewrite`입니다.

핵심 코드는 다음 위치에 있습니다.

- `app/src/main/java/com/hanshin/healthtask/data`: Room 저장소, seed, JSON 백업
- `app/src/main/java/com/hanshin/healthtask/domain`: 목표·순서·중첩·세션 상태 규칙
- `app/src/main/java/com/hanshin/healthtask/health`: Health Connect 경계와 동기화
- `app/src/main/java/com/hanshin/healthtask/ui`: Compose 화면과 상태
- `app/src/test`: 순수 단위 테스트
- `app/src/androidTest`: Room, legacy import, Health Connect 재시도 테스트
- `shared`: 휴대폰/워치 Data Layer 프로토콜과 경과 시간 규칙
- `wear/src/main/java`: Watch9 Compose UI, DataStore, Data Layer, Health Services
- `app/src/main/res/raw`: 스쿼트 정면·측면 오프라인 3D 모션 MP4
- `design/motion` 및 `tools/blender`: 최종 Blender 원본과 재생성 스크립트

## 남은 실환경 작업

1. USB 디버깅을 켠 Android 14+ 삼성 휴대폰을 연결합니다.
2. Galaxy Watch9을 Wi-Fi 디버깅으로 페어링합니다.
3. `./gradlew connectedDebugAndroidTest`를 실행합니다.
4. 휴대폰과 워치에 각각 `app-release.apk`, `wear-release.apk`를 설치합니다.
5. Watch9에서 활동 인식·심박·알림 권한을 허용하고 센서 측정을 확인합니다.
6. 휴대폰 연결을 끊은 채 운동을 완료한 뒤 재연결해 Room 전달과 Health Connect 쓰기를 확인합니다.
7. 기존 PWA JSON 개수와 동일 서명 업데이트 후 Room 유지 여부를 확인합니다.
8. `~/.android/healthtask-release.jks`와 `~/.gradle/healthtask-signing.properties`를 안전한 별도 위치에 백업합니다.

거리와 칼로리는 현재 합의한 제한 권한에 포함되지 않으므로 Health Connect 외부 기록에서 값이 없을 수 있습니다. 앱은 해당 값을 nullable로 유지하며 시간과 원본 앱 표시는 항상 제공합니다.

스쿼트 3D 가이드는 MakeHuman Community의 CC0 베이스 메시·시스템 자산과 MPFB 2.0.17로 생성했습니다. 실제 하이바 스쿼트 정면·측면 Shorts를 자세 레퍼런스로만 사용해 MPFB 다리 IK 축(-90도), 고정된 발, 무릎 트래킹, 골반·척추 회전, 바벨 수직 경로, 손 그립을 재제작했습니다. 앱에는 자체 렌더된 MP4만 포함되며, Media3 1.11.0이 무음 반복 재생합니다. 다른 운동은 기존 SVG 가이드를 유지합니다.

새 3D 운동을 제작하거나 기존 모션을 수정할 때는 `docs/MOTION_QUALITY_STANDARD.md`를 완료 조건으로 사용합니다. 특히 장비를 잡는 손은 주먹 포즈로 대체하지 않고 손가락 뿌리 축을 장비와 정렬한 뒤 관절별 접촉을 계산하며, 정면·사선·후면 근접 렌더까지 통과해야 합니다. 현재 스쿼트의 `design/motion/previews/human_squat_grip_{front,angle,rear}.png`가 최소 시각 품질 기준입니다.
