package com.kafka.pago.Service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.kafka.pago.Component.ProductorIntentosPago;
import com.kafka.pago.Component.ProductorResultadosPago;
import com.kafka.pago.DTO.EstadoResultadoPago;
import com.kafka.pago.DTO.IntentoPagoDTO;
import com.kafka.pago.DTO.OrigenPago;
import com.kafka.pago.DTO.PeticionPagoDTO;
import com.kafka.pago.DTO.ResultadoPagoDTO;
import com.kafka.pago.DTO.TarjetaDTO;
import com.kafka.pago.DTO.TipoPasarela;
import com.kafka.pago.Exception.KafkaNoDisponibleException;
import com.kafka.pago.Exception.TarjetaBloqueadaException;
import com.kafka.pago.Model.BloqueoTarjeta;
import com.kafka.pago.Service.PasarelaPago.Redireccion;
import com.kafka.pago.Service.PasarelaPago.SolicitudCobro;
import com.kafka.pago.Service.PasarelaWebpay.ConfirmacionWebpay;
import com.kafka.pago.Service.ServicioPagos.CobroInmediato;
import com.kafka.pago.Service.ServicioPagos.RedireccionWebpay;

/** Orquestación del pago con todas las dependencias externas mockeadas. */
class ServicioPagosTest {

    private static final String NUMERO = "4051885600446623";

    private ServicioAntifraude antifraude;
    private ProductorIntentosPago productorIntentos;
    private ProductorResultadosPago productorResultados;
    private PasarelaWebpay webpay;
    private final HuellaTarjeta huellas = new HuellaTarjeta("p");
    private ServicioPagos servicio;

    @BeforeEach
    void preparar() {
        antifraude = mock(ServicioAntifraude.class);
        productorIntentos = mock(ProductorIntentosPago.class);
        productorResultados = mock(ProductorResultadosPago.class);
        webpay = mock(PasarelaWebpay.class);
        when(webpay.tipo()).thenReturn(TipoPasarela.WEBPAY);
        // Por defecto la ventana no infringe; el caso del 4.º intento se prueba aparte.
        when(antifraude.anticiparDecision(anyString())).thenReturn(new VentanaAntifraude.Decision(1, false));
        Clock reloj = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);
        servicio = new ServicioPagos(antifraude, huellas, productorIntentos, productorResultados, webpay,
                List.of(webpay, new PasarelaSimulada()), reloj, "http://localhost:8083/api/pagos/commit");
    }

    private static PeticionPagoDTO peticion(TipoPasarela pasarela, String numero, OrigenPago origen) {
        PeticionPagoDTO p = new PeticionPagoDTO();
        p.setClientId("ismael");
        p.setAmount(15000L);
        p.setTransactionId("TX-1");
        p.setPasarela(pasarela);
        p.setOrigen(origen);
        if (numero != null) {
            p.setTarjeta(new TarjetaDTO(numero));
        }
        return p;
    }

    @Test
    void webpayUsaHuellaDeCuentaYReturnUrlConOrigen() {
        when(webpay.iniciar(any())).thenReturn(new Redireccion("https://webpay/init", "tok"));

        var r = servicio.iniciarPago(peticion(null, null, OrigenPago.TIENDA));

        assertThat(r).isEqualTo(new RedireccionWebpay("https://webpay/init", "tok"));
        ArgumentCaptor<SolicitudCobro> solicitud = ArgumentCaptor.forClass(SolicitudCobro.class);
        verify(webpay).iniciar(solicitud.capture());
        assertThat(solicitud.getValue().returnUrl()).isEqualTo("http://localhost:8083/api/pagos/commit?origen=TIENDA");

        ArgumentCaptor<IntentoPagoDTO> intento = ArgumentCaptor.forClass(IntentoPagoDTO.class);
        verify(productorIntentos).publicarConConfirmacion(intento.capture());
        assertThat(intento.getValue().getHuellaTarjeta()).isEqualTo(huellas.deCuenta("ismael"));
        assertThat(intento.getValue().getPasarela()).isEqualTo(TipoPasarela.WEBPAY);
        assertThat(intento.getValue().getUltimos4()).isNull();
        assertThat(intento.getValue().getMoneda()).isEqualTo("CLP");
        verify(antifraude).verificarNoBloqueada(huellas.deCuenta("ismael"));
        // El resultado de Webpay se publica recién en el commit.
        verify(productorResultados, never()).publicar(any());
    }

    @Test
    void simuladaAprobadaPublicaResultadoAutorizadoSinElNumero() {
        var r = servicio.iniciarPago(peticion(TipoPasarela.SIMULADA, NUMERO, null));

        assertThat(r).isEqualTo(new CobroInmediato(EstadoResultadoPago.AUTORIZADO, "TX-1", "6623"));
        ArgumentCaptor<IntentoPagoDTO> intento = ArgumentCaptor.forClass(IntentoPagoDTO.class);
        verify(productorIntentos).publicarConConfirmacion(intento.capture());
        assertThat(intento.getValue().getHuellaTarjeta()).isEqualTo(huellas.deTarjeta(NUMERO));
        assertThat(intento.getValue().toString()).doesNotContain(NUMERO);

        ArgumentCaptor<ResultadoPagoDTO> resultado = ArgumentCaptor.forClass(ResultadoPagoDTO.class);
        verify(productorResultados).publicar(resultado.capture());
        assertThat(resultado.getValue().getEstado()).isEqualTo(EstadoResultadoPago.AUTORIZADO);
        assertThat(resultado.getValue().getIdOrden()).isEqualTo("TX-1");
        assertThat(resultado.getValue().getCodigoAutorizacion()).matches("^\\d{6}$");
        assertThat(resultado.getValue().toString()).doesNotContain(NUMERO);
        verify(webpay, never()).iniciar(any());
    }

    @Test
    void simuladaRechazaTarjeta0002() {
        var r = servicio.iniciarPago(peticion(TipoPasarela.SIMULADA, "4000000000000002", null));
        assertThat(((CobroInmediato) r).estado()).isEqualTo(EstadoResultadoPago.RECHAZADO);
        ArgumentCaptor<ResultadoPagoDTO> resultado = ArgumentCaptor.forClass(ResultadoPagoDTO.class);
        verify(productorResultados).publicar(resultado.capture());
        assertThat(resultado.getValue().getEstado()).isEqualTo(EstadoResultadoPago.RECHAZADO);
    }

    @Test
    void huellaBloqueadaCortaAntesDePublicarYCobrar() {
        doThrow(new TarjetaBloqueadaException(LocalDateTime.now(), 4, 30))
                .when(antifraude).verificarNoBloqueada(anyString());

        assertThatThrownBy(() -> servicio.iniciarPago(peticion(TipoPasarela.SIMULADA, NUMERO, null)))
                .isInstanceOf(TarjetaBloqueadaException.class);
        verify(productorIntentos, never()).publicarConConfirmacion(any());
        verify(productorResultados, never()).publicar(any());
    }

    @Test
    void cuartoIntentoSePublicaPeroNoSeCobraYResponde429() {
        // La ventana ya tiene 3: este sería el 4.º, el que infringe.
        when(antifraude.anticiparDecision(anyString())).thenReturn(new VentanaAntifraude.Decision(4, true));
        when(antifraude.excepcionPorUmbral(anyString(), any()))
                .thenReturn(new TarjetaBloqueadaException(LocalDateTime.now(), 4, 30));

        assertThatThrownBy(() -> servicio.iniciarPago(peticion(TipoPasarela.SIMULADA, NUMERO, null)))
                .isInstanceOf(TarjetaBloqueadaException.class);
        // Se publica: el consumidor necesita este intento para bloquear y alertar.
        verify(productorIntentos).publicarConConfirmacion(any());
        // Pero no hay cobro ni resultado.
        verify(productorResultados, never()).publicar(any());
        verify(webpay, never()).iniciar(any());
    }

    @Test
    void kafkaCaidoImpideCobrar() {
        when(productorIntentos.publicarConConfirmacion(any()))
                .thenThrow(new KafkaNoDisponibleException("sin ack", null));

        assertThatThrownBy(() -> servicio.iniciarPago(peticion(null, null, null)))
                .isInstanceOf(KafkaNoDisponibleException.class);
        verify(webpay, never()).iniciar(any());
    }

    @Test
    void commitWebpayPublicaResultadoYReiniciaVentanaSiAutoriza() {
        when(webpay.confirmar("tok")).thenReturn(new ConfirmacionWebpay("AUTHORIZED", "TX-9", "ismael", 15000L,
                "1213", "2026-11-22T12:00:00Z", "6623"));

        servicio.confirmarWebpay("tok");

        verify(antifraude).gestionarResultadoCallbackPago(huellas.deCuenta("ismael"), true);
        ArgumentCaptor<ResultadoPagoDTO> resultado = ArgumentCaptor.forClass(ResultadoPagoDTO.class);
        verify(productorResultados).publicar(resultado.capture());
        assertThat(resultado.getValue()).extracting(ResultadoPagoDTO::getIdOrden, ResultadoPagoDTO::getEstado,
                        ResultadoPagoDTO::getMonto, ResultadoPagoDTO::getUltimos4, ResultadoPagoDTO::getPasarela)
                .containsExactly("TX-9", EstadoResultadoPago.AUTORIZADO, 15000L, "6623", TipoPasarela.WEBPAY);
    }

    @Test
    void commitWebpayConTarjetaBloqueadaAnulaYPublicaRechazado() {
        when(webpay.confirmar("tok")).thenReturn(new ConfirmacionWebpay("AUTHORIZED", "TX-9", "diego@duocuc.cl", 15000L,
                "1213", "2026-11-22T12:00:00Z", "6623"));
        BloqueoTarjeta bloqueo = new BloqueoTarjeta();
        bloqueo.setHuella(huellas.deTarjeta(NUMERO));
        bloqueo.setUltimos4("6623");
        bloqueo.setBloqueadoHasta(Instant.parse("2026-11-22T12:30:00Z"));
        when(antifraude.bloqueoVigentePorUltimos4("6623")).thenReturn(java.util.Optional.of(bloqueo));
        when(webpay.anular("tok", 15000L)).thenReturn(true);

        ConfirmacionWebpay c = servicio.confirmarWebpay("tok");

        assertThat(c.autorizado()).isFalse();
        assertThat(c.bloqueadaPorAntifraude()).isTrue();
        verify(webpay).anular("tok", 15000L);
        // Un pago con tarjeta bloqueada no levanta el bloqueo ni limpia la ventana.
        verify(antifraude, never()).gestionarResultadoCallbackPago(anyString(), anyBoolean());
        ArgumentCaptor<ResultadoPagoDTO> resultado = ArgumentCaptor.forClass(ResultadoPagoDTO.class);
        verify(productorResultados).publicar(resultado.capture());
        assertThat(resultado.getValue().getEstado()).isEqualTo(EstadoResultadoPago.RECHAZADO);
    }

    @Test
    void simuladaRevisaTambienLaCuentaDelCliente() {
        doThrow(new TarjetaBloqueadaException(LocalDateTime.of(2026, 11, 22, 12, 30), 4, 30L))
                .when(antifraude).verificarNoBloqueada(huellas.deCuenta("ismael"));

        assertThatThrownBy(() -> servicio.iniciarPago(peticion(TipoPasarela.SIMULADA, NUMERO, null)))
                .isInstanceOf(TarjetaBloqueadaException.class);
        verify(productorIntentos, never()).publicarConConfirmacion(any());
    }

    @Test
    void commitWebpayRechazadoNoLimpiaVentana() {
        when(webpay.confirmar("tok")).thenReturn(new ConfirmacionWebpay("FAILED", "TX-9", "ismael", 15000L,
                null, null, null));
        servicio.confirmarWebpay("tok");
        verify(antifraude).gestionarResultadoCallbackPago(huellas.deCuenta("ismael"), false);
        ArgumentCaptor<ResultadoPagoDTO> resultado = ArgumentCaptor.forClass(ResultadoPagoDTO.class);
        verify(productorResultados).publicar(resultado.capture());
        assertThat(resultado.getValue().getEstado()).isEqualTo(EstadoResultadoPago.RECHAZADO);
    }

    @Test
    void anulacionPublicaAnuladoSoloSiHayOrden() {
        servicio.registrarAnulacionWebpay("TX-5", "ismael");
        ArgumentCaptor<ResultadoPagoDTO> resultado = ArgumentCaptor.forClass(ResultadoPagoDTO.class);
        verify(productorResultados).publicar(resultado.capture());
        assertThat(resultado.getValue().getEstado()).isEqualTo(EstadoResultadoPago.ANULADO);
        verify(antifraude).gestionarResultadoCallbackPago(eq(huellas.deCuenta("ismael")), eq(false));

        servicio.registrarAnulacionWebpay(null, null);
        verify(productorResultados).publicar(any()); // sigue siendo una sola publicación
    }
}
