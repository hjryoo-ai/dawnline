# ops-web — Buildpacks 가 아닌 유일한 이미지 (ADR-057 결정 2). 빌드 문맥은 저장소 루트다:
# 타입을 커밋된 contracts/openapi/ops-api.yaml 에서 생성하기 때문이다(ADR-056).
#   docker build -f apps/ops-web/Dockerfile --build-arg NODE_IMAGE=… --build-arg NGINX_IMAGE=… .
# 두 이미지의 판은 deploy/compose/.env.example 에 고정한다 — 기본값을 두지 않는다(두 곳에 적힌 판은 갈라진다).
ARG NODE_IMAGE
ARG NGINX_IMAGE

FROM ${NODE_IMAGE} AS build
WORKDIR /w
COPY contracts/openapi/ops-api.yaml contracts/openapi/ops-api.yaml
COPY apps/ops-web/package.json apps/ops-web/package-lock.json apps/ops-web/
RUN cd apps/ops-web && npm ci --no-audit --no-fund
COPY apps/ops-web/ apps/ops-web/
# prebuild 가 생성하고, build 가 tsc(계약 픽스처 포함) 뒤에 vite build 를 한다.
RUN cd apps/ops-web && npm run build

FROM ${NGINX_IMAGE}
COPY apps/ops-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /w/apps/ops-web/dist /usr/share/nginx/html
