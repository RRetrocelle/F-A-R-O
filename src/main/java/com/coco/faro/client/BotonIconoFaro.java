package com.coco.faro.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Boton cuadrado de 20x20 con el faro dibujado adentro, del mismo tamano y
 * formato que el selector de idioma del menu principal.
 *
 * El icono se dibuja con rectangulos a partir de un mapa de caracteres, igual
 * que {@link AnimacionFaro}, para que sea el mismo personaje y no un segundo
 * grafico distinto. No usa textura: no hay que registrar ni cargar nada.
 *
 * Cuando hay algo que mirar, la punta del faro late. Es la unica animacion, y
 * solo aparece si de verdad hay un problema.
 */
public class BotonIconoFaro extends Button {

    /** 12x14, version reducida del faro del logo. */
    private static final String[] ICONO = {
            "....kkkk....",
            "...kwwwwk...",
            "..kkkkkkkk..",
            "..kyyyyyyk..",
            "..kyyyyyyk..",
            "..kkkkkkkk..",
            "..kwwwwwwk..",
            "..krrrrrrk..",
            ".kwwwwwwwwk.",
            ".krrrrrrrrk.",
            "kwwwwwwwwwwk",
            "krrrrrrrrrrk",
            "kkkkkkkkkkkk",
            "gggggggggggg",
    };

    private static final int C_OSCURO = 0xFF12161C;
    private static final int C_BLANCO = 0xFFE6EDF3;
    private static final int C_ROJO = 0xFFF85149;
    private static final int C_LUZ = 0xFFF0B429;
    private static final int C_ROCA = 0xFF46505C;

    /** 0 = todo bien, 1 = hay algo importante, 2 = hay algo critico. */
    private final int nivelAlerta;

    public BotonIconoFaro(int x, int y, int nivelAlerta, OnPress alPresionar, String tooltip) {
        super(x, y, 20, 20, Component.literal(""), alPresionar, DEFAULT_NARRATION);
        this.nivelAlerta = nivelAlerta;
        this.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal(tooltip)));
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Fondo y borde propios: el boton de vanilla dibujaria una textura de
        // 20x20 estirada que no combina con un icono dibujado a mano.
        boolean hover = this.isHovered();
        g.fill(getX(), getY(), getX() + width, getY() + height,
                hover ? 0xFF3A3A3A : 0xFF2B2B2B);
        Widgets.borde(g, getX(), getY(), width, height,
                hover ? 0xFFFFFFFF : 0xFF000000);

        int px = getX() + 4;
        int py = getY() + 3;

        for (int fy = 0; fy < ICONO.length; fy++) {
            String fila = ICONO[fy];
            for (int fx = 0; fx < fila.length(); fx++) {
                int color = switch (fila.charAt(fx)) {
                    case 'k' -> C_OSCURO;
                    case 'w' -> C_BLANCO;
                    case 'r' -> C_ROJO;
                    case 'y' -> colorLuz();
                    case 'g' -> C_ROCA;
                    default -> 0;
                };
                if (color == 0) {
                    continue;
                }
                g.fill(px + fx, py + fy, px + fx + 1, py + fy + 1, color);
            }
        }

        // Punto de alerta en la esquina, para que se vea sin abrir nada.
        if (nivelAlerta > 0) {
            int c = nivelAlerta >= 2 ? C_ROJO : C_LUZ;
            float pulso = Widgets.pulso(1400);
            c = Paleta.mezclar(c, 0xFFFFFFFF, pulso * 0.5f);
            g.fill(getX() + width - 6, getY() + 2, getX() + width - 2, getY() + 6, c);
        }
    }

    /** La linterna late suave cuando hay algo que revisar. */
    private int colorLuz() {
        if (nivelAlerta == 0) {
            return C_LUZ;
        }
        return Paleta.mezclar(C_LUZ, C_BLANCO, Widgets.pulso(1400) * 0.7f);
    }
}
