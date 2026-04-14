# 🏟️ AndTranslate (Platinum-V5)

> 인공지능 기반 실시간 일본어 하이재킹 번역 시스템입니다. 클린 아키텍처와 멀티 모듈 설계를 통해 높은 확장성과 성능을 보장합니다.

## 🏗️ Project Architecture (Clean Structure)

프로젝트의 대문(Root) 구조는 역할에 따라 엄격하게 분리되어 있습니다.

```text
AndTranslate/
├── 📱 apps/                  # 프론트엔드 (Android Application)
│   └── android/              # 메인 앱 모듈 (UI, Fragments, Activities)
│       └── src/main/kotlin/com/viitor/ui/
│           ├── tab1_browser/ # [1번탭] 웹뷰 하이재킹 & 스텔스 엔진
│           ├── tab2_script/  # [2번탭] 스크립트 관리 및 분석
│           ├── tab3_words/   # [3번탭] 개인 단어장 & 학습 로직
│           ├── tab4_lens/    # [4번탭] 실시간 카메라/이미지 OCR 스캔
│           └── overlay/      # 최상단 자막 투사 및 플로팅 컨트롤러
│
├── ⚙️ libs/                  # 백엔드 코어 (Independent Logic Modules)
│   ├── translation/          # C++ 기반 고성능 번역 엔진 (CTranslate2)
│   ├── stt-engine/           # 실시간 음성 인식 (Whisper/STT) 처리
...
```
*(Remainder of previous README content)*
