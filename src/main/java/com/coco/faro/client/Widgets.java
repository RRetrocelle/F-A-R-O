package com.coco.faro.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Piezas de dibujo reutilizables.
 *
 * Estan aca para que las pantallas se ocupen de QUE mostrar y no de como pintar
 * un borde. Todo se dibuja con rectangulos (GuiGraphics.fill), que es barato:
 * ninguna de estas funciones crea objetos por frame ni toca texturas.
 */
public final class Widgets {

    private Widgets() {
    }

    // ------------------------------------------------------------------ paneles

    /** Tarjeta con borde tenue y una linea de acento a la izquierda. */
    public static void tarjeta(GuiGraphics g, int x, int y, int ancho, int alto, int acento) {
        g.fill(x, y, x + ancho, y + alto, Paleta.FONDO_TARJETA);
        borde(g, x, y, ancho, alto, Paleta.BORDE_SUAVE);
        g.fill(x, y, x + 2, y + alto, acento);
    }

    /** Solo el contorno, un pixel. */
    public static void borde(GuiGraphics g, int x, int y, int ancho, int alto, int color) {
        g.fill(x, y, x + ancho, y + 1, color);
        g.fill(x, y + alto - 1, x + ancho, y + alto, color);
        g.fill(x, y, x + 1, y + alto, color);
        g.fill(x + ancho - 1, y, x + ancho, y + alto, color);
    }

    /** Degradado vertical simple, dibujado por bandas. */
    public static void degradadoVertical(GuiGraphics g, int x, int y, int ancho, int alto,
                                         int arriba, int abajo) {
        int bandas = Math.min(alto, 24);
        if (bandas <= 0) {
            return;
        }
        int altoBanda = Math.max(1, alto / bandas);
        for (int i = 0; i < bandas; i++) {
            int y0 = y + i * altoBanda;
            int y1 = (i == bandas - 1) ? y + alto : y0 + altoBanda;
            g.fill(x, y0, x + ancho, y1, Paleta.mezclar(arriba, abajo, i / (float) (bandas - 1)));
        }
    }

    // ------------------------------------------------------------------ badges

    /** Etiqueta chica con fondo, tipo "CRITICO" o "Alta". */
    public static int badge(GuiGraphics g, Font font, String texto, int x, int y, int color) {
        int ancho = font.width(texto) + 8;
        g.fill(x, y, x + ancho, y + 11, Paleta.conAlfa(color, 0.22f));
        borde(g, x, y, ancho, 11, Paleta.conAlfa(color, 0.55f));
        g.drawString(font, texto, x + 4, y + 2, color, false);
        return ancho;
    }

    // ------------------------------------------------------------------ barras

    /** Barra de progreso horizontal. fraccion va de 0 a 1. */
    public static void barra(GuiGraphics g, int x, int y, int ancho, int alto,
                             float fraccion, int color) {
        fraccion = Math.max(0f, Math.min(1f, fraccion));
        g.fill(x, y, x + ancho, y + alto, Paleta.FONDO_PANEL);
        borde(g, x, y, ancho, alto, Paleta.BORDE_SUAVE);
        int relleno = (int) ((ancho - 2) * fraccion);
        if (relleno > 0) {
            g.fill(x + 1, y + 1, x + 1 + relleno, y + alto - 1, color);
        }
    }

    /**
     * Grafico de barras verticales para la historia de tiempos de tick.
     * Marca con una linea el umbral de 50 ms, que es el limite real de un tick.
     */
    public static void grafico(GuiGraphics g, int x, int y, int ancho, int alto,
                               List<Double> valores, double maximo, double umbral) {
        g.fill(x, y, x + ancho, y + alto, Paleta.FONDO_PANEL);
        borde(g, x, y, ancho, alto, Paleta.BORDE_SUAVE);

        if (valores == null || valores.isEmpty()) {
            return;
        }
        double tope = Math.max(maximo, umbral * 1.2);
        if (tope <= 0) {
            return;
        }

        // Linea del umbral, por detras de las barras.
        int yUmbral = y + alto - 1 - (int) ((alto - 2) * (umbral / tope));
        if (yUmbral > y && yUmbral < y + alto) {
            g.fill(x + 1, yUmbral, x + ancho - 1, yUmbral + 1, Paleta.conAlfa(Paleta.ERROR, 0.35f));
        }

        int n = valores.size();
        int anchoBarra = Math.max(1, (ancho - 2) / n);
        int usadas = Math.min(n, (ancho - 2) / anchoBarra);
        int desde = n - usadas;

        for (int i = 0; i < usadas; i++) {
            double v = valores.get(desde + i);
            int h = (int) ((alto - 2) * Math.min(1.0, v / tope));
            if (h <= 0) {
                continue;
            }
            int color = v >= umbral ? Paleta.ERROR
                    : (v >= umbral * 0.6 ? Paleta.ADVERTENCIA : Paleta.OK);
            int bx = x + 1 + i * anchoBarra;
            g.fill(bx, y + alto - 1 - h, bx + anchoBarra, y + alto - 1, color);
        }
    }

    // ------------------------------------------------------------------ texto

    /** Escribe texto partido en varias lineas y devuelve la Y siguiente. */
    public static int parrafo(GuiGraphics g, Font font, String texto, int x, int y,
                              int ancho, int color, int maxLineas) {
        List<FormattedCharSequence> lineas = font.split(Component.literal(texto), ancho);
        int n = Math.min(maxLineas, lineas.size());
        for (int i = 0; i < n; i++) {
            g.drawString(font, lineas.get(i), x, y, color, false);
            y += 10;
        }
        return y;
    }

    /** Texto recortado a un ancho maximo, sin desbordar nunca. */
    public static void lineaRecortada(GuiGraphics g, Font font, String texto, int x, int y,
                                      int anchoMax, int color) {
        g.drawString(font, font.plainSubstrByWidth(texto, anchoMax), x, y, color, false);
    }

    /** Separador horizontal tenue. */
    public static void separador(GuiGraphics g, int x, int y, int ancho) {
        g.fill(x, y, x + ancho, y + 1, Paleta.BORDE_SUAVE);
    }

    // ------------------------------------------------------------------ varios

    /**
     * Pulso suave de 0 a 1 basado en el reloj, para resaltar alertas sin animar
     * nada costoso. periodoMs define lo lento que late.
     */
    public static float pulso(long periodoMs) {
        double fase = (System.currentTimeMillis() % periodoMs) / (double) periodoMs;
        return (float) ((Math.sin(fase * Math.PI * 2) + 1.0) / 2.0);
    }

    public static boolean dentro(int mouseX, int mouseY, int x, int y, int ancho, int alto) {
        return mouseX >= x && mouseX < x + ancho && mouseY >= y && mouseY < y + alto;
    }
}
