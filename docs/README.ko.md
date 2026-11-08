[English](../README.md) | [简体中文](README.zh-CN.md) | [繁體中文（港澳台）](README.zh-Hant.md) | [日本語](README.ja.md) | [한국어](README.ko.md)

# Reels TV 2.12

리모컨으로 Instagram Reels를 시청하기 편한 Android TV 인터페이스입니다. Reels TV는 WebView에서 Instagram 공식 웹사이트를 열고, TV에 맞춘 플레이어와 간단한 아이콘 기반 조작 막대로 재생 가능한 동영상을 보여줍니다.

Reels 시청이 핵심 기능입니다. Reels만 시청하려면 표준판을, 동영상을 Xunlei 클라우드 드라이브에도 저장하려면 선택 사항인 Xunlei Cloud Drive 에디션을 선택하세요. 표준판에는 Xunlei 버튼이 없으며 Xunlei 계정도 필요하지 않습니다. 이 선택 기능에 Xunlei를 선택한 이유는 필요한 용량을 충분히 제공하는 무료 클라우드 드라이브를 우연히 찾았기 때문입니다. 사용 가능한 용량과 약관은 변경될 수 있으며 Reels TV 사용에 Xunlei는 필요하지 않습니다.

이 프로젝트는 독립 프로젝트로, Instagram, Meta 또는 Xunlei와 제휴하거나 이들의 보증을 받은 제품 또는 공식 제품이 아닙니다. Instagram 웹사이트와 사용 가능한 기능은 예고 없이 변경될 수 있습니다.

## 기능

- TV 리모컨으로 이전 또는 다음 Reel로 이동합니다.
- Instagram에서 해당 조작을 제공할 때 Reel에 좋아요를 누릅니다. 채워진 하트는 확인된 좋아요를 나타냅니다.
- 동영상을 왼쪽으로 회전하고 자동 확대와 원래 비율을 전환합니다. 각 Reel의 화면 설정은 기기에 저장됩니다.
- 필요할 때 리모컨으로 조작하는 포인터를 사용해 공식 웹페이지를 이용합니다.
- 앱 인터페이스는 영어, 일본어, 한국어, 중국어 번체 및 중국어 간체를 지원하며 기기 언어를 따릅니다. 그 밖의 언어는 영어로 표시됩니다. 문서 언어는 위의 링크에서 직접 선택할 수 있습니다. 타사 공식 웹페이지는 자체 언어로 표시될 수 있습니다.
- Instagram이 인증을 요청하거나 요청 빈도 제한 응답을 반환하면 자동 연결을 일시 중지합니다. 공식 페이지에서 계속할 시점은 사용자가 결정합니다.

특정 동영상, 조작 또는 계정 세션을 계속 사용할 수 있다고 보장하지 않습니다. 인증이나 계정 제한을 우회하는 수단으로 사용하지 마세요.

## 기기 요구 사항

- Android 8.0(API 26) 이상, Android TV 호환 리모컨 및 작동하는 WebView.
- `armeabi-v7a` 또는 `arm64-v8a`를 지원하는 ARM Android 기기. 호환성은 기기에 따라 다르며 2.12는 모든 기기 또는 아키텍처에서 검증되지 않았습니다.
- Instagram과 해당 미디어 서버에 대한 네트워크 연결. 선택 사항인 Xunlei Cloud Drive 에디션에서 동영상을 저장하려면 Xunlei 연결도 필요합니다. 메시지가 표시되면 공식 페이지에서 로그인하세요. 앱은 자체 WebView 세션을 사용하며 Instagram 공식 앱의 세션을 가져오지 않습니다.

선택 사항인 Instagram Remote Assistant 접근성 서비스는 별도의 Instagram 공식 앱을 조작하기 위한 것입니다. Reels TV 자체에는 이 서비스를 켤 필요가 없습니다. 서비스를 켜면 Android가 창 정보, 방향키 및 제스처 시뮬레이션 접근 권한을 부여합니다. 서비스는 Instagram 공식 앱이 활성 상태이고 키보드가 닫혀 있을 때만 키를 필터링합니다.

### 선택 사항: Xunlei Cloud Drive 에디션

Xunlei Cloud Drive 에디션의 클라우드 버튼은 Reels TV 안에서 Xunlei 공식 웹사이트를 엽니다. 별도의 연동 앱은 필요하지 않습니다. 저장하기 전에 해당 창에서 본인의 Xunlei 계정에 로그인하고 클라우드 드라이브의 최상위 디렉터리에 이름이 정확히 `Reels TV`인 폴더를 만드세요. 폴더가 없다면 직접 만든 후 다시 시도하세요. 앱 언어와 관계없이 공식 웹사이트는 중국어로 표시될 수 있습니다.

앱은 현재 Reel을 임시 동영상으로 준비해 Xunlei 업로드 페이지를 통해 전송합니다. 웹사이트에 업로드 완료가 표시되고 새로고침한 폴더에 파일이 나타난 후에만 성공을 알립니다. 이전에 로컬에 저장된 표시에는 의존하지 않습니다. 취소했거나 확인되지 않은 업로드는 성공으로 표시하지 않습니다. 별도 테스트 빌드에서 생성한 테스트 동영상을 Xunlei에 업로드했지만, 실제 Reel 다운로드나 모든 기기에서의 전체 과정을 검증한 것은 아닙니다. Xunlei 웹사이트, 로그인, 파일 목록 또는 업로드 화면이 변경되면 이 과정이 작동하지 않을 수 있습니다.

저장하려면 Instagram이 지원하는 미디어 호스트에서 직접 다운로드할 수 있고 재생 가능한 동영상이 필요합니다. 일부 Reels 또는 계정에서는 해당 동영상을 제공하지 않을 수 있습니다. 다운로드는 최대 512 MiB이며 충분한 여유 공간이 필요합니다. 계정 접근, 네트워크 상태, 웹사이트 변경 및 Xunlei 자체 제한 때문에 저장하지 못할 수도 있습니다. 저장할 권한이 있는 콘텐츠에만 이 기능을 사용하세요.

## 빌드, 서명 및 설치

JDK 17, Android SDK Platform 34 및 Build Tools 34.0.0이 필요합니다. `ANDROID_HOME`을 Android SDK 디렉터리로 설정하거나 Android Studio의 로컬 SDK 설정을 사용하세요. 포함된 Gradle wrapper는 처음 사용할 때 Gradle 8.9를 다운로드하고 체크섬을 검증합니다. Gradle은 Android 플러그인과 선언된 종속성도 다운로드합니다.

```sh
./gradlew assembleStandardRelease assembleXunleiRelease lintStandardRelease lintXunleiRelease
```

Windows에서는 대신 `gradlew.bat`를 사용하세요. 한 에디션을 선택하세요. 두 빌드 모두 **서명되지 않았으므로** 그대로 설치할 수 없습니다.

```text
build/outputs/apk/standard/release/InstagramRemote-standard-release-unsigned.apk
build/outputs/apk/xunlei/release/InstagramRemote-xunlei-release-unsigned.apk
```

예를 들어 표준판에 서명하려면 서명되지 않은 APK를 정렬한 다음 안전하게 관리하는 본인의 릴리스 키로 서명하세요. Xunlei Cloud Drive 에디션에 서명할 때는 해당 경로와 다른 출력 파일 이름으로 바꾸세요. 아래의 키 저장소 경로와 별칭을 바꾸세요. `zipalign`과 `apksigner`는 Android SDK의 Build Tools 디렉터리에 있습니다. 키 저장소와 비밀번호는 비공개로 유지하세요.

```sh
zipalign -p -f 4 build/outputs/apk/standard/release/InstagramRemote-standard-release-unsigned.apk /tmp/Reels-TV-2.12-aligned.apk
apksigner sign --ks /path/to/release-keystore --ks-key-alias YOUR_KEY_ALIAS --out Reels-TV-2.12.apk /tmp/Reels-TV-2.12-aligned.apk
apksigner verify --verbose --print-certs Reels-TV-2.12.apk
```

배포된 APK를 설치하기 전에 `apksigner verify --verbose --print-certs Reels-TV-2.12.apk`로 서명을 검증하고, 표시된 SHA-256 인증서 지문을 신뢰할 수 있는 독립된 경로로 얻은 배포자의 지문과 비교하세요. 검증 성공만으로는 APK에 서명한 사람이 누구인지 알 수 없습니다. 이 저장소에는 배포자 지문이 명시되어 있지 않습니다.

```sh
adb install -r Reels-TV-2.12.apk
```

두 에디션은 같은 패키지 이름을 사용합니다. 에디션 전환은 별도 플러그인 설치가 아니라 앱 업데이트입니다. 기존 로컬 데이터와 세션을 유지하려면 일치하는 서명 인증서와 Android가 허용하는 버전을 사용하세요. Android는 다른 키로 서명된 기존 설치를 업데이트하지 않습니다. 기존 앱을 제거하면 로컬 데이터와 WebView 세션이 삭제됩니다. 예상 서명 파일 이름이 서명된 APK의 포함 또는 배포를 의미하지는 않습니다.

## 리모컨 조작

| 조작 | 동작 |
| --- | --- |
| 위 / 아래 | 사이드바 버튼 선택 |
| 확인 | 선택한 버튼 실행 |
| 왼쪽 | Instagram 페이지로 들어가 포인터 사용 |
| 페이지 가장자리에서 오른쪽 | 사이드바로 돌아가기 |
| 재생 / 일시 중지 | 동영상 일시 중지 또는 재개 |
| 뒤로, 홈, 음량 | 일반적인 시스템 동작 |

페이지나 동영상을 불러오는 중이거나 Instagram에서 해당 동작을 제공하지 않으면 버튼을 사용하지 못할 수 있습니다. 자동 확대는 화면 일부를 자를 수 있으며 화면 버튼으로 원래 비율을 복원할 수 있습니다.

## 테스트

JavaScript 테스트에는 Node.js 18 이상이 필요하며 Instagram에 연결하지 않습니다.

```sh
node --test tests/*.test.cjs tests/*.test.mjs
```

`debug` 빌드는 별도 애플리케이션 ID인 `com.gathin.instagramremote.checks`를 사용하며 네트워크 권한을 제거합니다. 기기 측 안전 검사에는 테스트 앱 캐시에 직접 제공한 테스트 동영상도 필요합니다. 이 동영상은 저장소에 **포함되어 있지 않으므로** 이 저장소만으로는 기기 측 재생 테스트 전체를 재현할 수 없습니다. JavaScript 테스트와 로컬 기기 검사는 실제 Instagram 또는 Xunlei 웹사이트, 업로드 또는 개별 계정의 동작을 증명하지 않습니다.

## 개인정보 및 데이터

앱은 공식 페이지와 재생을 위해 Instagram 및 Facebook 미디어 호스트에 연결합니다. 선택 사항인 Xunlei Cloud Drive 에디션에서 클라우드 저장을 사용하면 현재 동영상을 Instagram 미디어 호스트에서 앱의 비공개 캐시로 다운로드한 다음 공식 웹사이트를 통해 Xunlei에 업로드합니다. 과정이 끝나거나 닫히면 임시 파일이 삭제되지만, 앱 프로세스가 중단되면 Android가 지울 때까지 캐시 데이터가 남을 수 있습니다. Xunlei는 업로드된 동영상을 수신하고 자체 약관과 개인정보 처리방침에 따라 처리합니다.

앱의 WebView는 자체 웹사이트 세션과 쿠키를 기기에 저장합니다. Instagram과 Xunlei 공식 페이지에서 직접 로그인하세요. 앱은 다른 앱에서 로그인 데이터를 가져오지 않으며 클라우드 저장 과정에서 사용자 인증 정보나 쿠키를 읽거나 복사하지 않습니다. 앱은 동영상별 회전 및 확대 설정을 로컬에 저장합니다. Android 백업은 사용 중지되어 있습니다. 이 저장소에는 계정 인증 정보, 쿠키, 개인 시청 기록, 비공개 네트워크 설정 또는 서명 키가 포함되어 있지 않습니다.

선택 사항인 접근성 서비스를 켜는 경우 위에 설명된 권한과 동작을 확인하세요. 콘텐츠 사용 가능 여부는 서비스와 계정의 접근 권한에 따라 달라집니다.

## 라이선스 및 저작자 표시

프로젝트 소스는 [MIT License](../LICENSE)에 따라 배포됩니다. 이 프로젝트는 [CleanYourTV](https://github.com/Gathin23/cleanyourtv)와 함께 개발되었으며 해당 저작권 고지를 유지합니다. Gradle wrapper, AndroidX 라이브러리 및 LibVLC는 각각 별도 라이선스가 적용되는 독립적인 타사 구성 요소입니다. [THIRD_PARTY.md](../THIRD_PARTY.md)를 참조하세요. 배포 APK의 라이선스 의무는 이 소스 저장소의 의무와 다를 수 있습니다.
