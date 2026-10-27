# make up | run | benchmark | traces | test | lint | down   (make help lists all)
SHELL := /bin/bash
MVN := ./mvnw -B -ntp
PROVIDER_PORT ?= 18103
PRICE_PORT ?= 18104

-include .env
export

.PHONY: help env build up run benchmark traces serve health test test-unit lint format audit docker-build down clean

help: ## list the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort

env:
	@[ -f .env ] || { cp .env.example .env; echo "created .env from .env.example"; }

build: ## package all modules (the load generator is run from its jar)
	$(MVN) -DskipTests package

up: env ## build both images, start the provider, the price service and the Grafana stack, wait until healthy
	docker compose up -d --build --wait

run: build ## the lesson's scenario step by step against the running stack (make up first)
	scripts/scenario.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT)

benchmark: build ## the before/after table: every provider mode, 100 requests a second (make up first, about 6 minutes)
	scripts/benchmark.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT)

traces: ## capture a retried trace and a stale trace from Tempo into docs/evidence (make up first)
	scripts/capture-traces.sh http://localhost:$(PRICE_PORT) http://localhost:$(PROVIDER_PORT) http://localhost:$(TEMPO_PORT)

serve: env ## run the provider and the price service from source on the host (two terminals: see README)
	@echo "terminal 1: PORT=$(PROVIDER_PORT) java -jar provider/target/provider-0.1.0.jar"
	@echo "terminal 2: PRICE_PROVIDER_BASE_URL=http://localhost:$(PROVIDER_PORT) SERVER_PORT=$(PRICE_PORT) $(MVN) -pl price-service spring-boot:run"

health: ## exit 0 when the price service and the provider report healthy
	curl -fsS http://localhost:$(PRICE_PORT)/actuator/health/readiness | jq -e '.status == "UP"' >/dev/null && echo "price-service UP"
	curl -fsS http://localhost:$(PROVIDER_PORT)/health >/dev/null && echo "provider UP"

test: ## every module's unit, slice and integration tests, then the coverage gates
	$(MVN) verify

test-unit: ## unit and slice tests only
	$(MVN) test

lint: ## Spotless format check and Checkstyle on every module
	$(MVN) spotless:check checkstyle:check

format: ## apply the Spotless formatting
	$(MVN) spotless:apply

audit: ## known-vulnerability scan of the dependencies (OSV-Scanner in a container)
	scripts/audit.sh

docker-build: ## build both container images only
	docker build -f provider/Dockerfile -t spring-resilient-client-provider:dev .
	docker build -f price-service/Dockerfile -t spring-resilient-client-price:dev .

down: ## stop the containers
	docker compose down -v --remove-orphans

clean: down
	$(MVN) clean
