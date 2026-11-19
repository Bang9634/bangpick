# 프론트엔드

React + TypeScript + Vite 프론트엔드 프로젝트입니다. 현재 구현 범위는 [개발 안내](../docs/development.md#현재-구현-상태)를 참고합니다.

```sh
npm ci
npm run dev
npm run lint
npm run build
```

이 디렉토리에서 실행합니다. 권장 Node.js는 루트 `.node-version`을 따릅니다. 개발 주소는 `http://localhost:8088`이며 `/api` 요청은 로컬 백엔드 8080 포트로 프록시합니다. Compose 프론트도 기본 포트로 8088을 사용하므로, Vite로 전환할 때는 저장소 루트에서 `docker compose stop frontend`를 먼저 실행합니다. 개발 서버에 별도 프론트 환경변수는 필요하지 않습니다. 브라우저에 노출되는 `VITE_` 변수에는 비밀정보를 넣지 않습니다.

## 개발용 인증 테스트

Vite 개발 서버에서 `http://localhost:8088/_dev`의 **인증 테스트**를 선택하거나 `http://localhost:8088/_dev/auth`로 바로 접속합니다. 실제 Google 로그인을 검증하려면 [백엔드 로그인 설정](../backend/README.md#google-로그인-개발-환경)을 적용하고 `BANGPICK_WEB_ORIGIN`에 설정한 주소로 화면을 엽니다. 같은 주소와 탭에서 로그인을 시작하면 인증 테스트 화면으로 돌아옵니다.

1. 비로그인 상태에서 **접근 확인**을 눌러 `GET /api/admin/access`가 `401`을 반환하는지 확인합니다.
2. **Google로 로그인** 후 회원 ID, 표시 이름, 역할을 확인합니다. **접근 확인**을 실행하면 일반 회원은 `403`, 관리자는 `204`를 받습니다. 테스트 계정의 역할이 바뀌면 **회원 정보 다시 조회**를 누릅니다. [첫 관리자 지정 절차](../backend/README.md#첫-관리자-지정)
3. **로그아웃** 후 회원 정보가 사라지고, **접근 확인**에서 다시 `401`이 표시되는지 확인합니다.

`npm run build` 결과에는 개발 페이지 모듈이 포함되지 않습니다. `npm run preview`에서 `/_dev`와 `/_dev/auth`를 직접 열어 일반 앱 화면이 표시되는지 확인할 수 있습니다.

## 영상 전송과 PLY 미리보기

기본 화면에는 프로젝트 소개와 [Google 로그인 설정](../backend/README.md#google-로그인-개발-환경)에 따른 로그인·로그아웃 조작이 표시됩니다. 영상 전송 API와 뷰어는 매물 화면에서 호출할 수 있도록 `src/media/`에 두었습니다. 실제 전송에는 [백엔드의 OCI 전송 기능](../backend/README.md#oci-파일-전송-개발-환경)이 켜져 있어야 합니다. 전송 함수의 인증 인자에 `null`을 전달하면 로그인 세션을 사용하며, 로컬 개발 토큰을 사용할 때는 토큰 문자열을 전달합니다.

- `uploadSourceVideo`는 임시 URL로 영상을 올리고 작업 ID를 반환합니다. `confirmVideoUpload`로 저장 확인을 요청하며, 이 단계가 실패하면 파일을 다시 올리지 않고 확인만 재시도할 수 있습니다.
- `getMediaJob`으로 작업 상태를 조회합니다. 결과가 준비되면 `requestResultPreview`가 반환한 전송 정보를 `ResultViewer`에 전달해 PLY를 내려받고 표시합니다.

파일 본문은 브라우저와 OCI가 직접 주고받고, 백엔드 접근 토큰은 OCI에 보내지 않습니다. 실제 API 형식은 [영상·PLY 전송 계약](../contracts/README.md#영상ply-전송)을 참고하세요.
