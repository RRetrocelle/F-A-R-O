package com.coco.faro.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Faro en pixel art con el haz de luz girando. Se usa mientras corre un analisis
 * o una descarga.
 *
 * Es el mismo diseno que el icono del mod, a proposito: que sea un solo personaje
 * y no dos graficos distintos.
 *
 * Todo se dibuja con rectangulos a partir de un mapa de caracteres, sin texturas
 * ni objetos por frame. El haz se calcula con una rotacion simple sobre el reloj,
 * asi que la animacion no depende de los FPS ni consume nada apreciable.
 *
 * Importante: esta animacion NO agrega demora. Dura exactamente lo que tarda el
 * trabajo real; cuando el analisis termina, la pantalla cambia sola.
 */
public final class AnimacionFaro {

    /** 24x28. '.' = transparente. Mismo diseno que faro_logo.png. */
    private static final String[] MAPA = {
            "..........kkkk..........",
            ".........kwwwwk.........",
            ".........kwwwwk.........",
            "........kkkkkkkk........",
            ".......kyyyyyyyyk.......",
            ".......kyyyyyyyyk.......",
            ".......kyyyyyyyyk.......",
            "........kkkkkkkk........",
            ".......kkwwwwwwkk.......",
            ".......kwwwwwwwwk.......",
            ".......krrrrrrrrk.......",
            ".......krrrrrrrrk.......",
            "......kwwwwwwwwwwk......",
            "......kwwwwwwwwwwk......",
            "......krrrrrrrrrrk......",
            "......krrrrrrrrrrk......",
            ".....kwwwwwwwwwwwwk.....",
            ".....kwwwwwwwwwwwwk.....",
            ".....krrrrrrrrrrrrk.....",
            ".....krrrrrrrrrrrrk.....",
            "....kwwwwwwwwwwwwwwk....",
            "....kwwwwwwwwwwwwwwk....",
            "...kkkkkkkkkkkkkkkkkk...",
            "..gggggggggggggggggggg..",
            ".gggggggggggggggggggggg.",
            "gggggggggggggggggggggggg",
            "ssssssssssssssssssssssss",
            "ssssssssssssssssssssssss",
    };

    private static final int ANCHO = 24;
    private static final int ALTO = 28;

    /** Alto de la linterna dentro del mapa: de ahi sale el haz. */
    private static final int FILA_LINTERNA = 5;

    private static final int C_OSCURO = 0xFF12161C;
    private static final int C_BLANCO = 0xFFE6EDF3;
    private static final int C_ROJO   = 0xFFF85149;
    private static final int C_LUZ    = 0xFFF0B429;
    private static final int C_ROCA   = 0xFF46505C;
    private static final int C_MAR    = 0xFF1C3048;

    /** Cuanto tarda una vuelta completa del haz. */
    private static final long PERIODO_MS = 2600L;

    private static int color(char c) {
        return switch (c) {
            case 'k' -> C_OSCURO;
            case 'w' -> C_BLANCO;
            case 'r' -> C_ROJO;
            case 'y' -> C_LUZ;
            case 'g' -> C_ROCA;
            case 's' -> C_MAR;
            default -> 0;
        };
    }

    /**
     * Dibuja el faro centrado horizontalmente en cx, con la parte de arriba en y.
     *
     * @param escala pixeles de pantalla por pixel del dibujo
     */
    public void dibujar(GuiGraphics g, int cx, int y, int escala) {
        int x0 = cx - (ANCHO * escala) / 2;

        double fase = (System.currentTimeMillis() % PERIODO_MS) / (double) PERIODO_MS;
        double angulo = fase * Math.PI * 2;

        // El haz va primero para que la torre quede por encima.
        dibujarHaz(g, cx, y + FILA_LINTERNA * escala + escala, escala, angulo);

        for (int fy = 0; fy < ALTO; fy++) {
            String fila = MAPA[fy];
            for (int fx = 0; fx < ANCHO; fx++) {
                int c = color(fila.charAt(fx));
                if (c == 0) {
                    continue;
                }
                // La linterna late al ritmo del giro: mas brillante de frente.
                if (fila.charAt(fx) == 'y') {
                    float brillo = (float) ((Math.cos(angulo) + 1.0) / 2.0);
                    c = Paleta.mezclar(C_LUZ, C_BLANCO, brillo * 0.6f);
                }
                int px = x0 + fx * escala;
                int py = y + fy * escala;
                g.fill(px, py, px + escala, py + escala, c);
            }
        }
    }

    /**
     * Dos conos de luz opuestos que barren el ancho de la pantalla.
     *
     * Se dibujan como columnas verticales cuya altura crece con la distancia:
     * es una aproximacion barata de un cono en perspectiva, y en pixel art se
     * lee perfecto sin necesidad de rotar texturas.
     */
    private void dibujarHaz(GuiGraphics g, int cx, int cy, int escala, double angulo) {
        double dirX = Math.cos(angulo);
        // Cuando el haz apunta al frente o atras (dirX cerca de 0) casi no se ve.
        double apertura = Math.abs(dirX);
        if (apertura < 0.05) {
            return;
        }

        int largo = 60 * escala;
        int paso = Math.max(1, escala);

        for (int lado = 0; lado < 2; lado++) {
            int signo = (lado == 0) ? 1 : -1;
            double d = dirX * signo;
            if (d <= 0) {
                continue;
            }
            int alcance = (int) (largo * d);

            for (int i = 0; i < alcance; i += paso) {
                float t = i / (float) alcance;
                int mitad = (int) (escala * 1.5 + t * escala * 5);
                // Se desvanece con la distancia.
                float alfa = (float) (0.30 * (1 - t) * d);
                int c = Paleta.conAlfa(C_LUZ, alfa);

                int px = cx + signo * (ANCHO * escala / 2 + i);
                g.fill(px, cy - mitad, px + paso, cy + mitad, c);
            }
        }
    }

    /** Texto que acompana la animacion, rotando entre variantes. */
    public String texto() {
        String[] frases = {"Observando...", "Calculando...", "Revisando la carpeta..."};
        int i = (int) ((System.currentTimeMillis() / 1400L) % frases.length);
        return frases[i];
    }

    public int anchoEn(int escala) {
        return ANCHO * escala;
    }

    public int altoEn(int escala) {
        return ALTO * escala;
    }
}
