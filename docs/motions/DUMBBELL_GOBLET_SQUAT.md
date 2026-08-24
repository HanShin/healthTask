# 덤벨 고블릿 스쿼트 모션 정의

- 운동 ID: `dumbbell-goblet-squat`
- 리소스 접두사: `dumbbell_goblet_squat`
- 제작 기준: `docs/MOTION_QUALITY_STANDARD.md`
- 사람형 베이스: 승인된 스쿼트 MakeHuman/MPFB 메시·의상·리그와 하체 IK

## 레퍼런스

- ACE 동작 설명: https://www.acefitness.org/resources/everyone/exercise-library/362/goblet-squat/
- NASM 동작 설명: https://www.nasm.org/resource-center/exercise-library/goblet-squat
- NASM 손 받침과 자세 해설: https://www.nasm.org/resource-center/blog/training/how-to-perform-goblet-squats

레퍼런스는 자세와 타이밍 확인에만 사용하며 영상·음원·이미지는 앱에 포함하지 않는다.

## 동작 정의

- 발은 어깨너비로 두고 발바닥 전체를 플랫폼에 고정한다.
- 덤벨 하나를 수직으로 세워 가슴 앞에 들고 위쪽 헤드를 양 손바닥 사이에 깊게 넣은 뒤 손가락과 엄지로 양옆과 위 모서리를 감싼다.
- 팔꿈치는 갈비뼈 가까이에 두되 무릎 진행을 막지 않도록 자연스럽게 앞을 향한다.
- 복압과 중립 척추를 유지하며 엉덩이와 무릎을 함께 굽힌다.
- 무릎은 발끝과 같은 방향으로 진행하고 뒤꿈치가 들리거나 안쪽으로 무너지지 않는다.
- 허벅지가 바닥과 평행한 범위까지 내려간 뒤 발 전체로 플랫폼을 밀어 시작 자세로 돌아온다.
- 덤벨은 전 구간에서 수직을 유지하며 상체와 함께 부드럽게 내려갔다 올라온다.
- 프레임 1과 241은 같은 선 자세이며 MP4에는 중복 프레임 241을 넣지 않는다.

## 장비와 경로

- 승인된 바벨 스쿼트의 발 target, 무릎 pole, 골반·척추 제어와 깊이를 그대로 보존한다.
- 덤벨 손잡이: 지름 36 mm, 손잡이 구간 0.170 m, 수직 방향
- 덤벨 헤드: 꼭짓점 기준 반지름 0.053 m, 두께 0.056 m, 중심 간격 0.184 m의 팔각형. 첨부 레퍼런스처럼 손이 헤드 대부분을 감쌀 수 있도록 최초 원형 시안보다 폭을 약 38% 줄이고 세로 두께를 늘렸다. 위쪽 잡는 면에는 돌출 캡을 두지 않는다.
- 덤벨 중심 선 자세: `x=0.000, y=-0.255, z=1.105`
- 덤벨 중심 최하단: `x=0.000, y=-0.255, z=0.850`
- 손목 target: 덤벨 중심 기준 `x=±0.072, y=-0.005, z=+0.027`; 양 손바닥과 손날은 위쪽 헤드의 마주 보는 평면을 압착한다.
- 손가락: 검지와 중지는 끝마디를 거의 편 채 위쪽 면을 길게 덮고, 약지는 과도하게 접히지 않는 얕은 곡선으로 중지와 소지 사이에서 위쪽 사면을 감싼다. 소지는 중간·끝 관절을 `0°`로 완전히 편 뒤 첫 관절 방향만 돌려 손날에서 헤드 윗모서리까지 일자로 올리며, 양 엄지는 벌림 축을 좌우 반전해 같은 높이에서 위쪽 모서리를 잠근다.
- 팔꿈치 pole: 덤벨 중심 기준 `x=±0.195, y=+0.050, z=-0.215` 부근
- 타임라인: 출력 240 프레임, 30 fps, 정확히 8초의 하강·정지·상승·선 자세 정지

## 검수 산출물

- Blender 원본: `design/motion/dumbbell_goblet_squat_human_sample.blend`
- 생성 코드: `tools/blender/generate_human_dumbbell_goblet_squat.py`
- 6개 자세: `design/motion/previews/human_dumbbell_goblet_squat_{front,side}_{top,mid,bottom}.png`
- 그립 5방향·확대: `design/motion/previews/human_dumbbell_goblet_squat_grip_{front,angle,detail_front,detail_angle,rear}.png`
- 앱 영상: `app/src/main/res/raw/dumbbell_goblet_squat_{front,side}.mp4`
- 자동 그립·충돌 검사: 시작·중간·최하단에서 좌우 손가락 끝 대칭, 중지–약지–소지의 앞뒤 순서, 약지의 위쪽 사면 높이와 소지까지의 자연스러운 높이 차, 소지 중간·끝 관절 `0°`와 윗모서리 방향을 검사한다. 모든 의상과 덤벨 전체를 검사하고, 의도적인 손–위쪽 원판·손잡이 접촉을 제외한 인체와 덤벨 부품도 별도로 검사한다.

생성 코드의 `--mode validate`는 영상이나 프리뷰를 렌더하지 않고 충돌 검사와 Blender 원본 저장까지만 수행한다. `--mode render`는 H.264, 720 × 720, 30 fps, 240 프레임, 무음 MP4를 만든다.
