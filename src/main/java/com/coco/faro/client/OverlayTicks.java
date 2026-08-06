package com.coco.faro.client;

import com.coco.faro.config.ConfigFaro;
import com.coco.faro.diag.MonitorRendimiento;
import com.coco.faro.diag.MotorDiagnostico;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.Locale;

/**
 * HUD chico con el tiempo de tick y el TPS efectivo, en vivo.
 *
 * Se prende y apaga desde los ajustes de Faro, no editando el .toml a mano.
 *
 * Sobre el numero de TPS: mientras el tick entre en su presupuesto de 50 ms, el
 * juego mantiene 20 por segundo y mostrar otra cosa seria enganoso. Recien
 * cuando se pasa, la frecuencia real cae a 1000/duracion. Por eso el calculo no
 * es "50/tiempo * 20" como se ve en otros mods: eso muestra 25 TPS cuando el
 * tick va rapido, que no existe.
 */
public final class OverlayTicks implements IGuiOverlay {

    public static final OverlayTicks INSTANCIA = new OverlayTicks();

    private static final int MARGEN = 4;

    private OverlayTicks() {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick,
                       int anchoPantalla, int altoPantalla) {
        try {
            renderInterno(g, anchoPantalla, altoPantalla);
        } catch (Throwable t) {
            // El HUD se dibuja cada frame: un fallo aca llenaria el log y podria
            // romper el render del juego. Se traga y se sigue.
            com.coco.faro.Faro.LOG.debug("[Faro] Fallo el HUD: {}", t.toString());
        }
    }

    private void renderInterno(GuiGraphics g, int anchoPantalla, int altoPantalla) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.options.hideGui) {
            return;
        }

        // La notificacion estilo logro se dibuja siempre, aunque el HUD de ticks
        // este apagado: son cosas distintas.
        NotificacionLogro.render(g, anchoPantalla);

        if (!ConfigFaro.INSTANCIA.hudTicksActivo.get() || mc.options.renderDebug) {
            return;
        }
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null) {
            return;
        }

        MonitorRendimiento r = motor.rendimiento();
        boolean detallado = ConfigFaro.INSTANCIA.hudTicksDetallado.get();

        double prom = r.promedioMs();
        double tps = (prom <= 50.0 || prom <= 0) ? 20.0 : 1000.0 / prom;
        int color = colorPorTick(prom);

        String principal = String.format(Locale.ROOT, "%.1f ms  ·  %.1f TPS", prom, tps);
        String detalle = detallado
                ? String.format(Locale.ROOT, "p95 %.0f ms  ·  RAM %d%%",
                        r.p95Ms(), MonitorRendimiento.porcentajeMemoria())
                : null;

        int anchoTexto = Math.max(mc.font.width(principal),
                detalle == null ? 0 : mc.font.width(detalle));
        int ancho = anchoTexto + 10;
        int alto = detalle == null ? 14 : 24;

        int x = switch (ConfigFaro.INSTANCIA.hudTicksEsquina.get()) {
            case ARRIBA_IZQUIERDA, ABAJO_IZQUIERDA -> MARGEN;
            case ARRIBA_DERECHA, ABAJO_DERECHA -> anchoPantalla - ancho - MARGEN;
        };
        int y = switch (ConfigFaro.INSTANCIA.hudTicksEsquina.get()) {
            case ARRIBA_IZQUIERDA, ARRIBA_DERECHA -> MARGEN;
            case ABAJO_IZQUIERDA, ABAJO_DERECHA -> altoPantalla - alto - MARGEN;
        };

        // Alerta de uso critico: solo aparece cuando de verdad esta al limite,
        // asi no se vuelve ruido que uno aprende a ignorar.
        String alerta = alertaCritica();
        if (alerta != null) {
            alto += 10;
            ancho = Math.max(ancho, mc.font.width(alerta) + 10);
        }

        g.fill(x, y, x + ancho, y + alto, 0xB0000000);
        g.fill(x, y, x + 2, y + alto, color);

        g.drawString(mc.font, principal, x + 6, y + 3, color, false);
        int yy = y + 13;
        if (detalle != null) {
            g.drawString(mc.font, detalle, x + 6, yy, 0xFF8B98A5, false);
            yy += 10;
        }
        if (alerta != null) {
            // Late para que se note sin tapar nada.
            int c = com.coco.faro.client.Paleta.mezclar(
                    0xFFF85149, 0xFFFFFFFF, Widgets.pulso(900) * 0.6f);
            g.drawString(mc.font, alerta, x + 6, yy, c, false);
        }
    }

    /** Devuelve el primer recurso que este en rojo, o null si esta todo bien. */
    private static String alertaCritica() {
        int ram = MonitorRendimiento.porcentajeMemoria();
        if (ram >= 92) {
            return "! RAM al " + ram + "%";
        }
        com.coco.faro.diag.MonitorHardware hw = com.coco.faro.diag.MonitorHardware.get();
        // Sostenido, no instantaneo, y con umbral alto: la CPU de un juego
        // moddeado toca picos todo el tiempo sin que eso sea un problema.
        if (hw.cpuSostenidamenteAlta(97)) {
            return "! CPU al " + hw.cpuDelJuego() + "%";
        }
        com.coco.faro.diag.MonitorHardware.LecturaGpu gpu = hw.gpu();
        if (gpu.hayDato() && gpu.usoPorcentaje() >= 98) {
            return "! GPU al " + gpu.usoPorcentaje() + "%";
        }
        if (gpu.hayTemperatura() && gpu.temperaturaC() >= 87) {
            return "! GPU a " + gpu.temperaturaC() + " C";
        }
        return null;
    }

    /** Verde con margen, amarillo cerca del limite, rojo pasado los 50 ms. */
    private static int colorPorTick(double ms) {
        if (ms <= 0) {
            return 0xFF8B98A5;
        }
        if (ms < 35) {
            return 0xFF3FB950;
        }
        if (ms < 50) {
            return 0xFFD29922;
        }
        return 0xFFF85149;
    }
}
