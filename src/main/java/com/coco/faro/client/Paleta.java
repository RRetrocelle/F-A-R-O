package com.coco.faro.client;

import com.coco.faro.diag.Confianza;
import com.coco.faro.diag.Severidad;
import com.coco.faro.diag.TipoProblema;

/** Colores ARGB de la interfaz, en un solo lugar. */
public final class Paleta {

    // Superficies
    public static final int FONDO_PANTALLA   = 0xF00B0E12;
    public static final int FONDO_PANEL      = 0xE0101418;
    public static final int FONDO_TARJETA    = 0xC0161C24;
    public static final int FONDO_TARJETA_ALT= 0xC01B222C;
    public static final int FONDO_HOVER      = 0xC0222B36;

    // Bordes
    public static final int BORDE            = 0xFF2E3A46;
    public static final int BORDE_SUAVE      = 0x402E3A46;
    public static final int BORDE_ACENTO     = 0xFFF0B429;

    // Texto
    public static final int TEXTO            = 0xFFE6EDF3;
    public static final int TEXTO_TENUE      = 0xFF8B98A5;
    public static final int TEXTO_APAGADO    = 0xFF5A6673;
    public static final int TEXTO_TITULO     = 0xFFF0B429;

    // Estados
    public static final int OK               = 0xFF3FB950;
    public static final int ADVERTENCIA      = 0xFFD29922;
    public static final int ERROR            = 0xFFF85149;
    public static final int NEUTRO           = 0xFF58A6FF;
    public static final int VIOLETA          = 0xFFA371F7;

    private Paleta() {
    }

    public static int porConfianza(Confianza c) {
        return switch (c) {
            case ALTA -> ERROR;
            case MEDIA -> ADVERTENCIA;
            case BAJA -> NEUTRO;
            case NINGUNA -> TEXTO_TENUE;
        };
    }

    public static int porSeveridad(Severidad s) {
        return switch (s) {
            case CRITICA -> ERROR;
            case ALTA -> ADVERTENCIA;
            case MEDIA -> NEUTRO;
            case INFO -> TEXTO_TENUE;
        };
    }

    public static int porTipo(TipoProblema t) {
        return switch (t) {
            case FALTA_MEMORIA, DEPENDENCIA_FALTANTE -> NEUTRO;
            case DESCONOCIDO -> TEXTO_TENUE;
            default -> ADVERTENCIA;
        };
    }

    /** Mezcla dos colores ARGB. t=0 devuelve a, t=1 devuelve b. */
    public static int mezclar(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24) & 0xFF, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24) & 0xFF, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int ra = (int) (aa + (ba - aa) * t);
        int rr = (int) (ar + (br - ar) * t);
        int rg = (int) (ag + (bg - ag) * t);
        int rb = (int) (ab + (bb - ab) * t);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }

    /** Aplica una transparencia (0..1) a un color conservando el tono. */
    public static int conAlfa(int color, float alfa) {
        int a = (int) (((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alfa)));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
