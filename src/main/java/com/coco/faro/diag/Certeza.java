package com.coco.faro.diag;

/**
 * Nivel de certeza de CUALQUIER cosa que Faro afirme, en toda la interfaz.
 *
 * Antes la confianza solo existia para los crashes. Ahora es transversal: un
 * problema de dependencias, una sugerencia de alternativa y una hipotesis de la
 * capa de IA no valen lo mismo, y el usuario tiene que poder distinguirlos de un
 * vistazo antes de tocar sus archivos.
 *
 * La regla de oro: nada se muestra sin su etiqueta al lado.
 */
public enum Certeza {

    /**
     * Sale de un dato estructural verificable: lo que dice el mods.toml, lo que
     * Forge escribio en el crash, dos jars con el mismo modId. No es opinion.
     */
    ALTA("Certeza alta", "Sale de un dato verificable, no de una estimacion."),

    /**
     * Sale de una lista curada a mano o de una deduccion razonable. Suele
     * acertar, pero puede fallar y conviene revisarlo.
     */
    MEDIA("Certeza media", "Es una deduccion o sale de una lista armada a mano. Revisalo."),

    /**
     * No se pudo determinar. Se muestra igual, porque decir "no se" es
     * informacion util, pero nunca habilita una accion automatica.
     */
    NINGUNA("Sin certeza", "No hay datos suficientes para afirmar nada.");

    private final String etiqueta;
    private final String explicacion;

    Certeza(String etiqueta, String explicacion) {
        this.etiqueta = etiqueta;
        this.explicacion = explicacion;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public String explicacion() {
        return explicacion;
    }

    /** Solo ALTA y MEDIA pueden ofrecer una accion con confirmacion. */
    public boolean permiteAccion() {
        return this != NINGUNA;
    }

    /**
     * Certeza de un problema detectado, segun de donde salio.
     *
     * Las categorias que se leen directo de los metadatos son ALTA. Las que
     * salen de listas curadas o de heuristicas son MEDIA. Esta separacion es la
     * misma que ya se aplicaba a los conflictos, extendida a todo lo demas.
     */
    public static Certeza de(Problema p) {
        return switch (p.categoria()) {
            case DEPENDENCIA_AUSENTE, DEPENDENCIA_VERSION, MOD_DUPLICADO,
                 LOADER_INCORRECTO, CONFLICTO_DECLARADO, SIN_METADATOS -> ALTA;
            case RANGO_BLANDO, POSIBLE_SOLAPAMIENTO, ALTERNATIVA_SUGERIDA,
                 RENDIMIENTO, LOG -> MEDIA;
        };
    }

    /** Puente desde la confianza del analisis de crashes, que ya existia. */
    public static Certeza de(Confianza c) {
        return switch (c) {
            case ALTA -> ALTA;
            case MEDIA -> MEDIA;
            case BAJA, NINGUNA -> NINGUNA;
        };
    }
}
