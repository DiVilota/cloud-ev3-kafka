package com.kafka.pago.Controller;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.kafka.pago.DTO.EstadoResultadoPago;
import com.kafka.pago.DTO.OrigenPago;
import com.kafka.pago.DTO.PeticionPagoDTO;
import com.kafka.pago.Service.PasarelaWebpay.ConfirmacionWebpay;
import com.kafka.pago.Service.ServicioPagos;
import com.kafka.pago.Service.ServicioPagos.CobroInmediato;
import com.kafka.pago.Service.ServicioPagos.RedireccionWebpay;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * Traduce HTTP ↔ ServicioPagos. Los errores (400/429/502/503) los resuelve
 * ManejadorErroresGlobal a partir de las excepciones del servicio.
 */
@Slf4j
@RestController
@RequestMapping("/api/pagos")
public class PagosController {

    private final ServicioPagos servicioPagos;

    /**
     * Destinos del redirect posterior al commit, por origen. Lista CERRADA que
     * sale de la configuración: nunca se redirige a una URL que mande el cliente.
     */
    private final Map<OrigenPago, String> frontPorOrigen;

    public PagosController(ServicioPagos servicioPagos,
                           @Value("${app.front.url:http://localhost:5173}") String frontConsola,
                           @Value("${app.front.url-tienda:http://localhost:5174}") String frontTienda) {
        this.servicioPagos = servicioPagos;
        this.frontPorOrigen = Map.of(OrigenPago.CONSOLA, frontConsola, OrigenPago.TIENDA, frontTienda);
    }

    /**
     * WEBPAY → 200 { url, token } (igual que antes; la consola hace el POST a Transbank).
     * SIMULADA → 200 { estado, idOrden, ultimos4 } si aprueba, 402 si rechaza.
     */
    @PostMapping("/iniciar")
    public ResponseEntity<Map<String, Object>> iniciarPago(@Valid @RequestBody PeticionPagoDTO peticion) {
        log.info("Iniciar pago: orden={} cliente={} monto={} pasarela={} origen={}", peticion.getTransactionId(),
                peticion.getClientId(), peticion.getAmount(), peticion.getPasarela(), peticion.getOrigen());

        return switch (servicioPagos.iniciarPago(peticion)) {
            case RedireccionWebpay r -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("url", r.url());
                body.put("token", r.token());
                yield ResponseEntity.ok(body);
            }
            case CobroInmediato c -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("estado", c.estado().name());
                body.put("idOrden", c.idOrden());
                body.put("ultimos4", c.ultimos4());
                HttpStatus status = c.estado() == EstadoResultadoPago.AUTORIZADO ? HttpStatus.OK : HttpStatus.PAYMENT_REQUIRED;
                yield ResponseEntity.status(status).body(body);
            }
        };
    }

    /**
     * Vuelta de Webpay. Transbank entra acá con POST y, según el flujo de
     * anulación, puede volver con GET: se aceptan ambos verbos.
     *
     * Los parámetros son opcionales a propósito. Cuando el cliente abandona el
     * formulario, Transbank NO envía token_ws sino TBK_TOKEN junto con la orden
     * de compra; exigir token_ws haría fallar con un 400 una anulación normal.
     *
     * "origen" no lo manda Transbank: viaja en el query del returnUrl que se le
     * entregó al crear la transacción.
     *
     * Responde SIEMPRE un redirect 302 al frontend, así el navegador queda en la
     * pantalla /callback y no mirando un JSON crudo.
     */
    @RequestMapping(value = "/commit", method = {RequestMethod.POST, RequestMethod.GET})
    public ResponseEntity<Void> confirmarPagoWebpay(
            @RequestParam(value = "token_ws", required = false) String tokenWs,
            @RequestParam(value = "TBK_TOKEN", required = false) String tbkToken,
            @RequestParam(value = "TBK_ORDEN_COMPRA", required = false) String tbkOrdenCompra,
            @RequestParam(value = "TBK_ID_SESION", required = false) String tbkIdSesion,
            @RequestParam(value = "origen", required = false) String origen) {

        String callback = frontPorOrigen.get(OrigenPago.desdeTexto(origen)) + "/callback";

        // --- Caso 1: el cliente anuló el pago en el formulario de Webpay ---
        if (tokenWs == null || tokenWs.isBlank()) {
            log.warn("Vuelta de Webpay sin token_ws (anulación). TBK_TOKEN={}, orden={}", tbkToken, tbkOrdenCompra);
            servicioPagos.registrarAnulacionWebpay(tbkOrdenCompra, tbkIdSesion);
            return redirigir(UriComponentsBuilder.fromUriString(callback)
                    .queryParam("estado", "ANULADO")
                    .queryParam("orden", textoSeguro(tbkOrdenCompra))
                    .queryParam("cliente", textoSeguro(tbkIdSesion)));
        }

        // --- Caso 2: hay token, se confirma contra Transbank ---
        try {
            ConfirmacionWebpay c = servicioPagos.confirmarWebpay(tokenWs);
            // Los nombres y valores del query se mantienen: la pantalla /callback
            // de la consola los lee así (AUTHORIZED/REJECTED, orden, cliente...).
            UriComponentsBuilder destino = UriComponentsBuilder.fromUriString(callback)
                    .queryParam("estado", c.autorizado() ? "AUTHORIZED" : "REJECTED")
                    .queryParam("orden", textoSeguro(c.idOrden()))
                    .queryParam("cliente", textoSeguro(c.idCliente()))
                    .queryParam("monto", textoSeguro(c.monto()))
                    .queryParam("autorizacion", textoSeguro(c.codigoAutorizacion()))
                    .queryParam("fecha", textoSeguro(c.fecha()));
            // El detalle de tarjeta puede venir nulo en transacciones rechazadas.
            if (c.ultimos4() != null) {
                destino.queryParam("tarjeta", c.ultimos4());
            }
            // Para que el frontend explique el rechazo en vez de culpar a Transbank.
            if (c.bloqueadaPorAntifraude()) {
                destino.queryParam("motivo", "antifraude");
            }
            return redirigir(destino);
        } catch (Exception e) {
            // Sin respuesta de Transbank no se conoce la orden: no se publica
            // resultado (ms-carrito lo resolverá cuando el cliente reintente).
            log.error("Error al confirmar el pago Webpay: {}", e.getMessage());
            return redirigir(UriComponentsBuilder.fromUriString(callback)
                    .queryParam("estado", "ERROR")
                    .queryParam("detalle", textoSeguro(e.getMessage())));
        }
    }

    private static String textoSeguro(Object valor) {
        return valor == null ? "" : valor.toString();
    }

    /** 302 + Location: el navegador sigue el redirect y queda en el frontend. */
    private static ResponseEntity<Void> redirigir(UriComponentsBuilder builder) {
        URI destino = builder.build().encode().toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(destino).build();
    }
}
