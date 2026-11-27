#!/usr/bin/env bash
# =============================================================================
#  Pedidos360 — DELETE /api/alertas en el API Gateway (reset del antifraude)
#
#  La tienda tiene botones de demo "Ráfaga" y "Resetear antifraude" en el
#  checkout (pasarela de prueba). El reset llama a DELETE /api/alertas de
#  ms-pago, que borra bloqueos e intentos. Sólo para la demo, pero detrás del
#  authorizer de Cognito: sin sesión, 401.
#
#  Idempotente: si el método ya existe, lo reemplaza. No toca las demás rutas
#  (nunca re-correr 14 sobre las rutas de 16; ver LEEME-DESPLIEGUE).
#      bash despliegue/21-reset-antifraude-gateway.sh
# =============================================================================
set -euo pipefail
export AWS_PAGER=""
REGION="${AWS_REGION:-us-east-1}"
API_ID="${API_ID:-vih5lem02j}"
ETAPA="${ETAPA:-desarrollo}"
HOST="${HOST_PUBLICO:-18.211.30.140}"

RES=$(aws apigateway get-resources --region "$REGION" --rest-api-id "$API_ID" --limit 500 \
  --query "items[?path=='/api/alertas'].id" --output text)
AUTH=$(aws apigateway get-authorizers --region "$REGION" --rest-api-id "$API_ID" \
  --query "items[?type=='COGNITO_USER_POOLS'].id | [0]" --output text)
echo ">> recurso /api/alertas=$RES authorizer=$AUTH"

aws apigateway delete-method --region "$REGION" --rest-api-id "$API_ID" --resource-id "$RES" \
  --http-method DELETE >/dev/null 2>&1 || true
aws apigateway put-method --region "$REGION" --rest-api-id "$API_ID" --resource-id "$RES" \
  --http-method DELETE --authorization-type COGNITO_USER_POOLS --authorizer-id "$AUTH" \
  --request-parameters "method.request.header.Origin=false" >/dev/null
aws apigateway put-integration --region "$REGION" --rest-api-id "$API_ID" --resource-id "$RES" \
  --http-method DELETE --type HTTP --integration-http-method DELETE \
  --uri "http://${HOST}:8083/api/alertas" \
  --request-parameters '{"integration.request.header.Origin":"method.request.header.Origin","integration.request.header.X-Usuario-Id":"context.authorizer.claims.email"}' >/dev/null

for code in 200 403 500; do
  aws apigateway put-method-response --region "$REGION" --rest-api-id "$API_ID" --resource-id "$RES" \
    --http-method DELETE --status-code "$code" \
    --response-parameters "method.response.header.Access-Control-Allow-Origin=true,method.response.header.Access-Control-Allow-Credentials=true" >/dev/null
  patron=$([ "$code" = 200 ] && echo "" || echo "$code")
  aws apigateway put-integration-response --region "$REGION" --rest-api-id "$API_ID" --resource-id "$RES" \
    --http-method DELETE --status-code "$code" ${patron:+--selection-pattern "$patron"} \
    --response-parameters '{"method.response.header.Access-Control-Allow-Origin":"integration.response.header.Access-Control-Allow-Origin","method.response.header.Access-Control-Allow-Credentials":"integration.response.header.Access-Control-Allow-Credentials"}' >/dev/null
done

aws apigateway create-deployment --region "$REGION" --rest-api-id "$API_ID" --stage-name "$ETAPA" \
  --description "DELETE /api/alertas (reset antifraude, demo)" --query id --output text
echo ">> listo: DELETE https://${API_ID}.execute-api.${REGION}.amazonaws.com/${ETAPA}/api/alertas (con ID token)"
