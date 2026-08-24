# 작업 진행 로그

## Android 네이티브 전환

- [x] PWA 최종 상태를 `pwa-final-2026-08-21` 태그로 보존
- [x] `feat/android-native-rewrite` 브랜치 생성
- [x] Android Studio, SDK Platform 34/36, Platform Tools 설치
- [x] Kotlin + Compose Material 3 + Navigation 단일 Activity 구조
- [x] Room 정규화 모델과 DataStore 설정
- [x] 루틴 CRUD, 순번, 운동 세션, 최근 세트 이어받기, 건강 기록
- [x] Health Connect Gateway와 Fake 가능 경계
- [x] 삼성 헬스 원본 읽기, 로컬 운동 요약 쓰기, 실패 재시도
- [x] 10분 목표 기준, 70% 자동 연결, 중복 목표 방지
- [x] legacy-v1 가져오기와 schemaVersion 2 내보내기
- [x] 기존 PWA 소스 제거 및 SVG 가이드 번들
- [x] 단위 테스트, lint, debug APK, androidTest APK 빌드
- [ ] 삼성 Android 14+ 실기기 `connectedDebugAndroidTest`
- [x] 저장소 밖 개인 릴리스 키 생성 및 서명된 release APK 검증
- [x] Galaxy Watch9 대상 `:wear` Wear OS 앱 모듈
- [x] Data Layer 다음 루틴 전송과 오프라인 완료 기록 전달
- [x] 워치 세트·횟수·중량 편집, 일시정지, 진행 상태 복원
- [x] Health Services 심박·평균 심박·칼로리·거리 측정 서비스
- [x] 워치 기록의 휴대폰 Room 멱등 가져오기와 Health Connect 전송 대기
- [x] 휴대폰/워치 release APK 동일 인증서 서명 검증
- [x] CC0 사람형 아바타 기반 바벨 스쿼트 정면·측면 3D 루프 제작
- [x] 실제 하이바 스쿼트 정면·측면 레퍼런스로 다리 IK 축, 골반·척추, 바벨 경로와 그립 전면 재제작
- [x] 향후 모든 3D 운동에 적용할 모션 품질 기준과 스쿼트 그립 정면·사선·후면 기준 이미지 문서화
- [x] Media3 오프라인 자동 반복과 정면·측면 전환을 운동 가이드에 통합
- [x] 플랫 덤벨 프레스와 원암 덤벨 로우의 벤치·의상·덤벨 자동 충돌 검사 보강
- [x] 덤벨 고블릿 스쿼트의 동작 정의, 접촉 검수, 정면·측면 3D 루프와 앱 연결
- [ ] 서명된 release APK 실기기 설치·업데이트 검증
- [ ] Galaxy Watch9에서 센서·오프라인·재연결 전달 실기기 검증

## 검증 기록

2026-08-21:

- `assembleDebug`: 성공
- `test`: 성공
- `lintDebug`: 성공(오류 0, 버전/리소스 권고 경고만 존재)
- `assembleDebugAndroidTest`: 성공
- `assembleRelease`: 성공, APK Signature Scheme v2 검증 성공
- 휴대폰·워치 release 인증서 SHA-256 일치 확인
- 스쿼트 MP4 2개 release APK 리소스 포함 및 Media3 1.11.0 빌드 확인
- 생성 파일:
  - `app/build/outputs/apk/debug/app-debug.apk`
  - `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - `app/build/outputs/apk/release/app-release.apk`
  - `wear/build/outputs/apk/debug/wear-debug.apk`
  - `wear/build/outputs/apk/release/wear-release.apk`

2026-08-24:

- 사람형 3D 가이드 6종·정면/측면 MP4 12개 완성
- 플랫 덤벨 프레스와 원암 덤벨 로우 장비·의상 충돌 보강 및 주요 프레임 자동 검사 통과
- 덤벨 고블릿 스쿼트 6개 자세와 그립 5방향·확대 검수, 자동 그립·충돌 검사 통과
- 고블릿 스쿼트 양손의 비대칭 엄지 축을 교정하고, 덤벨 헤드 폭을 최초 시안보다 약 38% 줄인 뒤 세로 두께를 보강했다. 검지·중지 패드는 위쪽 면을 길게 덮고 약지는 과도한 갈고리 굽힘 없이 위쪽 사면을 부드럽게 감싸며, 소지는 중간·끝 관절을 완전히 펴 손날에서 윗모서리로 올라가는 레퍼런스형 대칭 그립으로 재렌더
- `tools/verify_motion_videos.py`: 12개 모두 H.264, 720 × 720, 30 fps, 240프레임, 8초, 무음 통과
- `test lintDebug assembleDebug`: 성공, debug APK에 MP4 12개 포함 확인

실기기 검증에서는 삼성 헬스 양방향 표시, Watch9 단독 기록과 재연결 전송, 워치/삼성 동시 기록 자동 연결, 권한 취소/재허용, 오프라인/재시작, PWA 개수 일치, 동일 서명 업데이트 후 Room 유지 여부를 확인합니다.
