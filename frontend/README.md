# 프론트엔드

React + TypeScript + Vite 프론트엔드 프로젝트입니다. 현재 구현 범위는 [개발 안내](../docs/development.md#현재-구현-상태)를 참고합니다.

```sh
npm ci
npm run dev
npm run lint
npm run build
```

이 디렉토리에서 실행합니다. 권장 Node.js는 루트 `.node-version`을 따릅니다. 개발 주소는 `http://localhost:5173`이고 `/api` 요청은 로컬 백엔드 8080 포트로 프록시합니다. 별도 프론트 환경변수는 아직 없습니다. 추후 `VITE_` 변수는 브라우저에 노출되므로 비밀정보를 넣지 않습니다.
