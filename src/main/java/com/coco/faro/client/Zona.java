package com.coco.faro.client;

/**
 * Region interactiva dibujada a mano dentro de un area con scroll.
 *
 * No se usan Button de Minecraft para esto porque las filas se desplazan y se
 * recortan: recalcular rectangulos por frame es mas simple y mas seguro que
 * mantener sincronizadas las posiciones de widgets reales.
 *
 * Vive fuera de las pantallas porque la comparten {@link PantallaFaro} y
 * {@link PantallaRescate} a traves de {@link PanelProblemas}.
 */
public record Zona(int x, int y, int ancho, int alto, Runnable accion,
                   String claveGlosario, String etiqueta) {

    public static Zona boton(int x, int y, int ancho, int alto, String etiqueta, Runnable accion) {
        return new Zona(x, y, ancho, alto, accion, null, etiqueta);
    }

    public static Zona ayuda(int x, int y, int ancho, int alto, String claveGlosario) {
        return new Zona(x, y, ancho, alto, null, claveGlosario, null);
    }

    public boolean contiene(int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + ancho && mouseY >= y && mouseY < y + alto;
    }

    /** true si la zona esta enteramente dentro de la banda visible. */
    public boolean visibleEntre(int yMin, int yMax) {
        return y >= yMin && y + alto <= yMax;
    }
}
