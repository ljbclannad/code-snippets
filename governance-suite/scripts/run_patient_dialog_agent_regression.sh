#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EVAL_DIR="$ROOT_DIR/docs/patient-dialog-agent/evals"

echo "[regression] checking evaluation assets"
for file in \
  normal-patient-dialog.jsonl \
  agent-trigger-evals.jsonl \
  patient-safety-escalation-evals.jsonl \
  complex-follow-up-analysis-evals.jsonl \
  degrade-timeout-evals.jsonl
do
  if [[ ! -f "$EVAL_DIR/$file" ]]; then
    echo "[regression] missing eval file: $file" >&2
    exit 1
  fi
done

echo "[regression] running ai-chat-service targeted tests"
mvn -pl ai-chat-service \
  -Dtest=AiChatMetricsTest,AiChatServiceConcurrencyTest,DoctorAssistantTaskRequestFactoryTest,TaskTriggerRouterTest \
  test

echo "[regression] running doctor-assistant-agent targeted tests"
mvn -pl doctor-assistant-agent \
  -Dtest=AgentMetricsTest,PatientTaskAgentServiceTest,PatientTaskControllerTest,MedicalSafetyServiceTest \
  test

echo "[regression] completed"
