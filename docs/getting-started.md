# 설치·실험 가이드

## 빌드 환경

두 폴더는 서로 독립적인 Android Gradle 프로젝트입니다.

| 항목 | Target (`burn-in-fixed`) | Scanner (`burn-in-camera`) |
| --- | --- | --- |
| 최소 Android | 7.0 / API 24 | 8.0 / API 26 |
| compileSdk / targetSdk | 35 / 33 | 35 / 33 |
| Java 소스·바이트코드 | 17 | 17 |
| Android Gradle Plugin | 8.7.3 | 8.7.3 |

정확한 Kotlin 및 의존성 버전은 각 `build.gradle.kts`가 기준입니다. Scanner의 최소 OS 지원과 Gemini Nano 기능 지원은 별개입니다. 다중 카메라·수동 촬영·온디바이스 모델 사용 가능 여부는 기기와 시스템 구성에 따라 다릅니다.

Android SDK Platform 35와 JDK 17 이상의 호환 환경을 준비합니다. 각 폴더를 Android Studio에서 열어 Gradle 프로젝트로 가져옵니다. 저장소에는 Gradle Wrapper가 포함되어 있지 않으므로 로컬 Gradle을 별도로 준비해야 합니다. Gradle 8.13을 사용하는 예:

```sh
# 저장소 루트에서 실행, gradle은 PATH에 설치된 Gradle 8.13
# JAVA_HOME은 사용하는 JDK 경로로 설정

gradle -p burn-in-fixed :app:testDebugUnitTest :app:assembleDebug
gradle -p burn-in-camera :app:testDebugUnitTest :app:assembleDebug
```

SDK 경로는 Android Studio가 생성하는 각 프로젝트의 `local.properties` 또는 로컬 SDK 환경 설정을 사용합니다. 해당 파일과 빌드 캐시는 Git에 포함하지 않습니다.

생성 APK:

- `burn-in-fixed/app/build/outputs/apk/debug/app-debug.apk`
- `burn-in-camera/app/build/outputs/apk/debug/app-debug.apk`

디버그 APK는 연구용 설치 산출물입니다. 배포용 서명·스토어 출시 준비를 의미하지 않습니다.

## 기기 준비

1. Target 앱을 대상 기기에, Scanner 앱을 별도 카메라 기기에 설치합니다.
2. 두 기기를 신뢰 가능한 동일 Wi-Fi에 연결합니다. TCP 8899를 사용합니다.
3. 대상 앱에서 **조정설비**를 선택하고 IP를 확인합니다.
4. 자동 밝기·색온도 필터·절전 기능을 끄고 밝기와 화면 방향을 고정합니다. 실험 기록에 밝기 설정과 색상 모드를 남깁니다.
5. 무광 검정 내부의 차광 지그에 화면과 카메라를 고정합니다. 화면 전체와 마커가 보이도록 배치하고 화면 표면을 닦습니다.
6. Scanner 카메라 권한을 허용하고 조정설비 IP로 연결합니다. 대상 앱은 패턴 표시를 위해 전면에 둡니다.

## 측정과 평가

**측정 시작**을 누르면 노출 고정, 기준·마커·검정·회색·RGB 촬영, 보정맵 생성, 반복 측정, 최종 평가가 진행됩니다. 지원 기기에서는 다중 카메라를 선택할 수 있지만 구성 실패 시 단일 카메라로 전환될 수 있습니다.

주사율 입력은 노출 양자화의 기준입니다. 패널 PWM 주파수와 주사율이 항상 같다는 뜻은 아니며, 주사율에 맞춘 노출만으로 모든 밴딩 제거를 보장하지 않습니다.

보정 전후 토글로 비교합니다. 일반 앱 화면에는 Target의 **오버레이 켜기**와 별도의 오버레이 권한이 필요합니다. 오버레이는 RGB별 보정이 아닌 검정 알파 감쇠입니다. 화면 회전 시에는 오버레이가 중지됩니다.

발열·밝기 변화·기기 이동·측정 오류가 발생하면 실험을 중단하고 보정 및 오버레이 상태를 확인합니다. 현재 실패 복구는 완전하지 않으며 마지막 후보가 남을 수 있습니다. 자동 합격 표시는 프로그램 내부 기준 통과를 의미하며 공인 측정이나 육안 비가시성을 보증하지 않습니다.

## 색감 맞추기 (A급 기준 기기)

번인 보정 대상 기기의 색감을 상태 좋은 기준 기기(A급)에 맞춥니다. 번인 보정과 별도로 저장되고 별도로 켜고 끕니다.

1. 기준 기기에 Target을 설치하고 **대조설비**, 보정 대상은 **조정설비**로 선택합니다. 같은 모델 패널을 권장합니다. 발광 스펙트럼이 다른 패널끼리는 카메라 값이 같아도 육안 색이 다를 수 있습니다(metamerism).
2. 두 기기의 밝기 슬라이더·색상 모드·블루라이트 필터를 같게 하고, 두 화면이 모두 프레임에 들어오도록 카메라를 고정한 뒤 주변광을 끕니다.
3. Scanner에서 두 기기를 연결하고 **색감 맞추기 (대조설비 기준)** → **색감 맞추기 시작**을 누릅니다. 측정 자체는 약 5~6분 걸립니다(예상치).
4. 진행: 이전 색 프로파일·화이트밸런스 제거 → 카메라 고정·화면 검출·노출 조정 → 패치 45개를 두 기기에서 번갈아 촬영 → 17³ 3D LUT 생성·전송 → 회색 계조 closed-loop 보정 2회 → held-out 검증색으로 보정 전/후 근사 ΔE00 보고 → drift 확인 → 리포트(`report.json`, `color_lut.bin`) 저장.
5. Scanner의 **색감 보정 ON/OFF** 또는 Target 홈의 **색감 보정 켜기/끄기**로 비교합니다.

한계: 색 프로파일은 Target 앱 자체 렌더링에만 적용됩니다. 시스템 오버레이는 검정 알파 감쇠만 가능해 색을 바꿀 수 없습니다. 리포트의 `experimentalGlobalMatrix.adbCommand`는 LUT를 3×3 행렬로 근사한 SurfaceFlinger 명령이며 자동 실행하지 않습니다. 적용 공간과 행렬 순서는 기기별 검증이 필요합니다. 두 기기 위치의 비네팅 차이 때문에 절대 밝기가 아니라 상대 밝기(기준 흰색을 조정 기기가 낼 수 있는 최대 배율)로 맞춥니다.

## 결과 수집

Scanner 앱 전용 외부 저장소에 세션이 생성됩니다.

```text
Android/data/com.burnin.scanner/files/sessions/<timestamp>/
  report.json
  correction_alpha_<width>x<height>.png
  correction_rgb_<width>x<height>.png   # RGB 맵 생성 시
  ...                                  # 히트맵, 교차 검증 이미지 등
```

Android 버전에 따라 파일 관리자 접근이 제한될 수 있습니다. 허가된 개발 기기의 ADB 또는 Android Studio 파일 탐색 기능으로 수집합니다. 공유하기 전에 이미지·로그·기기 정보에 개인정보가 포함되었는지 확인합니다. 모델 다운로드에는 네트워크가 필요할 수 있으며 외부 SDK 조건은 [의존성 안내](../THIRD_PARTY_NOTICES.md)를 확인하세요.
