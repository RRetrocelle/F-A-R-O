package com.coco.faro.client;

import com.coco.faro.config.ConfigFaro;
import com.coco.faro.diag.Integraciones;
import com.coco.faro.diag.ProfilerZonas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Profiler de zonas: que parte del mundo te esta costando el rendimiento.
 *
 * Complementa a spark en vez de competirle. spark responde "que metodo consume
 * CPU"; esto responde "que LUGAR". Si spark esta instalado, la pantalla lo dice y
 * manda ahi para lo que hace mejor.
 */
public class PantallaZonas extends PantallaHerramienta {

    public PantallaZonas(Screen anterior) {
        super(anterior, "Profiler de zonas",
                "que chunk concentra la carga, y con que adentro");
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        int mitad = (ancho - 6) / 2;
        ProfilerZonas p = ProfilerZonas.get();

        addRenderableWidget(Button.builder(
                        Component.literal(p.activo() ? "Dejar de medir" : "Empezar a medir"),
                        b -> {
                            boolean nuevo = !p.activo();
                            p.activar(nuevo);
                            ConfigFaro.INSTANCIA.profilerZonas.set(nuevo);
                            ConfigFaro.INSTANCIA.profilerZonas.save();
                            refrescar();
                        })
                .bounds(x, y, mitad, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Reiniciar muestras"),
                        b -> {
                            p.reiniciar();
                            refrescar();
                        })
                .bounds(x + mitad + 6, y, mitad, 20).build());
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        ProfilerZonas p = ProfilerZonas.get();
        Minecraft mc = Minecraft.getInstance();

        y = veredicto(g, x, y, ancho, p.veredicto(),
                p.activo() ? (p.muestrasTotales() < 10 ? Paleta.NEUTRO : Paleta.ADVERTENCIA)
                        : Paleta.TEXTO_APAGADO);

        if (mc.level == null) {
            return vacio(g, x, y, ancho,
                    "Tenes que estar dentro de un mundo. Desde el menu no hay nada que muestrear.");
        }

        y = fila(g, x, y, ancho, "Muestras tomadas", String.valueOf(p.muestrasTotales()),
                Paleta.TEXTO_TENUE);
        y = fila(g, x, y, ancho, "Midiendo desde hace", p.segundosMidiendo() + " s",
                Paleta.TEXTO_APAGADO);
        y += 8;

        // --- Metodo, explicado antes de mostrar los numeros.
        y = seccion(g, x, y, ancho, "Como se mide, y que significa");
        y = Widgets.parrafo(g, this.font,
                "Cada medio segundo se cuenta, por chunk, cuantas entidades hay y cuantos "
                        + "bloques con logica — separando los que ejecutan codigo en cada tick de "
                        + "los que son decorativos. Al mismo tiempo se anota cuanto duro el tick. "
                        + "Si se paso de 50 ms, esa muestra queda marcada.\n\n"
                        + "Los chunks que aparecen marcados una y otra vez son la zona caliente. "
                        + "Es CORRELACION, no prueba de causa — pero cuando un chunk concentra 400 "
                        + "entidades y aparece en el 80% de los tirones, no hay mucho que discutir.",
                x, y, ancho, Paleta.TEXTO_TENUE, 12) + 6;

        if (Integraciones.haySpark()) {
            y = Widgets.parrafo(g, this.font,
                    "Tenes spark instalado. spark responde otra pregunta —que METODO consume "
                            + "CPU— y lo hace mejor que cualquier cosa que Faro pueda inventar. "
                            + "Usá /spark profiler start, jugá un rato, /spark profiler stop. "
                            + "Esto de aca te dice DONDE mirar; spark te dice QUE esta pasando ahi.",
                    x, y, ancho, Paleta.VIOLETA, 8) + 6;
        }

        if (!p.activo()) {
            return y;
        }

        // --- Zonas calientes
        List<ProfilerZonas.Zona> calientes = p.calientes(12);
        y = seccion(g, x, y, ancho, "Zonas mas cargadas");

        if (calientes.isEmpty()) {
            y = vacio(g, x, y, ancho, "Todavia no hay muestras. Jugá un rato.");
        } else {
            double maximo = calientes.get(0).puntaje();
            for (ProfilerZonas.Zona z : calientes) {
                if (y > yContenido + altoVisible + 10) {
                    y += 46;
                    continue;
                }
                int color = z.fraccionCaliente() > 0.5 ? Paleta.ERROR
                        : (z.fraccionCaliente() > 0.2 ? Paleta.ADVERTENCIA : Paleta.NEUTRO);

                Widgets.lineaRecortada(g, this.font, "chunk " + z.chunkX() + ", " + z.chunkZ(),
                        x, y, ancho - 90, Paleta.TEXTO);
                String tirones = String.format(Locale.ROOT, "%.0f%% tirones",
                        z.fraccionCaliente() * 100);
                g.drawString(this.font, tirones, x + ancho - this.font.width(tirones), y,
                        color, false);
                y += 10;

                Widgets.barra(g, x, y, ancho, 4, (float) (z.puntaje() / maximo), color);
                y += 8;

                Widgets.lineaRecortada(g, this.font,
                        String.format(Locale.ROOT,
                                "  %d entidades  ·  %d bloques con logica (%d en total)  ·  ir a %s",
                                z.entidades(), z.blockEntitiesConTicker(), z.blockEntities(),
                                z.coordenadas()),
                        x, y, ancho, Paleta.TEXTO_TENUE);
                y += 10;

                List<Map.Entry<String, Integer>> tipos = z.tiposDominantes(3);
                if (!tipos.isEmpty()) {
                    StringBuilder sb = new StringBuilder("  ");
                    for (int i = 0; i < tipos.size(); i++) {
                        if (i > 0) {
                            sb.append("  ·  ");
                        }
                        sb.append(tipos.get(i).getValue()).append("x ").append(tipos.get(i).getKey());
                    }
                    Widgets.lineaRecortada(g, this.font, sb.toString(), x, y, ancho,
                            Paleta.TEXTO_APAGADO);
                    y += 10;
                }
                y += 6;
            }
        }

        // --- Que hay dando vueltas, en todo el mundo cargado.
        y += 4;
        y = seccion(g, x, y, ancho, "Que entidades abundan");
        List<Map.Entry<String, Integer>> globales = p.tiposGlobales(15);
        if (globales.isEmpty()) {
            y = vacio(g, x, y, ancho, "Sin datos.");
        } else {
            int max = globales.get(0).getValue();
            for (Map.Entry<String, Integer> e : globales) {
                if (y > yContenido + altoVisible + 10) {
                    y += 18;
                    continue;
                }
                String modId = e.getKey().contains(":")
                        ? e.getKey().substring(0, e.getKey().indexOf(':')) : "?";
                int color = modId.equals("minecraft") ? Paleta.TEXTO_TENUE : Paleta.NEUTRO;
                y = barraDeRanking(g, x, y, ancho, e.getKey(), String.valueOf(e.getValue()),
                        e.getValue() / (float) max, color);
            }
            y = Widgets.parrafo(g, this.font,
                    "Los conteos son maximos por chunk sumados, no un censo exacto del mundo: "
                            + "sirven para comparar entre si, no como numero absoluto.",
                    x, y + 2, ancho, Paleta.TEXTO_APAGADO, 4) + 6;
        }

        y = seccion(g, x, y, ancho, "Que hacer con una zona caliente");
        return Widgets.parrafo(g, this.font,
                "Si son entidades: buscá granjas de mobs, items tirados en el piso, o mobs "
                        + "atrapados. Un cofre lleno de items sueltos alrededor cuesta mas que "
                        + "cualquier maquina.\n\n"
                        + "Si son bloques con logica: son maquinas de mods de tecnologia. No hace "
                        + "falta sacarlas, muchas veces alcanza con espaciarlas o apagar las que "
                        + "no estan produciendo.\n\n"
                        + "Y si el chunk caliente es donde tenes la base entera, eso es esperable: "
                        + "el dato util ahi es COMPARAR contra el resto, no el numero solo.",
                x, y, ancho, Paleta.TEXTO_TENUE, 14);
    }
}
