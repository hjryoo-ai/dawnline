# =============================================================================
# Dawnline — 로컬 스택 조작 래퍼 (DESIGN.md §14, CLAUDE.md "명령어")
#
# 이 Makefile 은 docker compose 와 gradle 을 감싸는 얇은 래퍼다.
# 인프라 이미지 태그·포트·비밀번호는 전부 deploy/compose/.env 에 있다.
#
#   make help          사용 가능한 타깃
#   make up            전체 스택 (인프라 + 관측성 + 서비스 5개)
#   make down          컨테이너만 내린다 (데이터 볼륨은 유지)
#
# 주의: 데이터를 지우는 타깃은 `clean-volumes` 하나뿐이고 확인을 묻는다
#       (CLAUDE.md: 데이터 삭제 명령을 사용자 확인 없이 실행 금지).
# =============================================================================

SHELL := /bin/bash

COMPOSE_DIR   := deploy/compose
COMPOSE_FILE  := $(COMPOSE_DIR)/docker-compose.yml
ENV_FILE      := $(COMPOSE_DIR)/.env
ENV_EXAMPLE   := $(COMPOSE_DIR)/.env.example

COMPOSE_LEAN_FILE := $(COMPOSE_DIR)/docker-compose.lean.yml

COMPOSE       := docker compose -f $(COMPOSE_FILE) --env-file $(ENV_FILE)
COMPOSE_ALL   := $(COMPOSE) --profile app --profile obs
COMPOSE_APP   := $(COMPOSE) --profile app
COMPOSE_OBS   := $(COMPOSE) --profile obs
# lean 은 관측성 스택이 없다. 오버레이로 OTLP 내보내기를 꺼서
# otel-collector 미존재로 인한 ERROR 폭주를 막는다 (docker-compose.lean.yml 주석 참고).
COMPOSE_LEANP := docker compose -f $(COMPOSE_FILE) -f $(COMPOSE_LEAN_FILE) --env-file $(ENV_FILE) --profile app

SERVICES      := order-service fulfillment-service dispatch-service tracking-service ops-api

# `make logs SERVICE=dispatch-service` 처럼 좁힐 수 있다.
SERVICE       ?=

.DEFAULT_GOAL := help
.PHONY: help env images check-images up up-infra up-lean down restart ps logs wait urls \
        topics psql redis-cli config demo peak chaos-kafka clean-volumes \
        k6-orders k6-rate-limit

# -----------------------------------------------------------------------------
help:
	@printf '\nDawnline 로컬 스택\n\n'
	@printf '  \033[1m기동/정지\033[0m\n'
	@printf '    make up             전체 스택 (인프라 + 관측성 + 서비스 5개)\n'
	@printf '    make up-infra       인프라만 (postgres, kafka, kafka-init, redis)\n'
	@printf '    make up-lean        인프라 + 서비스 (관측성 스택 제외, 저사양용)\n'
	@printf '    make down           컨테이너 정지·삭제 (볼륨은 그대로 둔다)\n'
	@printf '    make restart        down 후 up\n'
	@printf '    make clean-volumes  볼륨까지 삭제 (확인을 묻는다 — 데이터가 사라진다)\n\n'
	@printf '  \033[1m빌드\033[0m\n'
	@printf '    make images         ./gradlew bootBuildImage — 서비스 이미지 5개 생성\n\n'
	@printf '  \033[1m확인\033[0m\n'
	@printf '    make ps             컨테이너 상태\n'
	@printf '    make wait           5개 서비스 /actuator/health/readiness 200 대기\n'
	@printf '    make urls           접속 URL 목록\n'
	@printf '    make topics         Kafka 토픽 목록\n'
	@printf '    make logs [SERVICE=dispatch-service]\n'
	@printf '    make config         compose 파일 문법·변수 치환 검증\n'
	@printf '    make psql / make redis-cli\n\n'
	@printf '  \033[1m부하·계약 스크립트\033[0m (tools/k6/README.md)\n'
	@printf '    make k6-orders      500 rps 60초 부하 → summary.md         [Phase 1]\n'
	@printf '    make k6-rate-limit  레이트 리밋 계약 검증 (통과/실패)      [Phase 1]\n\n'
	@printf '  \033[1m시나리오\033[0m (아직 미구현 — 해당 Phase 에서 채운다)\n'
	@printf '    make demo           시드 + smoke 시나리오        [Phase 1~2]\n'
	@printf '    make peak           피크 시나리오                [Phase 7]\n'
	@printf '    make chaos-kafka    Kafka 중단→복구 검증          [Phase 7]\n\n'

# -----------------------------------------------------------------------------
# .env 준비 — 이미 있으면 절대 덮어쓰지 않는다.
env:
	@if [ ! -f $(ENV_FILE) ]; then \
	cp $(ENV_EXAMPLE) $(ENV_FILE); \
	echo "생성: $(ENV_FILE) (원본 $(ENV_EXAMPLE)). 필요하면 포트·비밀번호를 고쳐라."; \
	else \
	: ; \
	fi

# -----------------------------------------------------------------------------
# 서비스 이미지 (Buildpacks, ADR-013). Docker 데몬이 떠 있어야 한다.
images:
	@echo "==> ./gradlew bootBuildImage (서비스 5개, 첫 실행은 빌더 이미지 내려받느라 오래 걸린다)"
	./gradlew bootBuildImage

check-images: env
	@set -a; . $(ENV_FILE); set +a; \
	missing=""; \
	for s in $(SERVICES); do \
	img="dawnline/$$s:$$DAWNLINE_VERSION"; \
	docker image inspect "$$img" >/dev/null 2>&1 || missing="$$missing $$img"; \
	done; \
	if [ -n "$$missing" ]; then \
	echo ""; \
	echo "서비스 이미지가 없다:$$missing"; \
	echo "먼저 'make images' 를 실행해라 (./gradlew bootBuildImage)."; \
	echo "인프라만 띄우려면 'make up-infra' 를 쓴다."; \
	echo ""; \
	exit 1; \
	fi

# -----------------------------------------------------------------------------
up: env check-images
	$(COMPOSE_ALL) up -d
	@$(MAKE) --no-print-directory wait
	@$(MAKE) --no-print-directory urls

up-infra: env
	$(COMPOSE) up -d
	@echo "인프라 기동 완료. 토픽 확인: make topics"

up-lean: env check-images
	$(COMPOSE_LEANP) up -d
	@$(MAKE) --no-print-directory wait
	@echo "lean 모드: OTLP 내보내기가 꺼져 있다(관측성 스택 없음). Grafana/Tempo 가 필요하면 make up."

# 볼륨은 지우지 않는다. 데이터까지 지우려면 clean-volumes.
down:
	$(COMPOSE_ALL) down --remove-orphans

restart:
	@$(MAKE) --no-print-directory down
	@$(MAKE) --no-print-directory up

ps:
	$(COMPOSE_ALL) ps

logs:
	@if [ -n "$(SERVICE)" ]; then \
	$(COMPOSE_ALL) logs -f --tail=100 $(SERVICE); \
	else \
	$(COMPOSE_ALL) logs -f --tail=50; \
	fi

# compose 파일 문법 + 변수 치환 검증 (CI 에서도 쓴다)
config: env
	@$(COMPOSE_ALL) config --quiet && echo "compose 설정 OK: $(COMPOSE_FILE)"

# -----------------------------------------------------------------------------
# 레디니스 대기 (DESIGN.md §8.6)
#   Paketo tiny 런 이미지에는 셸도 curl 도 없어서 컨테이너 내부 healthcheck 를
#   걸 수 없다. 그래서 호스트에서 폴링한다.
wait: env
	@set -a; . $(ENV_FILE); set +a; \
	all="order-service:$$ORDER_SERVICE_PORT fulfillment-service:$$FULFILLMENT_SERVICE_PORT dispatch-service:$$DISPATCH_SERVICE_PORT tracking-service:$$TRACKING_SERVICE_PORT ops-api:$$OPS_API_PORT"; \
	echo "레디니스 대기 (/actuator/health/readiness, 최대 120초)"; \
	i=0; \
	while [ $$i -lt 60 ]; do \
		notready=0; \
		for pair in $$all; do \
			curl -sf --max-time 2 -o /dev/null "http://localhost:$${pair##*:}/actuator/health/readiness" || notready=1; \
		done; \
		[ $$notready -eq 0 ] && break; \
		i=$$((i+1)); sleep 2; \
	done; \
	fail=0; \
	for pair in $$all; do \
		svc=$${pair%%:*}; port=$${pair##*:}; \
		url="http://localhost:$$port/actuator/health/readiness"; \
		if curl -sf --max-time 2 -o /dev/null "$$url"; then st=READY; else st=TIMEOUT; fail=1; fi; \
		printf '  %-22s %-58s %s\n' "$$svc" "$$url" "$$st"; \
	done; \
	exit $$fail

urls: env
	@set -a; . $(ENV_FILE); set +a; \
	echo ""; \
	echo "  ops-api             http://localhost:$$OPS_API_PORT"; \
	echo "  order-service       http://localhost:$$ORDER_SERVICE_PORT"; \
	echo "  fulfillment-service http://localhost:$$FULFILLMENT_SERVICE_PORT"; \
	echo "  dispatch-service    http://localhost:$$DISPATCH_SERVICE_PORT"; \
	echo "  tracking-service    http://localhost:$$TRACKING_SERVICE_PORT"; \
	echo "  Swagger UI          http://localhost:<서비스포트>/swagger-ui.html"; \
	echo "  Grafana             http://localhost:$$GRAFANA_PORT  ($$GRAFANA_ADMIN_USER / $$GRAFANA_ADMIN_PASSWORD)"; \
	echo "  Prometheus          http://localhost:$$PROMETHEUS_PORT"; \
	echo "  Tempo               http://localhost:$$TEMPO_HTTP_PORT"; \
	echo "  Kafka (호스트)      localhost:$$KAFKA_EXTERNAL_PORT"; \
	echo "  PostgreSQL          localhost:$$POSTGRES_PORT"; \
	echo "  Redis               localhost:$$REDIS_PORT"; \
	echo ""

# -----------------------------------------------------------------------------
topics:
	$(COMPOSE) exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

psql: env
	@set -a; . $(ENV_FILE); set +a; \
	$(COMPOSE) exec -e PGPASSWORD="$$POSTGRES_SUPERUSER_PASSWORD" postgres \
	psql -U "$$POSTGRES_SUPERUSER" -d "$$POSTGRES_DB"

redis-cli:
	$(COMPOSE) exec redis redis-cli

# -----------------------------------------------------------------------------
# k6 (tools/k6). 로컬에 k6 가 있으면 그것을 쓰고, 없으면 컨테이너로 돌린다.
#
# 컨테이너에서 돌 때 BASE_URL 의 기본값이 달라진다 — 컨테이너 안의 localhost 는
# 서비스가 아니라 그 컨테이너 자신이다. host.docker.internal 로 호스트를 가리킨다.
# (이 한 줄이 없으면 "connection refused" 만 보고 서비스가 죽은 줄 안다.)
K6_LOCAL  := $(shell command -v k6 2>/dev/null)
K6_OUT    ?= .
BASE_URL  ?=

define run_k6
	@mkdir -p $(K6_OUT)
	@if [ -n "$(K6_LOCAL)" ]; then \
	cd $(K6_OUT) && k6 run $(if $(BASE_URL),-e BASE_URL=$(BASE_URL),) $(CURDIR)/tools/k6/$(1); \
	else \
	echo "로컬에 k6 가 없다 → grafana/k6 컨테이너로 실행한다"; \
	docker run --rm -i \
	-v "$(CURDIR)/tools/k6:/scripts:ro" -v "$(CURDIR)/$(K6_OUT):/out" -w /out \
	--add-host=host.docker.internal:host-gateway \
	grafana/k6:latest run \
	-e BASE_URL=$(if $(BASE_URL),$(BASE_URL),http://host.docker.internal:8081) \
	/scripts/$(1); \
	fi
endef

k6-orders:
	$(call run_k6,orders.js)

k6-rate-limit:
	$(call run_k6,rate-limit.js)

# -----------------------------------------------------------------------------
# 아직 구현되지 않은 시나리오 타깃.
# 성공한 척하지 않는다 — 명확히 실패해서 스크립트가 오인하지 않게 한다.
demo:
	@echo ""
	@echo "make demo 는 아직 구현되지 않았다."
	@echo "  필요한 것: sim-runner 의 seed / smoke 시나리오 (IMPLEMENTATION_PLAN.md Phase 1~2)"
	@echo "  지금 할 수 있는 것: make up-infra && make topics"
	@echo ""
	@exit 2

peak:
	@echo ""
	@echo "make peak 는 아직 구현되지 않았다."
	@echo "  필요한 것: sim-runner 의 peak-day 시나리오 (IMPLEMENTATION_PLAN.md Phase 7)"
	@echo ""
	@exit 2

chaos-kafka:
	@echo ""
	@echo "make chaos-kafka 는 아직 구현되지 않았다."
	@echo "  필요한 것: 카오스 스크립트 + 검증 SQL (IMPLEMENTATION_PLAN.md Phase 7)"
	@echo "  Phase 7 에서 이 타깃은 kafka 중단 → 복구 → outbox 미발행 0건 검증까지 수행한다."
	@echo ""
	@exit 2

# -----------------------------------------------------------------------------
# 데이터까지 지운다. 반드시 확인을 묻는다 (CLAUDE.md).
clean-volumes:
	@echo "경고: PostgreSQL·Kafka·Redis·Grafana·Prometheus·Tempo 볼륨을 전부 삭제한다."
	@echo "      주문·이벤트·대시보드 상태가 모두 사라진다. 되돌릴 수 없다."
	@read -r -p "정말 삭제하려면 'delete' 를 입력해라: " ans; \
	if [ "$$ans" = "delete" ]; then \
	$(COMPOSE_ALL) down -v --remove-orphans; \
	echo "볼륨 삭제 완료."; \
	else \
	echo "취소했다. 아무것도 지우지 않았다."; \
	fi
