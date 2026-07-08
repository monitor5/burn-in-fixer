# OLED 번인 측정·보정 시스템

외부 스마트폰 카메라로 OLED 번인을 측정하고, 대상 기기에 네이티브 해상도 보정맵을 적용해
시각적 균일도를 개선하는 2-앱 시스템. 상세 요구사항은 [개발요구서.md](개발요구서.md) 참조.

> 이 시스템은 OLED 픽셀을 물리적으로 복구하지 않는다. 정상 영역의 밝기를 소폭 낮춰
> 화면 전체를 균일하게 보이게 하는 방식이다.

## 구성

| 폴더 | 앱 | 설치 대상 | 역할 |
| --- | --- | --- | --- |
| `burn-in-fixed` | 번인 보정 (Target) | 번인이 있는 태블릿/폰 (Android 7.0+) | 테스트 패턴 출력, TCP 서버(8899), 보정맵 수신·검증·적용, 시스템 오버레이 보정 |
| `burn-in-camera` | 번인 측정 (Scanner) | 카메라가 좋은 두 번째 폰 (Android 7.0+) | Camera2 촬영(노출 잠금), 화면 검출·정합, 휘도맵 분석, 보정맵 생성·전송, 반복 보정, 리포트 |

## 빌드

Android Studio에서 각 폴더를 열어 빌드하거나:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd burn-in-fixed  && gradle assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
cd burn-in-camera && gradle assembleDebug
cd burn-in-camera && gradle testDebugUnitTest   # 알고리즘 단위테스트
```

- compileSdk 35 / targetSdk 33 / **minSdk 24 (Android 7.0)**
- 외부 라이브러리: kotlinx-coroutines 뿐 (OpenCV 없이 순수 Kotlin 분석 모듈)

## 사용 순서

1. **같은 Wi-Fi**에 두 기기를 연결한다.
2. 대상 기기: 자동 밝기/블루라이트 필터/절전 끄기 → 보정앱 실행 → 홈 화면에 표시된 IP 확인.
   측정 중 앱을 전면에 켜 둔다 (백그라운드면 패턴 화면을 띄울 수 없음).
3. **암실 박스 지그에 거치** (중요):
   - 대상 기기를 박스 바닥에 화면이 위로 오게 눕힌다.
   - 측정 기기를 상판 구멍 위에 카메라가 아래를 향하게 올린다.
   - 화면이 카메라 프레임의 **40~90%** 를 차지하도록 박스 높이를 맞춘다.
   - 측정 기기 상단(카메라 쪽)을 대상 화면 상단과 같은 방향으로 맞춘다.
4. 측정앱: IP 입력 → 연결 → [측정 시작].
5. 자동 진행: gray70 노출 잠금 → 기준 촬영 → black 오프셋 → **red/green/blue 채널 측정**
   → 보정맵 생성·전송 → **반복 보정(최대 10회, 수렴 시 종료)** → 판정·리포트.
6. 대상 기기 패턴 화면을 **탭하면 보정 전/후 토글**, 측정앱의 [보정 전/후 토글]로도 원격 제어.
7. 일반 사용 화면에도 적용하려면 보정앱에서 [오버레이 켜기] (권한 필요, 알림에서 즉시 끄기 가능).

### 측정이 "평가 무효(측정 조건 변화)"로 끝나는 경우

보정 후 재측정의 중앙 휘도가 예상에서 10% 이상 벗어나면 반사·기기 이동·노출 변화로 판단하고
평가를 무효 처리한다. 손으로 들고 측정하면 거의 항상 발생한다 — 암실 박스에 고정할 것.

## 통신 프로토콜 (Wi-Fi, IP 기반)

TCP 8899, 한 줄 = JSON 하나(newline 구분). 요청 `{"cmd": ...}` → 응답 `{"ok": true|false, ...}`.

| 명령 | 파라미터 | 동작 |
| --- | --- | --- |
| `HELLO` / `REQUEST_SCREEN_INFO` | — | 기기 정보, 실제 화면 해상도, 보정맵 적재 상태 |
| `SHOW_PATTERN` | `pattern` | 전체 화면 패턴 표시 후 ACK. `black`, `gray30~85`, `white70/85`, `red70`, `green70`, `blue70`, `marker`, `grid`, `checker`, `dotgrid` |
| `APPLY_CORRECTION_MAP` | `width height maxAttenuation defaultStrength checksumMd5 data(base64 PNG)` | 검증(해상도·체크섬·값 범위) 후 저장·적재. 불일치 시 거부 |
| `ENABLE_CORRECTION` / `DISABLE_CORRECTION` | `strength(0~100)` | 앱 내부 보정 on/off |
| `ENABLE_OVERLAY` / `DISABLE_OVERLAY` | `strength` | 시스템 오버레이 보정 on/off |
| `END_SESSION` | — | 패턴 화면 종료 |

보정맵 포맷: 그레이 PNG, 픽셀값 `v` → 해당 위치를 `v/255 × maxAttenuation` 만큼 낮춤
(0 = 보정 없음, 255 = 최대 감쇠, 기본 maxAttenuation 5%).

## 측정 파이프라인

```
gray70 표시 → AE/AWB 잠금(세션 내 동일 노출, 노출은 화면 주사 주기의 정수배로 양자화해
   롤링셔터 밴딩/플리커 상쇄; ISO로 밝기 보상)
→ 5장 촬영(위상 지터)·절사 평균(픽셀별 min/max 제외) → 블록 평균 다운샘플(무아레 저역 통과)
→ 선형화(감마 2.2 역산) → 밝은 사각형 검출(암실 가정)
→ 4점 호모그래피(화면↔카메라 좌표) → black offset 제거 → 분석 그리드(긴 변 192셀) 휘도맵
→ target = 하위 10퍼센타일, gain = clamp(target/측정값, 1-5%, 1)
→ 적응형 블러(반경 = 카메라 1px당 그리드 셀 수 — 카메라 해상 한계 이하 노이즈/무아레 제거)
   + 가장자리 confidence 감쇠
→ 네이티브 해상도 알파 PNG 업스케일 → 전송·적용
→ [반복] 재촬영(화면 재검출로 미세 이동 흡수) → newGain = oldGain×(target/측정값)^0.3
   수렴(개선량<0.1%p+5%)·발산(2회 연속 악화→롤백)·조건변화(드리프트>10%→무효) 감지, 상한 10회
→ 판정(잔여 RMS ≤ 1.5% 합격) → report.json + 히트맵 저장
```

리포트 위치: 측정 기기 `Android/data/com.burnin.scanner/files/sessions/<시각>/`

## 검증 상태

- 단위테스트 13개: 호모그래피 정확도, 화면 검출, 합성 번인 종단 파이프라인(개선율 ≥60%),
  반복 보정 수렴, 패턴 파서
- 에뮬레이터 통합테스트 18/18: 프로토콜 전 명령, 패턴 픽셀값, 잘못된 맵 거부,
  보정맵 적용 픽셀 검증(179→171), 해제 복귀
- 실기기: Galaxy Tab S2(Android 7.0, 1536×2048) ↔ Galaxy S25, 실제 Wi-Fi로
  측정→전송→적용→재측정 전체 루프 동작 확인

## 요구서 대비 구현 범위 (MVP)

구현: 패턴 출력(T-FR-002/003/004), 수동 IP 페어링(12.1의 3안), 세션 프로토콜(12.3 부분),
보정맵 검증(T-FR-007), 앱 내부 보정(T-FR-008, Canvas 합성), 오버레이 보정(T-FR-009/010/011),
프레임 평균화(M-FR-005), 좌표 정합(M-FR-007 기본), 휘도맵(M-FR-008), RGB 채널 측정(M-FR-009 진단),
네이티브 보정맵(M-FR-010), 반복 보정(M-FR-012), 과보정·조건변화 감지(M-FR-013), 리포트(M-FR-014),
적용 후 평가(M-FR-017), 컷아웃 기기 1:1 매핑.

미구현(후속): QR 페어링, 렌즈 왜곡 역산(M-FR-015), 플랫필드/비네팅 분리(M-FR-016),
RAW 촬영, 마커 기반 정밀 정합·180° 방향 자동 판별, RGB 채널별 보정맵 적용,
OpenGL 셰이더 렌더러, 고주파 번인 전용 모드, 프로파일 다중 관리, PC 분석 도구.
