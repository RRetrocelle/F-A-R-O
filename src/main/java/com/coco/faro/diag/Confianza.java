package com.coco.faro.diag;

/**
 * Que tan seguro esta Faro de haber identificado al mod causante.
 *
 * Esto NO es decorativo: el boton de reparar solo se habilita en ALTA y MEDIA,
 * y el texto que ve el usuario cambia segun el nivel. Nunca inventamos un
 * culpable para llenar el hueco; si no sabemos, decimos NINGUNA.
 */
public enum Confianza {
    /** Forge nombro el mod explicitamente (bloque "-- MOD x --" o "Failure message"). */
    ALTA("Alta", "Forge nombro este mod directamente como origen del fallo."),

    /** El stacktrace apunta a paquetes que pertenecen a un mod instalado. */
    MEDIA("Media", "El stacktrace apunta a este mod, pero Forge no lo confirmo."),

    /** Hay pistas debiles (menciones sueltas en el log) pero nada concluyente. */
    BAJA("Baja", "Hay menciones a este mod, pero no alcanzan para culparlo."),

    /** No se pudo determinar nada. */
    NINGUNA("Ninguna", "No se pudo determinar la causa a partir de los datos disponibles.");

    private final String etiqueta;
    private final String explicacion;

    Confianza(String etiqueta, String explicacion) {
        this.etiqueta = etiqueta;
        this.explicacion = explicacion;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public String explicacion() {
        return explicacion;
    }

    /** Solo ALTA y MEDIA habilitan la reparacion automatica. */
    public boolean permiteReparar() {
        return this == ALTA || this == MEDIA;
    }
}
