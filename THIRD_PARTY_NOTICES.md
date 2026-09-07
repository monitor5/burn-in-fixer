# 외부 의존성과 권리

이 저장소의 [자체 라이선스](LICENSE)는 프로젝트 고유 코드·문서에 적용됩니다. 외부 라이브러리·SDK·빌드 도구·모델의 권리를 다시 허락하거나 기존 조건을 변경하지 않습니다. 아래는 직접 사용 도구·의존성의 안내이며, 전이 의존성을 포함한 배포용 완전한 고지 목록은 아닙니다.

| 구성 | 선언 버전 또는 역할 | 확인할 원문 |
| --- | --- | --- |
| Kotlin | Scanner 2.2.0, Target 2.0.21 | [Kotlin LICENSE](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| kotlinx.coroutines Android | 1.8.1 | [프로젝트 라이선스](https://github.com/Kotlin/kotlinx.coroutines/blob/1.8.1/LICENSE.txt) |
| ML Kit GenAI Prompt | 1.0.0-beta2, Scanner | [ML Kit 이용 조건](https://developers.google.com/ml-kit/terms), [GenAI 추가 약관](https://developers.google.com/ml-kit/genai-terms), 해당 아티팩트의 고지 |
| Gemini Nano / AICore | 선택적 온디바이스 시각 판단, 별도 다운로드·프로비저닝 | [ML Kit GenAI 안내](https://developers.google.com/ml-kit/genai)와 기기에서 적용되는 모델·서비스 조건 |
| JUnit | 4.13.2, 테스트 전용 | [JUnit 라이선스](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt) |
| Android Gradle Plugin | 8.7.3, 빌드 도구 | 해당 배포물 라이선스·고지 |
| Android SDK | compileSdk 35, 빌드·플랫폼 API | [Android SDK 약관](https://developer.android.com/studio/terms) |
| Gradle | 별도 설치하는 빌드 도구 | [Gradle 라이선스](https://github.com/gradle/gradle/blob/master/LICENSE) |

정확한 해석에는 설치한 버전의 배포물과 원문 약관을 확인해야 합니다. 모델 가중치·제조사 펌웨어·서명 키는 저장소 배포 대상이 아닙니다. APK를 별도 배포하기 전에는 실제 패키지의 전이 의존성, 라이선스 및 필요한 고지를 수집해야 합니다.

Samsung, Galaxy, Android, Kotlin, Gemini 등 명칭은 각 권리자에게 속합니다. 명칭은 기술·호환 환경 식별에 사용하며 해당 권리자와의 제휴를 뜻하지 않습니다.

## ML Kit 데이터 처리와 교육 환경

Google 문서에 따르면 입력 이미지·텍스트와 생성 결과는 온디바이스에서 처리되며 서버로 전송되지 않습니다. SDK는 모델·호환성 업데이트를 위해 서버에 접속하고 성능·사용량 지표를 Google에 전송할 수 있습니다. 따라서 완전한 오프라인 앱 또는 외부 통신이 전혀 없는 앱으로 설명하지 않습니다. [ML Kit 개인정보 안내](https://developers.google.com/ml-kit/terms)

GenAI 추가 약관에는 18세 이상 사용 및 미성년자를 대상으로 하거나 미성년자가 접근할 가능성이 있는 앱에 대한 제한이 있습니다. 교육 목적 라이선스가 이 제한을 없애지 않습니다. 해당 교육 환경에서 배포하려면 GenAI 의존성과 실행 경로를 제거한 구성을 별도로 준비해야 하며, 현재 저장소는 그러한 교육용 변형을 제공하지 않습니다. [ML Kit GenAI 추가 약관](https://developers.google.com/ml-kit/genai-terms)
