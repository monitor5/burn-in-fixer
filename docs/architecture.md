# 구조와 프로토콜

## 데이터 흐름

```text
Scanner → TCP 요청 → Target 패턴 출력
   ↓                       ↓
Camera2 촬영 ← 화면 발광 + 광학계 + 카메라 처리
   ↓
프레임 평균 / 근사 선형화 / 화면 검출
   ↓
마커 방향 판별 + dot-grid 호모그래피
   ↓
black offset / 방사형 플랫필드 근사 / 신뢰도
   ↓
gray70 + gray25 + RGB 분석 → 감쇠맵
   ↓
Target 앱 내부 적용 → 재촬영 → 후보 평가 → 리포트
```

주요 구현은 Scanner의 `camera/`, `analysis/`, `measure/`, `color/`, `vl/`, `report/`와 Target의 `pattern/`, `correction/`, `overlay/`, `net/`에 있습니다. 측정 오케스트레이션은 현재 `MeasurementActivity`에 집중되어 있습니다.

## 출력 경로

| 경로 | 적용 방식 | 범위 |
| --- | --- | --- |
| 앱 내부 단색 패턴 | RGB 코드값 감쇠, 별도 화이트밸런스 맵 | Target 패턴 렌더러 |
| 앱 내부 알파 보정 | 검정 비트맵 합성 | RGB 경로를 사용하지 않는 패턴 등 |
| 시스템 오버레이 | 검정 알파 비트맵 | OS가 허용하는 일반 앱 위 영역 |

세 경로의 색·밝기 응답은 동일하다고 가정하지 않습니다. 현재 출력은 Canvas/Bitmap 기반이며 범용 콘텐츠용 선형 RGB 셰이더는 구현되어 있지 않습니다.

## 현재 측정 상수

`burn-in-camera/.../measure/MeasurementActivity.kt`의 상수가 기준입니다.

| 항목 | 값 | 해석 |
| --- | --- | --- |
| 회색 평가 패턴 | gray70, gray25 | 두 패턴 모두 최종 기준 통과 필요 |
| 패턴당 기본 프레임 | 5 | 최소·최대 제외 평균을 사용 |
| 분석 그리드 | 대상 화면 너비 × 높이 | 촬영의 유효 해상도와는 다름 |
| 최대 감쇠 파라미터 | 0.10 | 실측 휘도 손실 10% 보증 아님 |
| 반복 상한 | 24 | 수렴·정체·발산·드리프트 조건으로 조기 종료 가능 |
| damping | 0.3 | 반복 gain 갱신 계수 |
| 합격 기준 | RMS ≤ 1.5%, P95 ≤ 3.5% | 근사 처리된 지도에 대한 내부 판정 |
| 드리프트 임계 | 10% | 모델 기반 예상 중앙값 대비 편차 |

Target 프로토콜에서 `maxAttenuation`을 생략하면 번인 맵 기본값은 0.05입니다. Scanner가 현재 전송하는 값은 0.10이므로 둘을 혼동하지 않아야 합니다.

## TCP 프로토콜

포트 8899. 한 줄에 JSON 객체 하나를 UTF-8로 전송합니다. 응답의 `ok`와 오류 내용을 확인해야 합니다. 인증·TLS·페어링 토큰은 현재 구현되어 있지 않으며 체크섬은 송신자 인증 기능이 아닙니다.

| 명령 | 주요 필드 | 용도 |
| --- | --- | --- |
| `HELLO`, `REQUEST_SCREEN_INFO` | — | 기기·역할·화면·프로파일 정보 |
| `SHOW_PATTERN` | `pattern` | 단색, marker, grid, checker, dotgrid |
| `APPLY_CORRECTION_MAP` | `width`, `height`, `maxAttenuation`, `defaultStrength`, `checksumMd5`, `data`; 선택 `rgbData`, `rgbChecksumMd5` | Base64 PNG 맵 검증·저장 |
| `ENABLE_CORRECTION`, `DISABLE_CORRECTION` | 선택 `strength` | 앱 내부 보정 상태 |
| `ENABLE_OVERLAY`, `DISABLE_OVERLAY` | 선택 `strength` | 시스템 알파 오버레이 |
| `APPLY_WHITE_BALANCE` | `width`, `height`, `maxAttenuation`, `checksumMd5`, `data`, 선택 `redGain`, `greenGain`, `blueGain`, `sourceDevice` | 별도 화이트밸런스 RGB 레이어 |
| `CLEAR_WHITE_BALANCE` | — | 화이트밸런스 제거 |
| `END_SESSION` | — | 패턴 화면 종료 |

대조설비(reference)는 보정·화이트밸런스·오버레이 적용 명령을 거부하고 패턴을 무보정으로 표시합니다.

그레이 맵 값 `v`는 감쇠 파라미터 `v / 255 × maxAttenuation`을 의미합니다. RGB 맵은 각 채널이 해당 감쇠를 나타냅니다. 이는 출력에 쓰는 코드값·알파 모델의 정의이며 물리 휘도 교정 단위가 아닙니다.

요청 예:

```json
{"cmd":"SHOW_PATTERN","pattern":"gray70"}
```
