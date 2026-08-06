package com.coco.faro.client;

import com.coco.faro.config.ConfigFaro;
import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.diag.VigilanteLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.List;

/**
 * Aviso chico en una esquina cuando aparecen errores nuevos en el log.
 *
 * Pensado para no molestar: aparece solo cuando hay algo nuevo, se va solo a los
 * pocos segundos, no captura el mouse y no interrumpe el juego. Si no hay nada
 * que informar, no dibuja un solo pixel.
 */
public final class OverlayFaro implements IGuiOverlay {

    public static final OverlayFaro INSTANCIA = new OverlayFaro();

    private static final int ANCHO = 118;
    private static final int MARGEN = 4;

    private long ultimaNovedadMs = 0L;
    private int erroresVistos = 0;
    private int advertenciasVistas = 0;

    private OverlayFaro() {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick, int anchoPantalla, int altoPantalla) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.options.hideGui) {
            return;
        }
        if (!ConfigFaro.INSTANCIA.overlayActivo.get()) {
            return;
        }

        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null) {
            return;
        }
        VigilanteLog v = motor.vigilante();

        boolean soloErrores = ConfigFaro.INSTANCIA.overlaySoloErrores.get();
        int err = v.errores();
        int adv = v.advertencias();

        // Detectamos novedad comparando contra lo que ya habiamos mostrado.
        boolean hayNuevo = err > erroresVistos || (!soloErrores && adv > advertenciasVistas);
        if (hayNuevo) {
            ultimaNovedadMs = System.currentTimeMillis();
            erroresVistos = err;
            advertenciasVistas = adv;
        }

        long visibleMs = ConfigFaro.INSTANCIA.segundosVisibleAviso.get() * 1000L;
        if (ultimaNovedadMs == 0L || System.currentTimeMillis() - ultimaNovedadMs > visibleMs) {
            return;
        }

        List<VigilanteLog.Evento> ultimos = v.ultimosEventos(2);
        int alto = 20 + ultimos.size() * 10;

        int x = switch (ConfigFaro.INSTANCIA.overlayEsquina.get()) {
            case ARRIBA_IZQUIERDA, ABAJO_IZQUIERDA -> MARGEN;
            case ARRIBA_DERECHA, ABAJO_DERECHA -> anchoPantalla - ANCHO - MARGEN;
        };
        int y = switch (ConfigFaro.INSTANCIA.overlayEsquina.get()) {
            case ARRIBA_IZQUIERDA, ARRIBA_DERECHA -> MARGEN;
            case ABAJO_IZQUIERDA, ABAJO_DERECHA -> altoPantalla - alto - MARGEN;
        };

        boolean critico = err > 0;
        int acento = critico ? Paleta.ERROR : Paleta.ADVERTENCIA;

        g.fill(x, y, x + ANCHO, y + alto, Paleta.FONDO_PANEL);
        g.fill(x, y, x + ANCHO, y + 1, acento);
        g.fill(x, y, x + 1, y + alto, acento);

        String titulo = critico ? "Faro: errores" : "Faro: avisos";
        g.drawString(mc.font, titulo, x + 4, y + 5, acento, false);

        String resumen = err + " err  /  " + adv + " warn";
        g.drawString(mc.font, resumen, x + ANCHO - 4 - mc.font.width(resumen), y + 5,
                Paleta.TEXTO_TENUE, false);

        int yy = y + 17;
        for (VigilanteLog.Evento e : ultimos) {
            String linea = e.origen() + ": " + e.mensaje();
            String recortado = mc.font.plainSubstrByWidth(linea, ANCHO - 8);
            g.drawString(mc.font, recortado, x + 4, yy,
                    e.esError() ? Paleta.ERROR : Paleta.TEXTO_TENUE, false);
            yy += 10;
        }
    }

    /** Se llama al cerrar la pantalla de Faro para que el aviso no reaparezca al toque. */
    public void marcarComoVisto() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor != null) {
            erroresVistos = motor.vigilante().errores();
            advertenciasVistas = motor.vigilante().advertencias();
        }
        ultimaNovedadMs = 0L;
    }
}
