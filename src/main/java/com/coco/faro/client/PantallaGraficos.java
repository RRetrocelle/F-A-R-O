package com.coco.faro.client;

import com.coco.faro.diag.PresetGraficos;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Auto-configurador grafico.
 *
 * A diferencia de las guias genericas de "optimizacion", el preset sale del
 * hardware real y —sobre todo— de lo MEDIDO en esta sesion. Si el tick ya sufre,
 * eso pesa mas que cualquier especificacion del equipo.
 *
 * Nada se aplica solo: primero se muestra cada cambio con su valor viejo y el
 * nuevo, y el por que. Recien despues hay un boton.
 */
public class PantallaGraficos extends PantallaHerramienta {

    private PresetGraficos.Preset preset;
    private PresetGraficos.Nivel elegido;
    private String mensaje = "";

    public PantallaGraficos(Screen anterior) {
        super(anterior, "Preset grafico", "ajustes elegidos a partir de tu hardware y lo medido");
        recalcular();
    }

    private void recalcular() {
        preset = PresetGraficos.calcular();
        if (elegido == null) {
            elegido = preset.nivel();
        }
    }

    @Override
    protected int altoBotonera() {
        return 50;
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        int tercio = (ancho - 12) / 3;

        // Fila 1: elegir nivel.
        PresetGraficos.Nivel[] niveles = PresetGraficos.Nivel.values();
        for (int i = 0; i < niveles.length; i++) {
            PresetGraficos.Nivel n = niveles[i];
            Button b = Button.builder(
                            Component.literal((n == elegido ? "> " : "") + n.etiqueta),
                            btn -> {
                                elegido = n;
                                mensaje = "";
                                refrescar();
                            })
                    .bounds(x + (tercio + 6) * i, y, tercio, 20).build();
            addRenderableWidget(b);
        }

        // Fila 2: aplicar.
        int mitad = (ancho - 6) / 2;
        addRenderableWidget(Button.builder(
                        Component.literal("Aplicar a los ajustes del juego"),
                        btn -> {
                            int n = PresetGraficos.aplicarVanilla(elegido);
                            mensaje = n + " ajustes aplicados. Los cambios de video ya estan "
                                    + "activos; los de Embeddium necesitan reiniciar.";
                            AlertasSonoras.listo();
                            recalcular();
                            refrescar();
                        })
                .bounds(x, y + 24, mitad, 20).build());

        Button embeddium = Button.builder(Component.literal("Aplicar a Embeddium"),
                        btn -> {
                            mensaje = PresetGraficos.aplicarEmbeddium(elegido);
                            AlertasSonoras.listo();
                            recalcular();
                            refrescar();
                        })
                .bounds(x + mitad + 6, y + 24, mitad, 20).build();
        embeddium.active = preset != null && preset.hayEmbeddium();
        addRenderableWidget(embeddium);
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        if (preset == null) {
            return vacio(g, x, y, ancho, "No pude calcular el preset.");
        }
        if (!mensaje.isEmpty()) {
            y = veredicto(g, x, y, ancho, mensaje, Paleta.OK);
        }

        // --- Recomendacion
        int color = switch (preset.nivel()) {
            case MINIMO -> Paleta.ADVERTENCIA;
            case EQUILIBRADO -> Paleta.NEUTRO;
            case CALIDAD -> Paleta.OK;
        };
        y = veredicto(g, x, y, ancho,
                "Recomendado para tu equipo: " + preset.nivel().etiqueta
                        + ". " + preset.nivel().descripcion, color);

        if (elegido != preset.nivel()) {
            y = Widgets.parrafo(g, this.font,
                    "Estas viendo el preset '" + elegido.etiqueta + "', que no es el recomendado. "
                            + elegido.descripcion,
                    x, y, ancho, Paleta.ADVERTENCIA, 4) + 4;
        }

        // --- Por que
        y = seccion(g, x, y, ancho, "De donde sale esta recomendacion");
        for (String razon : preset.razones()) {
            boolean medido = razon.startsWith("MEDIDO:");
            y = Widgets.parrafo(g, this.font, "· " + razon, x, y, ancho,
                    medido ? Paleta.TEXTO_TITULO : Paleta.TEXTO_TENUE, 5);
        }
        y += 4;

        if (!preset.datosFaltantes().isEmpty()) {
            y = Widgets.parrafo(g, this.font,
                    "No pude leer: " + String.join("; ", preset.datosFaltantes())
                            + ". Cuando falta un dato, el preset se queda del lado conservador "
                            + "en vez de asumir lo mejor.",
                    x, y, ancho, Paleta.TEXTO_APAGADO, 6) + 4;
        }

        y = Widgets.parrafo(g, this.font,
                "Lo marcado como MEDIDO pesa mas que el resto, a proposito: si el tick ya sufre, "
                        + "no importa lo que diga la ficha tecnica del equipo.",
                x, y, ancho, Paleta.TEXTO_APAGADO, 4) + 8;

        // --- Cambios
        List<PresetGraficos.Ajuste> todos = PresetGraficos.calcular().ajustes();
        // Se recalcula con el nivel elegido para ver la vista previa correcta.
        y = seccion(g, x, y, ancho, "Que va a cambiar");

        String archivoActual = "";
        int cambios = 0;
        for (PresetGraficos.Ajuste a : ajustesDe(elegido)) {
            if (!a.archivo().equals(archivoActual)) {
                archivoActual = a.archivo();
                y += 4;
                g.drawString(this.font, archivoActual, x, y, Paleta.VIOLETA, false);
                y += 12;
            }
            if (y > yContenido + altoVisible + 10) {
                y += 22;
                continue;
            }
            boolean cambia = a.cambia();
            if (cambia) {
                cambios++;
            }
            int c = cambia ? Paleta.TEXTO : Paleta.TEXTO_APAGADO;

            Widgets.lineaRecortada(g, this.font, a.clave(), x + 4, y, ancho - 130, c);
            String valor = cambia ? (a.valorActual() + "  ->  " + a.valorNuevo())
                    : (a.valorActual() + "  (ya esta)");
            g.drawString(this.font, valor, x + ancho - this.font.width(valor), y,
                    cambia ? Paleta.OK : Paleta.TEXTO_APAGADO, false);
            y += 10;

            if (!a.porQue().isEmpty()) {
                y = Widgets.parrafo(g, this.font, "   " + a.porQue(), x + 4, y, ancho - 8,
                        Paleta.TEXTO_APAGADO, 3);
            }
            y += 3;
        }

        y += 6;
        y = veredicto(g, x, y, ancho, cambios + " ajustes cambiarian con el preset '"
                + elegido.etiqueta + "'.", Paleta.NEUTRO);

        // --- Embeddium
        y = seccion(g, x, y, ancho, "Embeddium");
        if (preset.hayEmbeddium()) {
            y = Widgets.parrafo(g, this.font,
                    "Detectado. Faro escribe solo las claves que entiende en "
                            + "config/embeddium-options.json, respetando el resto del archivo, "
                            + "y deja un respaldo (.faro-backup) antes de tocarlo.\n\n"
                            + "Embeddium lee ese archivo al arrancar: hay que reiniciar el juego "
                            + "para que tome efecto.",
                    x, y, ancho, Paleta.TEXTO_TENUE, 8) + 4;

            java.nio.file.Path archivo = PresetGraficos.archivoEmbeddium();
            if (archivo != null && java.nio.file.Files.isRegularFile(archivo)) {
                boton(g, x, y, "Abrir la carpeta de config", Paleta.NEUTRO,
                        () -> Util.getPlatform().openFile(archivo.getParent().toFile()));
                y += 22;
            }
        } else {
            y = Widgets.parrafo(g, this.font,
                    "No esta instalado. Embeddium reemplaza el motor de renderizado de vanilla y "
                            + "es, de lejos, lo que mas FPS suma en un pack grande. Si tenes "
                            + "Rubidium, Embeddium es su sucesor directo.\n\n"
                            + "Sin el, el preset solo toca los ajustes de video de vanilla, que "
                            + "igual son los que mas pesan.",
                    x, y, ancho, Paleta.TEXTO_TENUE, 10);
        }
        return y + 8;
    }

    /** Vista previa de los ajustes del nivel elegido, no del recomendado. */
    private List<PresetGraficos.Ajuste> ajustesDe(PresetGraficos.Nivel nivel) {
        if (nivel == preset.nivel()) {
            return preset.ajustes();
        }
        // El calculo del preset elige el nivel; para previsualizar otro se arma
        // uno nuevo con los mismos datos y se le pisa el nivel.
        return PresetGraficos.ajustesPara(nivel);
    }
}
