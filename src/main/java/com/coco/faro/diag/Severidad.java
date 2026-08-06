package com.coco.faro.diag;

/** Que tan grave es un problema detectado. Ordena la lista y elige el color. */
public enum Severidad {
    /** Rompe el arranque o el juego. Hay que resolverlo si o si. */
    CRITICA("Critico", 3),
    /** Muy probablemente cause fallos o perdida de contenido. */
    ALTA("Importante", 2),
    /** Conviene mirarlo, pero el juego anda. */
    MEDIA("Aviso", 1),
    /** Solo informativo. */
    INFO("Info", 0);

    private final String etiqueta;
    private final int peso;

    Severidad(String etiqueta, int peso) {
        this.etiqueta = etiqueta;
        this.peso = peso;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public int peso() {
        return peso;
    }
}
