package com.coco.faro.client;

import com.coco.faro.diag.AnalizadorMixins;
import com.coco.faro.diag.MotorDiagnostico;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;

/**
 * Visualizador de conflictos de Mixins.
 *
 * Muestra que clases del juego estan siendo parcheadas por varios mods a la vez.
 * El dato es estructural —sale del bytecode de cada mixin— y por eso se presenta
 * con certeza alta. Lo que NO se afirma es que compartir una clase implique un
 * conflicto: lo normal es que convivan. El texto de la pantalla insiste en esa
 * diferencia, porque confundirla llevaria a desinstalar mods que funcionan bien.
 */
public class PantallaMixins extends PantallaHerramienta {

    private boolean soloCompartidos = true;
    private boolean soloDelCrash = false;

    public PantallaMixins(Screen anterior) {
        super(anterior, "Mixins",
                "que mods estan parcheando las mismas clases del juego");
    }

    @Override
    protected int altoBotonera() {
        return 26;
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        int mitad = (ancho - 6) / 2;
        addRenderableWidget(Button.builder(
                        Component.literal(soloCompartidos ? "Ver todos" : "Ver solo compartidos"),
                        b -> {
                            soloCompartidos = !soloCompartidos;
                            refrescar();
                        })
                .bounds(x, y, mitad, 20).build());

        addRenderableWidget(Button.builder(
                        Component.literal(soloDelCrash ? "Ver todos los objetivos"
                                : "Solo los del ultimo crash"),
                        b -> {
                            soloDelCrash = !soloDelCrash;
                            refrescar();
                        })
                .bounds(x + mitad + 6, y, mitad, 20).build());
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null || !motor.listo()) {
            return vacio(g, x, y, ancho, "El analisis todavia esta corriendo. Volvé en unos segundos.");
        }
        AnalizadorMixins.Reporte r = motor.mixins();
        if (r == null || r.parches().isEmpty()) {
            return vacio(g, x, y, ancho,
                    "No encontre ningun mixin en los jars instalados. Eso es raro en un pack "
                            + "de 1.20.1: puede que el escaneo no haya podido leer el bytecode. "
                            + "Fijate en latest.log si hay errores de Faro.");
        }

        y = veredicto(g, x, y, ancho, resumen(r), Paleta.NEUTRO);

        y = seccion(g, x, y, ancho, "Que estas viendo");
        y = Widgets.parrafo(g, this.font,
                "Un mixin es un parche que un mod aplica sobre una clase del juego. Los "
                        + "objetivos que ves aca salen del bytecode de cada mod: es lo que "
                        + "declara que va a parchear, no una suposicion.\n\n"
                        + "Que dos mods toquen la misma clase NO significa que choquen. Dos "
                        + "parches en metodos distintos conviven perfecto. Esto se vuelve util "
                        + "cuando hay un crash en una de estas clases: ahi ya sabes quienes "
                        + "estaban metiendo mano.",
                x, y, ancho, Paleta.TEXTO_TENUE, 12) + 8;

        // --- Sospechosos del crash, si los hay.
        List<AnalizadorMixins.Objetivo> delCrash = motor.diagnostico()
                .map(d -> AnalizadorMixins.sospechososEn(r, d.lineasStack()))
                .orElse(List.of());

        if (!delCrash.isEmpty()) {
            y = seccion(g, x, y, ancho, "Clases compartidas que aparecen en el ultimo crash");
            y = Widgets.parrafo(g, this.font,
                    "Estas clases fallaron Y estaban parcheadas por mas de un mod. Es la unica "
                            + "combinacion que justifica sospechar de un conflicto de mixins.",
                    x, y, ancho, Paleta.ERROR, 4) + 4;
            for (AnalizadorMixins.Objetivo o : delCrash) {
                y = dibujarObjetivo(g, x, y, ancho, o, true);
            }
            y += 6;
        }

        if (soloDelCrash) {
            if (delCrash.isEmpty()) {
                y = vacio(g, x, y, ancho,
                        "Ninguna clase compartida aparece en el ultimo crash. Si hubo un crash, "
                                + "no vino de dos mixins peleandose por la misma clase.");
            }
            return y;
        }

        // --- Ranking por mod.
        y = seccion(g, x, y, ancho, "Cuantos mixins aporta cada mod");
        List<Map.Entry<String, Integer>> ranking = r.ranking(12);
        if (!ranking.isEmpty()) {
            int max = ranking.get(0).getValue();
            for (Map.Entry<String, Integer> e : ranking) {
                y = barraDeRanking(g, x, y, ancho, e.getKey(), e.getValue() + " mixins",
                        e.getValue() / (float) max,
                        e.getValue() > 60 ? Paleta.ADVERTENCIA : Paleta.NEUTRO);
            }
            y = Widgets.parrafo(g, this.font,
                    "Muchos mixins no es malo por si mismo: los mods de optimizacion y las "
                            + "librerias grandes necesariamente parchean mucho. Es un indicador "
                            + "de cuanto se mete cada mod en el juego, nada mas.",
                    x, y + 4, ancho, Paleta.TEXTO_APAGADO, 4) + 8;
        }

        // --- Lista de objetivos.
        List<AnalizadorMixins.Objetivo> lista = soloCompartidos ? r.compartidos() : r.objetivos();
        y = seccion(g, x, y, ancho,
                (soloCompartidos ? "Clases tocadas por varios mods (" : "Todas las clases parcheadas (")
                        + lista.size() + ")");

        if (lista.isEmpty()) {
            return vacio(g, x, y, ancho,
                    "Ninguna clase del juego esta siendo parcheada por mas de un mod. "
                            + "Eso es lo ideal, y bastante infrecuente en un pack grande.");
        }

        // Con muchos objetivos se limita lo dibujado: la lista completa serian
        // miles de filas y no se puede leer de todos modos.
        int tope = Math.min(lista.size(), soloCompartidos ? 120 : 250);
        for (int i = 0; i < tope; i++) {
            y = dibujarObjetivo(g, x, y, ancho, lista.get(i), false);
        }
        if (lista.size() > tope) {
            g.drawString(this.font, "... y " + (lista.size() - tope) + " mas",
                    x, y, Paleta.TEXTO_APAGADO, false);
            y += 12;
        }

        if (!r.jarsIlegibles().isEmpty()) {
            y += 8;
            y = seccion(g, x, y, ancho, "Jars que no pude leer (" + r.jarsIlegibles().size() + ")");
            for (String s : r.jarsIlegibles()) {
                Widgets.lineaRecortada(g, this.font, "· " + s, x + 4, y, ancho - 8,
                        Paleta.TEXTO_APAGADO);
                y += 10;
            }
        }
        return y;
    }

    private int dibujarObjetivo(GuiGraphics g, int x, int y, int ancho,
                                AnalizadorMixins.Objetivo o, boolean resaltar) {
        // Salteo barato de lo que queda fuera del recorte: con cientos de filas,
        // dibujar lo que no se ve es trabajo tirado.
        if (y < yContenido - 30 || y > yContenido + altoVisible + 10) {
            return y + (o.compartido() ? 32 : 22);
        }

        int mods = o.mods().size();
        int color = resaltar ? Paleta.ERROR
                : (mods >= 4 ? Paleta.ADVERTENCIA : (mods > 1 ? Paleta.VIOLETA : Paleta.TEXTO_TENUE));

        g.fill(x, y, x + 2, y + 9, color);
        Widgets.lineaRecortada(g, this.font, o.nombreCorto(), x + 6, y, ancho - 60, Paleta.TEXTO);
        String contador = mods + (mods == 1 ? " mod" : " mods");
        g.drawString(this.font, contador, x + ancho - this.font.width(contador), y, color, false);
        registrarAyuda(x + 6, y, ancho - 12, 9, "mixin");
        y += 10;

        Widgets.lineaRecortada(g, this.font, o.paquete(), x + 8, y, ancho - 16, Paleta.TEXTO_APAGADO);
        y += 10;

        if (o.compartido()) {
            Widgets.lineaRecortada(g, this.font, String.join("  ·  ", o.mods()),
                    x + 8, y, ancho - 16, color);
            y += 12;
        } else {
            y += 2;
        }
        return y;
    }

    private static String resumen(AnalizadorMixins.Reporte r) {
        int compartidos = r.compartidos().size();
        if (compartidos == 0) {
            return r.parches().size() + " mixins de " + r.cantidadMods()
                    + " mods sobre " + r.objetivos().size() + " clases distintas. "
                    + "Ninguna clase esta compartida: cada mod parchea lo suyo.";
        }
        return r.parches().size() + " mixins de " + r.cantidadMods() + " mods. "
                + compartidos + " clases del juego estan parcheadas por mas de un mod a la vez. "
                + "Eso es normal en un pack grande — se vuelve relevante solo si el crash "
                + "apunta a una de ellas.";
    }
}
