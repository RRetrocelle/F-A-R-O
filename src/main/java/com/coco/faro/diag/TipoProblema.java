package com.coco.faro.diag;

/**
 * Familia del problema detectado. Se usa para explicarle al usuario en castellano
 * simple que clase de falla es, y para decidir si tiene sentido ofrecer reparacion.
 */
public enum TipoProblema {
    DEPENDENCIA_FALTANTE(
            "Falta una dependencia",
            "Un mod necesita otro mod (o una version distinta) que no esta instalado.",
            false),

    CONFLICTO_ENTRE_MODS(
            "Dos mods chocan",
            "Dos o mas mods intentan modificar lo mismo y se pisan entre si.",
            true),

    MIXIN_FALLIDO(
            "Un parche (mixin) fallo",
            "Un mod intento parchear codigo del juego y no pudo. Suele pasar cuando el mod "
                    + "es para otra version de Minecraft o de otro mod.",
            true),

    VERSION_INCORRECTA(
            "Version incorrecta",
            "Un mod es para otra version de Minecraft, de Forge o de un mod del que depende.",
            true),

    FALTA_MEMORIA(
            "Falta memoria",
            "El juego se quedo sin RAM asignada. Esto se arregla subiendo la memoria en el "
                    + "launcher, no sacando mods.",
            false),

    ERROR_DE_MUNDO(
            "Problema al cargar el mundo",
            "Faltan bloques o entidades que un mod ya quitado habia generado en el mundo.",
            false),

    EXCEPCION_GENERICA(
            "Error sin contexto claro",
            "El juego fallo pero el error no indica con claridad quien lo causo.",
            false),

    DESCONOCIDO(
            "Sin determinar",
            "No hay informacion suficiente para clasificar el problema.",
            false);

    private final String titulo;
    private final String explicacion;
    private final boolean reparablePorDesactivacion;

    TipoProblema(String titulo, String explicacion, boolean reparablePorDesactivacion) {
        this.titulo = titulo;
        this.explicacion = explicacion;
        this.reparablePorDesactivacion = reparablePorDesactivacion;
    }

    public String titulo() {
        return titulo;
    }

    public String explicacion() {
        return explicacion;
    }

    /**
     * Si desactivar el mod culpable puede razonablemente resolverlo.
     * Para FALTA_MEMORIA o DEPENDENCIA_FALTANTE sacar un jar no arregla nada
     * (en el segundo caso hay que AGREGAR algo, no quitar), asi que devolvemos false
     * y la UI explica que hacer en su lugar.
     */
    public boolean reparablePorDesactivacion() {
        return reparablePorDesactivacion;
    }
}
