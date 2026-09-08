# 테스트 보강과 검증 범위

## 실행

CI와 동일한 JDK 17, Android SDK Platform 35, Build Tools 34.0.0 및 SDK 라이선스 동의가 필요합니다. 각 앱의 Gradle 8.13 Wrapper는 배포 ZIP SHA-256을 검증합니다. 첫 실행에는 Maven/Google 의존성과 Robolectric Android 런타임을 내려받습니다.

```sh
export ANDROID_HOME=/path/to/android-sdk
bash scripts/check.sh
# 앱별 실행
bash scripts/check.sh burn-in-camera
bash scripts/check.sh burn-in-fixed
```

위 명령은 두 앱의 프로토콜 상수 일치, JUnit/Robolectric 테스트, Android lint, Kotlin/Java 컴파일 및 Debug APK 생성을 검사합니다. `pnpm`, `pytest`, `cargo` 프로젝트는 아닙니다. CI도 같은 스크립트를 실행하고 테스트 XML과 lint 보고서를 artifact로 보존합니다.

메모리가 부족하면 앱을 순서대로 실행하고 다음 옵션을 사용할 수 있습니다.

```sh
./burn-in-camera/gradlew -p burn-in-camera \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  --continue --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx768m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
```

테스트 결과는 `<project>/app/build/reports/tests/testDebugUnitTest/index.html`, lint는 `<project>/app/build/reports/lint-results-debug.html`에서 확인합니다. 생성 파일·SDK·Gradle 캐시는 커밋하지 않습니다.

## 증거 수준

- 기존 41개 테스트에서 출발해 정상값뿐 아니라 비정상 수치, 입력 크기, 취소 시점, 저장 실패, 실제 비트맵 픽셀까지 검사합니다. 테스트 메서드 수는 커버리지 비율이 아닙니다.
- Scanner의 PNG를 실제 Android 비트맵 API로 인코딩·디코딩하고 Target의 저장소/Canvas 렌더러를 별도로 검증합니다. Robolectric native graphics 기반이며, 두 물리 기기 사이의 완전한 E2E를 실행했다는 뜻은 아닙니다.
- TCP는 임의의 로컬 포트를 가진 실제 `ServerSocket`으로 UTF-8, 명령 대응, EOF, 타임아웃, 읽기 중단을 검증합니다. 타이밍 동기화에는 latch를 사용합니다.
- 측정 Activity의 파일 기반 절사 평균은 실제 private 경로를 호출합니다. 내부 메서드 이름을 변경하면 이 연결 테스트도 갱신해야 합니다.
- Camera2 자원 전달은 취소 전/후 및 resume와 dispatch 사이의 취소를 재현합니다. 제조사 HAL, 센서 타임스탬프와 CaptureResult의 대응은 별도 실기기 검증 대상입니다.
- lint 오류를 수정했지만 기존 UI 문자열·구형 target SDK·방향 고정·ML Kit 버전 권고 등의 경고는 남아 있습니다. 경고를 baseline이나 광범위한 suppress로 숨기지 않습니다. `lintDebug` 성공을 경고 0개로 해석하지 않습니다.

## 2026-09-08 로컬 검증 결과

- 정식 Gradle JUnit/Robolectric: Scanner 132개 + Target 32개 = **164개 통과**, 실패·skip 0개.
- 두 앱 `assembleDebug` 및 Kotlin/Java 컴파일 성공. Scanner API 28 계약 lint 오류 2개 보정.
- Android lint 오류 0개, Scanner 43개/Target 27개의 기존 권고 경고 확인. 경고 항목은 보고서에 그대로 노출합니다.
- 이전 점검에서 살아남았던 7가지 고장(90도 회전 제거, P95=0, 기하 score=0, 공간 confidence=1, 시간 confidence=1, 빈 alpha PNG, 빈 RGB PNG)을 동시에 주입한 검사에서 각각 대응하는 회귀 테스트가 실패했습니다. 전체 mutation score를 측정한 결과는 아닙니다.
- 고장 주입 후 소스를 복원하고 CI와 같은 `scripts/check.sh`로 두 앱 전체 정상 테스트·lint·APK 생성을 다시 통과했습니다. 원격 CI 결과와 실기기 정확도는 별도 증거입니다.

## 전체 점검 항목과 현재 경계

초기 전체 점검의 H01~H35를 유지합니다. “보강”은 아래에 적힌 계약을 검증한다는 뜻이며 해당 영역의 모든 가능한 장애가 해결되었다는 뜻은 아닙니다.

| ID | 영역 | 이번 보강 | 남은 확인 |
| --- | --- | --- | --- |
| H01 | 실패·취소·드리프트 | 비취소 정리, 원인/정리 오류 보존, 무효 후보 롤백 | 통신 단절 시 원격 상태 확인, 세션 시작 전 프로파일 전체 복원 프로토콜 |
| H02 | 최적 후보·종료 | 무보정 후보 포함, 악화/동점/NaN 거부, 후보 배열 복사 | 전체 반복 횟수·정체·발산을 기기 루프로 재현 |
| H03 | 기존 보정 상태 | 시작 시 앱 보정과 시스템 오버레이 OFF | 기존 WB 프로파일 보존/복원과 실제 화면 프레임 확인 |
| H04 | 최종 유효성 | 어두운 신호/NaN은 통과 불가, 화면 검출·좌표 이동 실패 중단 | 최종 촬영의 플리커·노출·미광 재검증 통합 |
| H05 | 교차 마스크 유지 | 저계조, RGB 맵, 혼합·반복 후 persistent mask 재적용 | 실제 다중 카메라 시공간 동기화 |
| H06 | 통계·합격 | 독립 P95/중앙값/RMS oracle, RMS와 P95 경계 동시 검사 | 통계 정의를 외부 계측 기준과 비교 |
| H07 | 보고서 일관성 | 기준 RMS 0에서 유한 개선율 | 중단 세션의 완전한 진단 보고서와 적용 맵 식별자 연결 |
| H08 | PNG 왕복 | signature/크기/채널/보간/감마/alpha 및 Target 픽셀 검증 | Scanner→TCP→Target 두 기기 E2E, 실제 휘도 응답 |
| H09 | 프로파일 저장 | 전체 파일 staging, 교체 실패 보존, 체크섬·RGB 누락·크기 검사 | 전원 차단의 파일시스템 내구성, 여러 volatile 필드의 단일 snapshot화 |
| H10 | 재시작·삭제 | 재적재도 동일 검증, 저장 시 체크섬 계산, 생성시각 보존, 복구 디렉터리, WB 삭제 | 파일시스템 장애 주입과 OS 프로세스 강제 종료 |
| H11 | 패턴 ACK | Host 없는 timeout은 성공 ACK 금지, Host commit 전 콜백 대기 | `View.post`와 실제 디스플레이 frame commit의 차이, correction ACK barrier |
| H12 | TCP | 명령 불일치/오류/EOF/timeout 시 연결 폐기, close가 read 중단, 한글 왕복 | 서버의 최대 메시지 길이·다중 클라이언트·접속 대기 제한 |
| H13 | 비전 파서 | 정확한 상태 토큰, 부정문/모호한 문장 거부, 비유한 confidence 0, 취소 전파 | 실제 모델 다운로드·추론 실패/취소 |
| H14 | 마스크 누출 | 블러 후 신뢰도 0 셀의 최초 무보정/반복 이전 값 보존 | 공간 신뢰도의 물리적 보정 모델 |
| H15 | 신뢰도 계산 | clipping/미광/SNR 독립 기대값, 전역·국소 flicker, malformed shape | 실제 PWM/rolling shutter 원본 fixture |
| H16 | Camera2 생명주기 | 늦은 resource 도착 및 resume 이후 취소 시 close, 실패 세션 close | HAL 오류, 단독/다중 capture listener 경쟁, 프레임-CaptureResult 대응 |
| H17 | WB | 채널 신호 유효성, 감쇠만 허용, clipping/노출 차이, 저장 실패 보존 | 기존 WB 복원, 독립 색차 지표와 최종 효과 판정 |
| H18 | 이미지 디코드 | YUV row/pixel stride, 홀수 크기, 범위, RGB 채널, 잘린 plane, 1픽셀 보간 | JPEG 센서 fixture와 YUV/JPEG 물리 일치도 |
| H19 | 방향·정합 | 8가지 대응의 모든 모서리와 비대칭 내부 좌표, 90도 방향, 퇴화/NaN 거부 | 실제 마커의 180도/반전, dot-grid 후 최종 정합 유지 |
| H20 | dot-grid | 121개 대응, 이동 복원, residual, 부족/먼 점/빈 화면 거부 | 비선형 왜곡·가림·중복 blob 원본 fixture |
| H21 | 화면 검출 | 분리 반사점과 작은 광점 집합 거부, 최대 연결 영역, 기하 점수 oracle | 반사가 화면과 연결된 경우, 여러 큰 사각형과 실물 원근 |
| H22 | flat-field | 기존 합성 플랫필드 회귀 유지 | 실제 패널 열화와 렌즈 음영 분리 실험; 소프트웨어 테스트로 입증 불가 |
| H23 | gain 수치 | 원소별 기대값, damping, 혼합/평균, 감쇠 범위, 배열/NaN 검증 | 실제 디스플레이 응답의 비선형성 |
| H24 | 절사 평균·캐시 | 실제 Activity 3/4프레임 경계, RGB, 캐시 metadata/stride/EOF/과대 길이 | 모든 중간 store의 예외별 소유권 정리, 큰 파일 메모리 측정 |
| H25 | 카메라 선택 | FOV 55/95 경계, 비유한 값, 큰 불가능 그룹에서 작은 가능한 그룹 fallback | OEM 공개/물리 ID 중복, 실제 concurrent camera 목록 |
| H26 | 노출 | ceiling/floor, ISO 보상 실패 fallback, 소수 주사율, Long overflow | HAL 적용값과 요청값의 차이, 수동 모드 실패 |
| H27 | 영역·진단 | 4방향 연결/threshold/정렬, floor, crop gradient, 카메라 열/heatmap 픽셀 | bbox 내부 다른 컴포넌트의 confidence 복원 정책 |
| H28 | 오버레이 | 맵 교체, 권한·역할 거부, strength, STOP/destroy | 180도 회전·멀티윈도우·실제 WindowManager 거부/회수 |
| H29 | UI·Session | 초기 버튼/빈 IP, 주소 복구, Activity 로그 listener 수명 | 연결 재시도와 빠른 역할 전환, 모든 Activity 재생성/권한 흐름 |
| H30 | 패턴 문법 | 7색 × 0~100%, alias/공백/잘못된 입력/클램프 | 물리 화면 색 정확도 |
| H31 | 보고서 저장 | 동시각 고유 폴더, report.json 마지막 publish, 실패 rollback, 경로 검증 | 전원 차단 내구성과 원본 캡처/맵의 장기 추적 |
| H32 | 실행 환경 | checksum Wrapper, 실제 Gradle test/lint/APK, 프로토콜 비교, GitHub Actions | API 24/26 및 다양한 제조사 기기 matrix |
| H33 | 보조 코드 | 로그 listener 해제와 서버/프로토콜 경로 확인 | NetUtils의 실제 네트워크 인터페이스 조합 |
| H34 | 검증 독립성 | 수치 oracle, 실제 PNG/Canvas, 고장 주입 검사 | 버전 고정된 실기기 원본 fixture, 고해상도 latency/peak RSS |
| H35 | 재정합 상태 | 반복·최종에서 모든 모서리 이동을 검사하고 재측정 요구 | 모서리 유지 상태의 180도 회전/노출 변화, 재보정 자동화 |

## 변경된 실패 계약

- 잘못된 수치/shape를 조용히 정규화하던 일부 분석 API는 `IllegalArgumentException`으로 거부합니다.
- 실패한 TCP 요청은 연결을 폐기합니다. 다음 요청 전에 재연결해야 합니다.
- 잘못된 저장 프로파일은 기존 메모리 상태를 교체하지 않습니다. 앱 재시작의 빈 상태에서는 적재 실패로 남습니다.
- 측정 이동 허용치는 모든 모서리의 유클리드 거리 4px입니다. 초과하면 이전 black/flat-field를 재사용하지 않습니다. 실측을 통한 허용치 조정은 후속 검증 대상입니다.
- 파일 교체는 프로파일 디렉터리 옆의 `.pending`/`.backup`을 사용합니다. 기존 `profiles/current` 및 `profiles/white_balance` 내부 형식은 유지합니다. 구버전의 체크섬 없는 프로파일도 검증 가능한 범위에서 읽고 새 저장에는 체크섬을 기록합니다.

정확도·실기기 실험의 기존 제한은 [검증 상태](validation.md)를 함께 참고합니다.

## PR #2 후속 보완

- 최종 후보는 회색 gain과 RGB 채널 gain·혼합 비율·무보정 여부를 함께 보관합니다. 기기 전송과 리포트 PNG 생성은 동일한 후보와 인코더를 사용합니다. 무보정 선택 시 RGB 파일을 만들지 않고 `rgb30GainWeight=0`, `baselineSelected=true`를 기록합니다.
- 무보정 후보로 롤백할 때 앱 보정을 OFF로 유지하여 저장된 화이트밸런스가 다시 켜지지 않도록 합니다. 기존 WB 프로파일 파일의 보존/복원 한계는 여전히 별도 검증 대상입니다.
- 실패·취소 정리는 기존 연결이 폐기된 경우에도 최대 한 번 재연결하고 두 해제 명령을 각각 처리합니다. 연결과 각 응답 대기는 2초로 제한합니다. 정리 실패는 원래 오류에 보존하고 화면·Toast·로그에 대상 기기에서 직접 보정 상태를 확인하도록 안내합니다. 연결이 돌아왔다는 사실만으로 원격 정리 성공을 판단하지 않습니다.
- 회귀 검증에는 무보정 PNG/RGB 정책, 최종 후보 배열 보존, 실제 Activity의 TCP 전송, 실패 ACK 후 재연결·두 레이어 해제, 첫 해제 실패 뒤 다음 해제 시도 및 재연결 한도 검사를 포함합니다.
- 후속 보완 로컬 검증: JDK 17에서 Scanner **138개**, Target **32개**, 총 **170개** 테스트 통과(실패·skip 0). 두 앱 lint와 Debug APK 생성 성공. 연결된 Android 기기가 없어 실기기 검증은 수행하지 않았습니다.
