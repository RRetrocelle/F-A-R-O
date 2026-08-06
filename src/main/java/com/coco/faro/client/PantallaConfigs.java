package com.coco.faro.client;

import com.coco.faro.diag.Diagnostico;
import com.coco.faro.diag.EscanerConfigs;
import com.coco.faro.diag.MotorDiagnostico;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Escaneo profundo de configs, para cuando el crash no tiene causa clara.
 *
 * El motor de sospecha trabaja sobre el stacktrace y los metadatos. Cuando eso no
 * alcanza, queda una causa que ese camino nunca ve: un archivo de configuracion
 * roto o con un valor absurdo. Esta pantalla la busca.
 */
public class PantallaConfigs extends PantallaHerramienta {

    private EscanerConfigs.Reporte reporte;
    private volatile boolean escaneando = false;

    public PantallaConfigs(Screen anterior) {
        super(anterior, "Escaneo de configs",
                "archivos rotos, editados justo antes del crash, o con valores absurdos");
        lanzar();
    }

    private void lanzar() {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null || escaneando) {
            return;
        }
        escaneando = true;
        Thread t = new Thread(() -> {
            try {
                long momentoCrash = motor.diagnostico()
                        .filter(Diagnostico::huboCrash)
                        .flatMap(Diagnostico::fechaCrash)
                        .map(java.time.Instant::toEpochMilli)
                        .orElse(0L);
                reporte = EscanerConfigs.analizar(motor.carpetaJuego(), momentoCrash);
            } catch (Throwable e) {
                com.coco.faro.Faro.LOG.error("[Faro] Fallo el escaneo de configs", e);
            } finally {
                escaneando = false;
                Minecraft.getInstance().execute(this::refrescar);
            }
        }, "Faro-EscaneoConfigs");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        int mitad = (ancho - 6) / 2;

        addRenderableWidget(Button.builder(
                        Component.literal(escaneando ? "Escaneando..." : "Volver a escanear"),
                        b -> {
                            lanzar();
                            refrescar();
                        })
                .bounds(x, y, mitad, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Abrir carpeta config"),
                        b -> {
                            MotorDiagnostico m = MotorDiagnostico.get();
                            if (m != null) {
                                Util.getPlatform().openFile(
                                        m.carpetaJuego().resolve("config").toFile());
                            }
                        })
                .bounds(x + mitad + 6, y, mitad, 20).build());
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        if (reporte == null) {
            return vacio(g, x, y, ancho, escaneando
                    ? "Recorriendo la carpeta config..."
                    : "Todavia no se escaneo nada.");
        }

        y = veredicto(g, x, y, ancho, EscanerConfigs.veredicto(reporte),
                reporte.hayAlgoGrave() ? Paleta.ERROR
                        : (reporte.hallazgos().isEmpty() ? Paleta.OK : Paleta.NEUTRO));

        y = fila(g, x, y, ancho, "Archivos revisados",
                String.valueOf(reporte.archivosRevisados()), Paleta.TEXTO_TENUE);
        y = fila(g, x, y, ancho, "Duro", reporte.duracionMs() + " ms", Paleta.TEXTO_APAGADO);
        y += 8;

        y = seccion(g, x, y, ancho, "Por que esto importa");
        y = Widgets.parrafo(g, this.font,
                "El motor de sospecha de Faro trabaja sobre el stacktrace y los metadatos de los "
                        + "jars. Hay una causa que ese camino no puede ver: un config editado a "
                        + "mano que quedo roto.\n\n"
                        + "Pasa mas seguido de lo que parece. Tocas un .toml para subir un limite, "
                        + "se te va una coma, y el mod que lo lee revienta al arrancar sin decir "
                        + "cual era. El log muestra un error de parseo generico y el crash report "
                        + "no nombra a nadie.",
                x, y, ancho, Paleta.TEXTO_TENUE, 12) + 6;

        y = grupo(g, x, y, ancho, EscanerConfigs.Tipo.ROTO, Paleta.ERROR);
        y = grupo(g, x, y, ancho, EscanerConfigs.Tipo.RECIENTE, Paleta.ADVERTENCIA);
        y = grupo(g, x, y, ancho, EscanerConfigs.Tipo.VACIO, Paleta.NEUTRO);
        y = grupo(g, x, y, ancho, EscanerConfigs.Tipo.VALOR_EXTREMO, Paleta.VIOLETA);
        y = grupo(g, x, y, ancho, EscanerConfigs.Tipo.GRANDE, Paleta.TEXTO_TENUE);

        if (reporte.hallazgos().isEmpty()) {
            y = vacio(g, x, y, ancho,
                    "Ningun archivo con problemas. Si el crash sigue sin causa clara, no viene "
                            + "de un config roto: probá la biseccion automatica.");
        }
        return y + 8;
    }

    private int grupo(GuiGraphics g, int x, int y, int ancho, EscanerConfigs.Tipo tipo, int color) {
        List<EscanerConfigs.Hallazgo> lista = reporte.por(tipo);
        if (lista.isEmpty()) {
            return y;
        }
        y = seccion(g, x, y, ancho, tipo.etiqueta + " (" + lista.size() + ")");

        for (EscanerConfigs.Hallazgo h : lista) {
            if (y > yContenido + altoVisible + 10) {
                y += 50;
                continue;
            }
            int anchoBadge = Widgets.badge(g, this.font, tipo.certeza.etiqueta(), x, y,
                    PanelProblemas.colorCerteza(tipo.certeza));
            zonas.add(Zona.ayuda(x, y, anchoBadge, 11, "certeza"));

            Widgets.lineaRecortada(g, this.font, h.nombreCorto(), x + anchoBadge + 6, y + 2,
                    ancho - anchoBadge - 60, color);
            String mod = h.modProbable();
            g.drawString(this.font, mod, x + ancho - this.font.width(mod), y + 2,
                    Paleta.TEXTO_APAGADO, false);
            y += 14;

            y = Widgets.parrafo(g, this.font, h.detalle(), x + 6, y, ancho - 10,
                    Paleta.TEXTO_TENUE, 5);
            y = Widgets.parrafo(g, this.font, "-> " + h.queHacer(), x + 6, y, ancho - 10,
                    Paleta.NEUTRO, 5);

            boton(g, x + 6, y + 2, "Abrir la carpeta que lo contiene", Paleta.TEXTO_TENUE,
                    () -> Util.getPlatform().openFile(h.archivo().getParent().toFile()));
            y += 24;
        }
        return y + 4;
    }
}
