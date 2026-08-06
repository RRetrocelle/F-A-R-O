package com.coco.faro.client;

import com.coco.faro.config.ConfigFaro;
import com.coco.faro.diag.MonitorWorldgen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * Generacion de mundo: que mod te cuesta al explorar, y que estructuras llegan rotas.
 *
 * Las dos cosas se sufren juntas al caminar hacia terreno nuevo, y por eso
 * comparten pantalla — aunque salgan de fuentes distintas: los tiempos de un
 * cronometro real, y las estructuras rotas de lo que el propio juego escribe en
 * el log.
 */
public class PantallaWorldgen extends PantallaHerramienta {

    private boolean porMod = true;

    public PantallaWorldgen(Screen anterior) {
        super(anterior, "Generacion de mundo",
                "cuanto cuesta explorar, y que estructuras llegan incompletas");
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        int tercio = (ancho - 12) / 3;
        boolean midiendo = MonitorWorldgen.midiendo();

        addRenderableWidget(Button.builder(
                        Component.literal(midiendo ? "Dejar de medir" : "Medir la generacion"),
                        b -> {
                            boolean nuevo = !MonitorWorldgen.midiendo();
                            MonitorWorldgen.medir(nuevo);
                            ConfigFaro.INSTANCIA.medirWorldgen.set(nuevo);
                            ConfigFaro.INSTANCIA.medirWorldgen.save();
                            refrescar();
                        })
                .bounds(x, y, tercio, 20).build());

        addRenderableWidget(Button.builder(
                        Component.literal(porMod ? "Ver por feature" : "Ver por mod"),
                        b -> {
                            porMod = !porMod;
                            refrescar();
                        })
                .bounds(x + tercio + 6, y, tercio, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Reiniciar medicion"),
                        b -> {
                            MonitorWorldgen.reiniciar();
                            refrescar();
                        })
                .bounds(x + (tercio + 6) * 2, y, tercio, 20).build());
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        y = veredicto(g, x, y, ancho, MonitorWorldgen.veredicto(),
                MonitorWorldgen.msPorChunk() >= 20 ? Paleta.ERROR
                        : (MonitorWorldgen.msPorChunk() >= 8 ? Paleta.ADVERTENCIA : Paleta.OK));

        // --- Estructuras rotas: va primero porque es lo que la gente viene a buscar.
        List<MonitorWorldgen.EstructuraRota> rotas = MonitorWorldgen.estructurasRotas();
        y = seccion(g, x, y, ancho, "Estructuras que llegaron rotas (" + rotas.size() + ")");

        if (rotas.isEmpty()) {
            y = Widgets.parrafo(g, this.font,
                    "Ninguna. Si viste una casa sin techo o un dungeon cortado, el problema no "
                            + "es que la plantilla no cargue: puede ser un choque entre dos mods "
                            + "que generan en el mismo lugar, y eso no deja rastro en el log.",
                    x, y, ancho, Paleta.OK, 6) + 6;
        } else {
            for (MonitorWorldgen.EstructuraRota r : rotas) {
                if (y > yContenido + altoVisible + 10) {
                    y += 40;
                    continue;
                }
                Widgets.tarjeta(g, x, y, ancho, 22, Paleta.ADVERTENCIA);
                Widgets.lineaRecortada(g, this.font, r.nombre(), x + 7, y + 4, ancho - 60,
                        Paleta.TEXTO);
                g.drawString(this.font, r.modId(),
                        x + ancho - this.font.width(r.modId()) - 6, y + 4,
                        Paleta.TEXTO_APAGADO, false);
                Widgets.lineaRecortada(g, this.font, r.motivo(), x + 7, y + 13, ancho - 14,
                        Paleta.ADVERTENCIA);
                y += 26;
                y = Widgets.parrafo(g, this.font, r.detalle(), x + 6, y, ancho - 10,
                        Paleta.TEXTO_TENUE, 5) + 6;
            }
            y = Widgets.parrafo(g, this.font,
                    "Estos avisos los escribe el propio juego en el log: el dato es suyo, no una "
                            + "interpretacion de Faro. Lo que si es hipotesis es a quien culpar — "
                            + "un bloque faltante puede venir de haber sacado otro mod, no del "
                            + "que genera la estructura.",
                    x, y, ancho, Paleta.TEXTO_APAGADO, 6) + 6;
        }

        // --- Tiempos
        y = seccion(g, x, y, ancho, "Costo de la generacion");
        if (!MonitorWorldgen.midiendo()) {
            y = Widgets.parrafo(g, this.font,
                    "Apagado. Un chunk pasa por cientos de enganches de generacion, y un mundo "
                            + "generando terreno hace decenas de chunks por segundo. Cronometrarlos "
                            + "todos siempre seria costoso justo cuando el juego mas necesita la "
                            + "CPU.\n\n"
                            + "Prendelo con el boton de abajo, caminá hacia terreno sin explorar, "
                            + "y volvé. Despues apagalo.",
                    x, y, ancho, Paleta.TEXTO_TENUE, 10);
            return y + 6;
        }

        if (!MonitorWorldgen.hayDatos()) {
            return vacio(g, x, y, ancho,
                    "Midiendo, pero todavia no se genero terreno nuevo. Caminá hacia una zona "
                            + "sin explorar.\n\n"
                            + "Si estas en un servidor remoto, esto no va a mostrar nada nunca: "
                            + "la generacion pasa alla y desde el cliente no se ve.");
        }

        y = fila(g, x, y, ancho, "Chunks generados",
                String.valueOf(MonitorWorldgen.chunksGenerados()), Paleta.TEXTO);
        y = fila(g, x, y, ancho, "Tiempo total en generacion",
                String.format(Locale.ROOT, "%.0f ms", MonitorWorldgen.totalMs()), Paleta.TEXTO);
        y = fila(g, x, y, ancho, "Por chunk",
                String.format(Locale.ROOT, "%.1f ms", MonitorWorldgen.msPorChunk()),
                MonitorWorldgen.msPorChunk() >= 20 ? Paleta.ERROR
                        : (MonitorWorldgen.msPorChunk() >= 8 ? Paleta.ADVERTENCIA : Paleta.OK));
        y = fila(g, x, y, ancho, "Midiendo desde hace",
                MonitorWorldgen.segundosMidiendo() + " s", Paleta.TEXTO_APAGADO);
        y += 8;

        List<MonitorWorldgen.Feature> lista = porMod
                ? MonitorWorldgen.porMod() : MonitorWorldgen.ranking();
        y = seccion(g, x, y, ancho, porMod ? "Costo por mod" : "Costo por feature");

        if (lista.isEmpty()) {
            return vacio(g, x, y, ancho, "Sin datos todavia.");
        }
        double maximo = lista.get(0).milisegundos();
        int tope = Math.min(lista.size(), porMod ? 40 : 60);

        for (int i = 0; i < tope; i++) {
            MonitorWorldgen.Feature f = lista.get(i);
            if (y > yContenido + altoVisible + 10) {
                y += porMod ? 18 : 28;
                continue;
            }
            int color = f.milisegundos() >= maximo * 0.5 ? Paleta.ADVERTENCIA : Paleta.NEUTRO;
            String valor = String.format(Locale.ROOT, "%.0f ms", f.milisegundos());

            y = barraDeRanking(g, x, y, ancho, porMod ? f.modId() : f.id(), valor,
                    (float) (f.milisegundos() / maximo), color);

            if (!porMod) {
                Widgets.lineaRecortada(g, this.font,
                        String.format(Locale.ROOT, "   %d colocaciones  ·  %.3f ms cada una",
                                f.colocaciones(), f.promedioMs()),
                        x, y, ancho, Paleta.TEXTO_APAGADO);
                y += 11;
            }
        }
        if (lista.size() > tope) {
            g.drawString(this.font, "... y " + (lista.size() - tope) + " mas",
                    x, y, Paleta.TEXTO_APAGADO, false);
            y += 12;
        }

        y += 6;
        y = seccion(g, x, y, ancho, "Que hacer con esto");
        y = Widgets.parrafo(g, this.font,
                "Casi todos los mods de mundo permiten desactivar features sueltos desde su "
                        + "config. Si uno se lleva la mitad del tiempo de generacion, ahi tenes "
                        + "un candidato concreto — y no hace falta sacar el mod entero, alcanza "
                        + "con apagar lo que no usas.\n\n"
                        + "Los cambios de generacion solo afectan a los chunks NUEVOS. El terreno "
                        + "que ya visitaste queda como esta.",
                x, y, ancho, Paleta.TEXTO_TENUE, 10);

        if (Minecraft.getInstance().isLocalServer()) {
            return y;
        }
        return Widgets.parrafo(g, this.font,
                "Estas en un servidor: lo de arriba puede estar vacio porque la generacion "
                        + "ocurre alla.",
                x, y + 4, ancho, Paleta.ADVERTENCIA, 3);
    }
}
