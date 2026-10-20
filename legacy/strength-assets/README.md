# 이전 근력운동 가이드 보관

러닝·인바디 전용 2.0 전환으로 앱에서 제외한 MP4/SVG 가이드입니다.
삭제하지 않고 이 폴더로 옮겼으므로 필요하면 원래 리소스 경로로 복구할 수 있습니다.
이 폴더는 Android 빌드 및 APK에 포함되지 않습니다.

- `raw/`: 기존 정면·측면 MP4 26개
- `guides/`: 기존 SVG 가이드
- Blender 원본과 제작 문서는 기존 `design/motion`, `tools/blender`, `docs/motions`에 그대로 보존

사용자의 이전 운동 데이터는 변경하지 않습니다. 이전 데이터와 JSON 백업을 읽기 위한 DB 테이블과 프로토콜 필드는 호환용으로 남아 있습니다.

저장소 루트에서 보관된 MP4 규격을 검사하려면 다음과 같이 경로를 명시합니다.

```bash
python3 tools/verify_motion_videos.py legacy/strength-assets/raw
```

제작 스크립트·운동별 문서는 이전 `app/src/main/res/raw` 경로를 참조할 수 있습니다. 이는 역사적 제작 경로이며 현재 APK에 영상을 다시 넣으라는 의미가 아닙니다. 재생성할 때는 먼저 출력 위치를 확인하세요.
