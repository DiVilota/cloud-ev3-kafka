package com.kafka.pago.Service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.kafka.pago.DTO.TipoPasarela;
import com.kafka.pago.Exception.PasarelaNoDisponibleException;

import cl.transbank.common.IntegrationApiKeys;
import cl.transbank.webpay.webpayplus.WebpayPlus;
import lombok.extern.slf4j.Slf4j;

/**
 * Webpay Plus (SDK Transbank 6.x) en ambiente de INTEGRACIÓN. Toda llamada al
 * SDK queda encerrada acá: el resto del servicio se prueba con esta clase
 * mockeada, sin red.
 */
@Slf4j
@Component
public class PasarelaWebpay implements PasarelaPago {

    /** Código de comercio público de integración de Transbank (no es un secreto). */
    @Value("${app.webpay.codigo-comercio:597055555532}")
    private String codigoComercio;

    /** Vacío = la API key pública de integración que trae el SDK. */
    @Value("${app.webpay.api-key:}")
    private String apiKey;

    @Override
    public TipoPasarela tipo() {
        return TipoPasarela.WEBPAY;
    }

    private WebpayPlus.Transaction transaccion() {
        String llave = (apiKey == null || apiKey.isBlank()) ? IntegrationApiKeys.WEBPAY : apiKey;
        return WebpayPlus.Transaction.buildForIntegration(codigoComercio, llave);
    }

    @Override
    public ResultadoInicioPago iniciar(SolicitudCobro solicitud) {
        try {
            var respuesta = transaccion().create(solicitud.idOrden(), solicitud.idCliente(),
                    (double) solicitud.monto(), solicitud.returnUrl());
            log.info("Transacción Webpay creada: orden={} token={}", solicitud.idOrden(), respuesta.getToken());
            return new Redireccion(respuesta.getUrl(), respuesta.getToken());
        } catch (Exception e) {
            log.error("Error al crear la transacción en Transbank (orden={}): {}", solicitud.idOrden(), e.getMessage());
            throw new PasarelaNoDisponibleException("Error al iniciar pago en Webpay", e);
        }
    }

    /** Commit del token que devuelve Transbank al returnUrl. */
    public ConfirmacionWebpay confirmar(String tokenWs) {
        try {
            var r = transaccion().commit(tokenWs);
            String ultimos4 = r.getCardDetail() != null ? r.getCardDetail().getCardNumber() : null;
            return new ConfirmacionWebpay(r.getStatus(), r.getBuyOrder(), r.getSessionId(),
                    Math.round(r.getAmount()), r.getAuthorizationCode(), r.getTransactionDate(), ultimos4);
        } catch (Exception e) {
            log.error("Error al hacer commit en Transbank: {}", e.getMessage());
            throw new PasarelaNoDisponibleException("No se pudo confirmar el pago con Transbank", e);
        }
    }

    /**
     * Anula (refund) una transacción ya autorizada. Se usa cuando el commit
     * revela una tarjeta bloqueada por el antifraude. Si Transbank falla se
     * deja registrado y se sigue: el pago igual queda RECHAZADO del lado nuestro
     * y la anulación se puede reintentar a mano desde el portal de Transbank.
     */
    public boolean anular(String tokenWs, Long monto) {
        if (monto == null) {
            return false;
        }
        try {
            var r = transaccion().refund(tokenWs, (double) monto);
            log.info("Anulación Webpay: token={} tipo={} monto={}", tokenWs, r.getType(), monto);
            return true;
        } catch (Exception e) {
            log.error("No se pudo anular en Transbank (token={}): {}", tokenWs, e.getMessage());
            return false;
        }
    }

    /**
     * Lo que importa del commit. ultimos4 es lo que Transbank llama cardNumber:
     * ya viene recortado a 4 dígitos (o nulo si la transacción fue rechazada).
     */
    public record ConfirmacionWebpay(String estado, String idOrden, String idCliente, Long monto,
                                     String codigoAutorizacion, String fecha, String ultimos4) {
        /** Estado propio (no de Transbank): autorizado, pero anulado por el antifraude. */
        public static final String BLOQUEADA_ANTIFRAUDE = "BLOQUEADA_ANTIFRAUDE";

        public boolean bloqueadaPorAntifraude() {
            return BLOQUEADA_ANTIFRAUDE.equals(estado);
        }

        public boolean autorizado() {
            return "AUTHORIZED".equals(estado);
        }
    }
}
