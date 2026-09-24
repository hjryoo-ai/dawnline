#!/usr/bin/env bash
# =============================================================================
# ops-api 운영자 토큰 발급 (DESIGN.md §5.5 · §10, ADR-052 결정 2)
#
#   make token ROLE=OPS_OPERATOR [ACTOR=demo-operator]
#   DAWNLINE_OPS_JWT_SECRET=… tools/ops-token/ops-token.sh OPS_VIEWER [actor]
#
# ops-api 는 토큰을 검증만 한다. 사용자 저장소가 설계에 없으므로 로그인 엔드포인트가 없고, 발급은 이
# 스크립트가 같은 로컬 시크릿으로 한다. 의존은 bash 와 openssl 뿐이다.
#
# 만료는 12시간이다 — 데모 토큰이 저장소나 스크린샷에 남았을 때의 반경. 줄이려면
# OPS_TOKEN_TTL_SECONDS 로 덮는다(늘리는 것은 막는다).
#
# 클레임: iss=dawnline-ops-token, sub=<actor>(감사 행의 actor), roles=[<ROLE>], iat, exp.
# ops-api 의 SecurityConfig 가 이 다섯을 본다 — 이름을 바꾸면 OpsTokenScriptTest 가 깨진다.
# =============================================================================
set -euo pipefail

readonly MAX_TTL_SECONDS=43200   # 12시간

role="${1:-}"
actor="${2:-demo-operator}"
ttl="${OPS_TOKEN_TTL_SECONDS:-$MAX_TTL_SECONDS}"

case "$role" in
  OPS_VIEWER|OPS_OPERATOR|ADMIN) ;;
  *) echo "ROLE 은 OPS_VIEWER · OPS_OPERATOR · ADMIN 중 하나다 (받은 값: '${role}')" >&2; exit 2 ;;
esac

# actor 는 감사 행의 actor(VARCHAR(64))이고 JSON 에 그대로 들어간다 — 이스케이프가 필요 없는 글자만 받는다.
if [[ ! "$actor" =~ ^[A-Za-z0-9._@-]{1,64}$ ]]; then
  echo "ACTOR 는 영숫자와 . _ @ - 로 된 1–64자다 (받은 값: '${actor}')" >&2
  exit 2
fi

if [[ ! "$ttl" =~ ^[0-9]+$ ]] || (( ttl < 1 || ttl > MAX_TTL_SECONDS )); then
  echo "OPS_TOKEN_TTL_SECONDS 는 1–${MAX_TTL_SECONDS} 초다 (받은 값: '${ttl}')" >&2
  exit 2
fi

secret="${DAWNLINE_OPS_JWT_SECRET:-}"
if (( $(printf '%s' "$secret" | wc -c) < 32 )); then
  echo "DAWNLINE_OPS_JWT_SECRET 가 없거나 32바이트보다 짧다 — 'make env' 가 만든 deploy/compose/.env 를 쓴다" >&2
  exit 2
fi

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

now="$(date +%s)"
header="$(printf '%s' '{"alg":"HS256","typ":"JWT"}' | b64url)"
payload="$(printf '{"iss":"dawnline-ops-token","sub":"%s","roles":["%s"],"iat":%d,"exp":%d}' \
  "$actor" "$role" "$now" "$(( now + ttl ))" | b64url)"
signature="$(printf '%s.%s' "$header" "$payload" | openssl dgst -binary -sha256 -hmac "$secret" | b64url)"

printf '%s.%s.%s\n' "$header" "$payload" "$signature"
