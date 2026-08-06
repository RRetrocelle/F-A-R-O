package com.coco.faro.client;

import com.coco.faro.diag.DetectorDeadlock;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Detector de congelamientos: el caso que no deja crash report.
 *
 * Cuando el juego se traba sin cerrarse, no hay nada que analizar despues: no se
 * escribe crash report, y matar el proceso no deja rastro. La unica forma de
 * diagnosticarlo es tener a alguien mirando MIENTRAS pasa, y eso es el watchdog.
 */
public class PantallaCongelamientos extends PantallaHerramienta {

    private static final DateTimeFormatter HORA =
            DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ZoneId.systemDefault());

    private int expandido = -1;

    public PantallaCongelamientos(Screen anterior) {
        super(anterior, "Congelamientos", "cuelgues y deadlocks, que no dejan crash report");
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        DetectorDeadlock d = DetectorDeadlock.get();
        int mitad = (ancho - 6) / 2;

        addRenderableWidget(Button.builder(Component.literal("Abrir congelamientos.log"),
                        b -> {
                            if (d.archivo() != null) {
                                Util.getPlatform().openFile(d.archivo().getParent().toFile());
                            }
                        })
                .bounds(x, y, mitad, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Copiar ultimo volcado"),
                        b -> {
                            List<DetectorDeadlock.Incidente> lista = d.incidentes();
                            if (lista.isEmpty()) {
                                AlertasSonoras.fallo();
                                return;
                            }
                            DetectorDeadlock.Incidente ultimo = lista.get(lista.size() - 1);
                            Minecraft.getInstance().keyboardHandler.setClipboard(
                                    ultimo.titulo() + "\n\n" + ultimo.volcado());
                            AlertasSonoras.listo();
                        })
                .bounds(x + mitad + 6, y, mitad, 20).build());
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        DetectorDeadlock d = DetectorDeadlock.get();
        List<DetectorDeadlock.Incidente> incidentes = d.incidentes();

        String texto = incidentes.isEmpty()
                ? "Sin congelamientos en esta sesion. El watchdog esta despierto y mirando."
                : incidentes.size() + " episodio(s) registrados. Los stacktraces quedaron en "
                  + "faro/congelamientos.log.";
        y = veredicto(g, x, y, ancho, texto,
                incidentes.isEmpty() ? Paleta.OK : Paleta.ERROR);

        // --- Estado del latido
        y = seccion(g, x, y, ancho, "Estado ahora");
        long sinLatido = d.msSinLatido();
        y = fila(g, x, y, ancho, "Ultimo latido del hilo principal",
                "hace " + sinLatido + " ms",
                sinLatido > 5000 ? Paleta.ADVERTENCIA : Paleta.OK);
        y = fila(g, x, y, ancho, "Episodios registrados", String.valueOf(incidentes.size()),
                incidentes.isEmpty() ? Paleta.OK : Paleta.ERROR);
        y += 6;

        y = Widgets.parrafo(g, this.font,
                "Como funciona: el hilo del juego marca un latido en cada tick. Un hilo aparte, "
                        + "de prioridad maxima, revisa cada 2 segundos que ese latido siga "
                        + "llegando. Si pasan 10 segundos sin latido, vuelca los stacktraces de "
                        + "todos los hilos importantes a disco.\n\n"
                        + "El watchdog corre con prioridad ALTA, al reves que el resto de Faro. "
                        + "Es a proposito: cuando el hilo principal acapara la CPU en un bucle, "
                        + "un vigilante de prioridad baja podria no ejecutarse nunca — justo "
                        + "cuando mas se lo necesita.\n\n"
                        + "Aparte, se consulta a la propia JVM si detecto un deadlock formal "
                        + "(dos hilos esperandose entre si). Eso no es interpretacion: cuando la "
                        + "JVM lo dice, es un hecho.",
                x, y, ancho, Paleta.TEXTO_TENUE, 16) + 8;

        if (incidentes.isEmpty()) {
            y = seccion(g, x, y, ancho, "Si el juego se te congela");
            return Widgets.parrafo(g, this.font,
                    "No cierres el proceso enseguida. Espera unos 15 segundos: el watchdog "
                            + "necesita ese tiempo para detectar el cuelgue y escribir el volcado. "
                            + "Despues si, matalo desde el administrador de tareas — el archivo "
                            + "ya va a estar en faro/congelamientos.log.\n\n"
                            + "Ese archivo dice exactamente en que linea de que mod se quedo "
                            + "trabado el juego. Es la unica forma de saberlo.",
                    x, y, ancho, Paleta.NEUTRO, 10);
        }

        // --- Lista de episodios
        y = seccion(g, x, y, ancho, "Episodios");
        for (int i = 0; i < incidentes.size(); i++) {
            DetectorDeadlock.Incidente inc = incidentes.get(i);
            boolean abierto = expandido == i;
            final int indice = i;

            int color = inc.tipo() == DetectorDeadlock.Tipo.DEADLOCK ? Paleta.ERROR : Paleta.ADVERTENCIA;

            Widgets.tarjeta(g, x, y, ancho, 34, color);
            Widgets.lineaRecortada(g, this.font, inc.titulo(), x + 7, y + 5, ancho - 14, color);
            Widgets.lineaRecortada(g, this.font,
                    HORA.format(Instant.ofEpochMilli(inc.momento()))
                            + (inc.duracionMs() > 0
                            ? "  ·  sin responder " + (inc.duracionMs() / 1000) + " s" : "")
                            + "  ·  " + inc.hilos().size() + " hilos",
                    x + 7, y + 16, ancho - 14, Paleta.TEXTO_TENUE);

            if (!inc.modsSospechosos().isEmpty()) {
                Widgets.lineaRecortada(g, this.font,
                        "en el volcado aparecen: " + String.join(", ", inc.modsSospechosos()),
                        x + 7, y + 25, ancho - 14, Paleta.TEXTO_APAGADO);
            }
            y += 38;

            boton(g, x + 4, y, abierto ? "Ocultar el volcado" : "Ver el volcado",
                    Paleta.NEUTRO, () -> expandido = (expandido == indice ? -1 : indice));
            y += 18;

            if (abierto) {
                String[] lineas = inc.volcado().split("\\R");
                int tope = Math.min(lineas.length, 60);
                for (int k = 0; k < tope; k++) {
                    if (y > yContenido + altoVisible + 10) {
                        y += 9;
                        continue;
                    }
                    String l = lineas[k];
                    int c = l.startsWith("\"") ? Paleta.TEXTO_TITULO
                            : (esDeMod(l) ? Paleta.ADVERTENCIA : Paleta.TEXTO_APAGADO);
                    Widgets.lineaRecortada(g, this.font, l, x + 6, y, ancho - 12, c);
                    y += 9;
                }
                if (lineas.length > tope) {
                    g.drawString(this.font, "... " + (lineas.length - tope)
                                    + " lineas mas en el archivo",
                            x + 6, y, Paleta.TEXTO_APAGADO, false);
                    y += 12;
                }
                y += 4;
            }
            y += 4;
        }

        y += 6;
        y = seccion(g, x, y, ancho, "Como leer el volcado");
        return Widgets.parrafo(g, this.font,
                "Cada bloque empieza con el nombre del hilo y su estado. Las lineas resaltadas "
                        + "en amarillo son las que NO son del juego ni de Java: ahi esta el "
                        + "codigo de mod que se quedo colgado.\n\n"
                        + "Si el estado dice BLOCKED y hay un 'bloqueado por', ese es un deadlock "
                        + "clasico: dos partes del codigo esperandose. Si dice RUNNABLE y la pila "
                        + "no cambia entre dos volcados, es un bucle infinito.",
                x, y, ancho, Paleta.TEXTO_TENUE, 10);
    }

    private static boolean esDeMod(String linea) {
        String t = linea.trim();
        if (!t.startsWith("at ")) {
            return false;
        }
        String c = t.substring(3);
        return !c.startsWith("java.") && !c.startsWith("jdk.") && !c.startsWith("sun.")
                && !c.startsWith("net.minecraft.") && !c.startsWith("com.mojang.")
                && !c.startsWith("io.netty.");
    }
}
