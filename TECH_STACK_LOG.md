# 기술 스택

| 구분 | 기술 | 버전 | 역할 |
|---|---|---:|---|
| 언어 | Kotlin | AGP 9 내장 Kotlin + Compose compiler 2.3.21 | 앱 및 도메인 구현 |
| UI | Jetpack Compose Material 3 | BOM 2026.06.00 | 네이티브 화면과 디자인 시스템 |
| Navigation | Navigation Compose | 2.9.8 | 온보딩 및 하단 4탭 |
| DB | Room | 2.8.4 | 프로필, 루틴, 세션, 세트, 건강, 동기화 저장 |
| 설정 | DataStore Preferences | 1.2.1 | 동기화 사용 여부와 마지막 시각 |
| 건강 연동 | Health Connect Client | 1.1.0 | 삼성 운동/체성분 읽기와 운동 요약 쓰기 |
| JSON | Gson | 2.13.2 | legacy-v1 및 schemaVersion 2 백업 |
| 비동기 | Kotlin Coroutines | 1.10.2 | Flow 기반 DB/UI와 동기화 |
| 워치 UI | Compose for Wear OS Material 3 | 1.6.2 | Galaxy Watch 원형 화면 UI |
| 워치 센서 | Health Services Client | 1.1.0-rc02 | 운동 중 심박·칼로리·거리 |
| 기기간 연동 | Play Services Wearable Data Layer | 20.0.1 | 루틴 및 완료 운동 영속 전송 |
| 미디어 재생 | AndroidX Media3 ExoPlayer/UI | 1.11.0 | APK 내 3D 운동 모션 무음 반복 재생 |
| 3D 제작 | Blender / MPFB | 4.5.10 LTS / 2.0.17 | CC0 사람형 스쿼트 원본·IK 애니메이션 렌더 |
| 빌드 | Gradle / AGP | 9.4.1 / 9.2.0 | Android 빌드와 검증 |
| 코드 생성 | KSP | 2.3.10 | Room 구현 생성 |

휴대폰은 `minSdk 34`, 워치는 `minSdk 30`, 두 앱의 `targetSdk`는 36이고 패키지는 `com.hanshin.healthtask`로 같습니다. 시스템 JDK 26 대신 Android Studio 번들 JDK를 사용합니다. 휴대폰만 Room과 Health Connect의 기준 저장소이며 워치는 오프라인 운동 수집기 역할을 합니다.
