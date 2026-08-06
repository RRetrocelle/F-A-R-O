package com.coco.faro.client;

import com.coco.faro.diag.Benchmark;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Benchmark del modpack.
 *
 * "Me da 60 FPS" no significa nada solo. La prueba fija los ajustes que mas pesan,
 * gira la camara a velocidad constante y descarta el calentamiento, para que dos
 * corridas sean comparables de verdad.
 *
 * Lo unico que no puede estandarizar es el lugar — teletransportar exige trucos.
 * Por eso guarda la coordenada de cada corrida y avisa cuando dos no son
 * comparables, en vez de dejar que el usuario compare peras con manzanas.
 */
public class PantallaBenchmark extends PantallaHerramienta {

    private static final DateTimeFormatter FECHA =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    public PantallaBenchmark(Screen anterior) {
        super(anterior, "Benchmark", "un numero de FPS que se pueda comparar de verdad");
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        Benchmark b = Benchmark.get();
        int mitad = (ancho - 6) / 2;

        if (b.corriendo()) {
            addRenderableWidget(Button.builder(Component.literal("Cancelar la prueba"),
                            btn -> {
                                b.cancelar();
                                refrescar();
                            })
                    .bounds(x, y, ancho, 20).build());
            return;
        }

        Button empezar = Button.builder(
                        Component.literal("Empezar (" + Benchmark.SEGUNDOS_TOTAL + " s)"),
                        btn -> {
                            b.iniciar();
                            // Se cierra la pantalla: durante la prueba la camara gira
                            // y con un menu abierto no se dibujaria el mundo.
                            Minecraft.getInstance().setScreen(null);
                        })
                .bounds(x, y, mitad, 20).build();
        empezar.active = b.impedimento() == null;
        addRenderableWidget(empezar);

        Button copiar = Button.builder(Component.literal("Copiar resultado"),
                        btn -> {
                            Minecraft.getInstance().keyboardHandler.setClipboard(texto(b));
                            AlertasSonoras.listo();
                        })
                .bounds(x + mitad + 6, y, mitad, 20).build();
        copiar.active = b.ultimo() != null;
        addRenderableWidget(copiar);
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        Benchmark b = Benchmark.get();

        String impedimento = b.impedimento();
        int color = b.corriendo() ? Paleta.ADVERTENCIA
                : (impedimento != null && !b.corriendo() ? Paleta.TEXTO_APAGADO : Paleta.OK);
        y = veredicto(g, x, y, ancho,
                impedimento != null && !b.corriendo() ? impedimento : b.estadoTexto(), color);

        if (b.corriendo()) {
            Widgets.barra(g, x, y, ancho, 8, (float) b.progreso(), Paleta.NEUTRO);
            y += 16;
            y = Widgets.parrafo(g, this.font,
                    "Cerra esta pantalla para que se dibuje el mundo: con un menu abierto la "
                            + "prueba no mide nada real.",
                    x, y, ancho, Paleta.ADVERTENCIA, 3) + 6;
        }

        // --- Que hace la prueba
        y = seccion(g, x, y, ancho, "Que estandariza, exactamente");
        y = Widgets.parrafo(g, this.font,
                "1) Fija distancia de render en " + Benchmark.DISTANCIA_RENDER + " y simulacion en "
                        + Benchmark.DISTANCIA_SIMULACION + ", apaga nubes, baja particulas y "
                        + "desactiva VSync. Anota los tuyos y los devuelve al terminar, pase lo "
                        + "que pase — tambien si cancelas.\n\n"
                        + "2) Gira la camara 360 grados a velocidad constante, para recorrer todo "
                        + "el entorno en vez de medir un solo angulo.\n\n"
                        + "3) Descarta los primeros " + Benchmark.SEGUNDOS_CALENTAMIENTO
                        + " segundos: al empezar a girar hay que construir mallas de chunks que "
                        + "no estaban dibujadas, y eso no representa como se juega.\n\n"
                        + "4) Reporta promedio, 1% bajo y peor cuadro.",
                x, y, ancho, Paleta.TEXTO_TENUE, 16) + 6;

        y = Widgets.parrafo(g, this.font,
                "VSync se apaga porque limitaria los FPS al refresco de tu monitor y taparia "
                        + "justo lo que queremos medir. El 1% bajo es el numero que importa: son "
                        + "los tirones, y es lo que realmente se siente al jugar. El promedio los "
                        + "esconde.",
                x, y, ancho, Paleta.TEXTO_APAGADO, 8) + 8;

        // --- Ultimo resultado
        Benchmark.Resultado r = b.ultimo();
        if (r != null) {
            y = seccion(g, x, y, ancho, "Ultimo resultado");

            int colorRes = r.fps1PorCientoBajo() >= 30 ? Paleta.OK
                    : (r.fps1PorCientoBajo() >= 20 ? Paleta.ADVERTENCIA : Paleta.ERROR);

            Widgets.tarjeta(g, x, y, ancho, 54, colorRes);
            int fy = y + 6;
            fy = grande(g, x + 8, fy, ancho, "FPS promedio", r.fpsPromedio(), Paleta.TEXTO);
            fy = grande(g, x + 8, fy, ancho, "1% bajo (los tirones)",
                    r.fps1PorCientoBajo(), colorRes);
            fy = grande(g, x + 8, fy, ancho, "Peor cuadro", r.fpsPeor(), Paleta.TEXTO_TENUE);
            grande(g, x + 8, fy, ancho, "Mejor cuadro", r.fpsMejor(), Paleta.TEXTO_APAGADO);
            y += 60;

            y = Widgets.parrafo(g, this.font, r.veredicto(), x, y, ancho, colorRes, 4) + 4;

            y = fila(g, x, y, ancho, "Cuadros medidos", String.valueOf(r.cuadros()),
                    Paleta.TEXTO_APAGADO);
            y = fila(g, x, y, ancho, "Lugar", r.dimension(), Paleta.TEXTO_APAGADO);
            y = fila(g, x, y, ancho, "Coordenadas", r.coordenadas(), Paleta.TEXTO_APAGADO);
            y = fila(g, x, y, ancho, "Mods cargados", String.valueOf(r.modsCargados()),
                    Paleta.TEXTO_APAGADO);
            y = fila(g, x, y, ancho, "Cuando", FECHA.format(Instant.ofEpochMilli(r.momento())),
                    Paleta.TEXTO_APAGADO);
            y += 8;
        }

        // --- Historial
        List<Benchmark.Resultado> historial = b.historial();
        if (historial.size() > 1) {
            y = seccion(g, x, y, ancho, "Corridas anteriores");
            double maximo = historial.stream()
                    .mapToDouble(Benchmark.Resultado::fpsPromedio).max().orElse(1);

            for (int i = historial.size() - 1; i >= 0; i--) {
                Benchmark.Resultado h = historial.get(i);
                if (y > yContenido + altoVisible + 10) {
                    y += 28;
                    continue;
                }
                boolean mismoLugar = r != null && h.dimension().equals(r.dimension())
                        && h.coordenadas().equals(r.coordenadas());

                y = barraDeRanking(g, x, y, ancho,
                        FECHA.format(Instant.ofEpochMilli(h.momento()))
                                + (mismoLugar ? "" : "  (otro lugar)"),
                        String.format(Locale.ROOT, "%.0f / %.0f FPS",
                                h.fpsPromedio(), h.fps1PorCientoBajo()),
                        (float) (h.fpsPromedio() / maximo),
                        mismoLugar ? Paleta.NEUTRO : Paleta.TEXTO_APAGADO);
            }
            y = Widgets.parrafo(g, this.font,
                    "Las marcadas como 'otro lugar' NO son comparables con la ultima. Comparar "
                            + "el FPS de una llanura vacia con el de una base llena de maquinas "
                            + "no dice nada, por mas que los ajustes hayan sido identicos.",
                    x, y + 4, ancho, Paleta.ADVERTENCIA, 6) + 6;
        }

        // --- El limite
        y = seccion(g, x, y, ancho, "Lo que la prueba NO puede estandarizar");
        return Widgets.parrafo(g, this.font,
                "El lugar. Faro no puede teletransportarte sin trucos, asi que la comparabilidad "
                        + "depende de que corras la prueba SIEMPRE EN EL MISMO PUNTO. Se guarda "
                        + "la coordenada de cada corrida y se avisa cuando no coinciden.\n\n"
                        + "Consejo practico: elegí un punto fijo — la puerta de tu base, por "
                        + "ejemplo — y corré la prueba ahi antes y despues de cada cambio grande. "
                        + "Ese es el numero que sirve para decidir si un mod valio la pena.",
                x, y, ancho, Paleta.TEXTO_TENUE, 12);
    }

    private int grande(GuiGraphics g, int x, int y, int ancho, String etiqueta,
                       double valor, int color) {
        g.drawString(this.font, etiqueta, x, y, Paleta.TEXTO_APAGADO, false);
        String v = String.format(Locale.ROOT, "%.1f", valor);
        g.drawString(this.font, v, x + ancho - this.font.width(v) - 16, y, color, false);
        return y + 12;
    }

    private static String texto(Benchmark b) {
        Benchmark.Resultado r = b.ultimo();
        if (r == null) {
            return "";
        }
        return String.format(Locale.ROOT,
                "=== Faro — benchmark ===%n"
                        + "FPS promedio: %.1f%n1%% bajo: %.1f%nPeor: %.1f   Mejor: %.1f%n"
                        + "Cuadros: %d%nLugar: %s @ %s%nMods cargados: %d%n"
                        + "Ajustes fijos: render %d, simulacion %d, sin VSync, sin nubes, "
                        + "particulas minimas%n%s%n",
                r.fpsPromedio(), r.fps1PorCientoBajo(), r.fpsPeor(), r.fpsMejor(),
                r.cuadros(), r.dimension(), r.coordenadas(), r.modsCargados(),
                Benchmark.DISTANCIA_RENDER, Benchmark.DISTANCIA_SIMULACION, r.veredicto());
    }
}
