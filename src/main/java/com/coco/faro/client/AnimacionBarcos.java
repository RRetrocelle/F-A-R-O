package com.coco.faro.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Dos animaciones de barcos en pixel art, hermanas del faro.
 *
 *   navegando()  — un barco avanzando tranquilo, para procesos en curso.
 *   chocando()   — dos barcos que se acercan, chocan y se rompen, para cuando
 *                  hay un conflicto real entre mods.
 *
 * Se dibujan con rectangulos y el estado sale del reloj: no hay texturas, no hay
 * objetos por frame y no hay hilos. Cuesta lo mismo que dibujar unos cuadrados.
 */
public final class AnimacionBarcos {

    private static final int C_CASCO = 0xFF6B4423;
    private static final int C_CASCO_OSCURO = 0xFF4A2F18;
    private static final int C_VELA = 0xFFE6EDF3;
    private static final int C_MASTIL = 0xFF3A2612;
    private static final int C_AGUA = 0xFF1C3048;
    private static final int C_ESPUMA = 0xFF3A5A7A;
    private static final int C_FUEGO = 0xFFF0B429;
    private static final int C_HUMO = 0xFF4A4A4A;

    private AnimacionBarcos() {
    }

    /** Barco de 16x12 mirando a la derecha (dir=1) o a la izquierda (dir=-1). */
    private static void barco(GuiGraphics g, int x, int y, int escala, int dir, boolean roto) {
        String[] mapa = roto ? new String[]{
                "......m.........",
                ".....mvm........",
                "....mv..........",
                "....m...........",
                "................",
                "..cc....cc......",
                ".cccc..cccc.....",
                "..cc....cc......",
        } : new String[]{
                "......m.........",
                ".....mvvm.......",
                ".....mvvvm......",
                ".....mvvvvm.....",
                "......m.........",
                "..cccccccccc....",
                ".dddddddddddd...",
                "..dddddddddd....",
        };

        for (int fy = 0; fy < mapa.length; fy++) {
            String fila = mapa[fy];
            for (int fx = 0; fx < fila.length(); fx++) {
                int c = switch (fila.charAt(fx)) {
                    case 'm' -> C_MASTIL;
                    case 'v' -> C_VELA;
                    case 'c' -> C_CASCO;
                    case 'd' -> C_CASCO_OSCURO;
                    default -> 0;
                };
                if (c == 0) {
                    continue;
                }
                // Espejado para el barco que viene de la derecha.
                int px = dir > 0 ? fx : (fila.length() - 1 - fx);
                g.fill(x + px * escala, y + fy * escala,
                        x + (px + 1) * escala, y + (fy + 1) * escala, c);
            }
        }
    }

    private static void mar(GuiGraphics g, int cx, int y, int ancho, int escala, long fase) {
        g.fill(cx - ancho / 2, y, cx + ancho / 2, y + escala * 3, C_AGUA);
        // Espuma que se desplaza, para que el mar no parezca una barra quieta.
        for (int i = 0; i < ancho / (escala * 4); i++) {
            int ox = (int) ((i * escala * 4 + fase / 40) % ancho);
            int px = cx - ancho / 2 + ox;
            g.fill(px, y, px + escala * 2, y + escala, C_ESPUMA);
        }
    }

    /**
     * Barco navegando de izquierda a derecha, en bucle. Para "configurando...".
     */
    public static void navegando(GuiGraphics g, int cx, int y, int escala) {
        long ms = System.currentTimeMillis();
        int ancho = 90 * escala;
        int recorrido = ancho - 16 * escala;
        int avance = (int) ((ms / 24) % recorrido);

        mar(g, cx, y + 8 * escala, ancho, escala, ms);
        // Cabeceo suave sobre las olas.
        int cabeceo = (int) (Math.sin(ms / 260.0) * escala);
        barco(g, cx - ancho / 2 + avance, y + cabeceo, escala, 1, false);
    }

    /**
     * Dos barcos que se acercan, chocan y quedan rotos con fuego y humo.
     *
     * El ciclo dura 4 segundos y se repite: acercamiento, impacto, restos.
     */
    public static void chocando(GuiGraphics g, int cx, int y, int escala) {
        long ms = System.currentTimeMillis();
        long ciclo = ms % 4000L;
        int ancho = 90 * escala;

        mar(g, cx, y + 8 * escala, ancho, escala, ms);

        int separacionInicial = 32 * escala;
        boolean impacto = ciclo > 1600L;

        int desplazamiento = impacto
                ? separacionInicial / 2
                : (int) (separacionInicial / 2 * (ciclo / 1600.0));

        int xIzq = cx - separacionInicial + desplazamiento - 8 * escala;
        int xDer = cx + separacionInicial - desplazamiento - 8 * escala;

        int cabeceo = (int) (Math.sin(ms / 200.0) * escala);
        barco(g, xIzq, y + cabeceo, escala, 1, impacto);
        barco(g, xDer, y - cabeceo, escala, -1, impacto);

        if (impacto) {
            // Chispas y humo saliendo del punto de choque.
            long desdeImpacto = ciclo - 1600L;
            int cantidad = (int) Math.min(8, desdeImpacto / 60);
            for (int i = 0; i < cantidad; i++) {
                double ang = i * 2.4 + desdeImpacto / 400.0;
                int r = (int) (escala * (2 + i * 1.2));
                int px = cx + (int) (Math.cos(ang) * r);
                int py = y + 4 * escala + (int) (Math.sin(ang) * r * 0.6) - (int) (desdeImpacto / 120);
                int c = (i % 2 == 0) ? C_FUEGO : C_HUMO;
                g.fill(px, py, px + escala, py + escala, c);
            }
        }
    }
}
