package com.coco.faro.diag;

import java.util.ArrayList;
import java.util.List;

/**
 * Catalogo de fallos conocidos de Minecraft/Forge 1.20.1.
 *
 * Cada entrada nacio de un mensaje de error que Forge realmente escribe. Estan
 * ordenadas de mas especifica a mas generica: la primera que coincide manda,
 * porque una firma precisa siempre explica mejor que "hubo una excepcion".
 *
 * Si ninguna coincide, el motor lo dice y no inventa. Esa es la diferencia entre
 * un diagnostico util y uno decorativo.
 */
public final class BaseConocimiento {

    private static final List<Firma> FIRMAS = new ArrayList<>();

    static {
        // ---------------------------------------------------------- memoria
        FIRMAS.add(Firma.de("oom",
                "java\\.lang\\.OutOfMemoryError(?::\\s*(?:Java heap space|GC overhead limit exceeded))?",
                TipoProblema.FALTA_MEMORIA, Severidad.CRITICA,
                "El juego se quedo sin RAM asignada. No es culpa de un mod puntual: "
                        + "el modpack entero necesita mas memoria de la que tiene.",
                "Subi la RAM en SKLauncher (Ajustes de la instancia > Memoria). "
                        + "Con ~160 mods, 6 GB es un piso razonable. Ojo: NO le des mas de la mitad "
                        + "de tu RAM fisica, y nunca mas de 8-10 GB, que empeora las pausas del recolector.",
                100));

        FIRMAS.add(Firma.de("metaspace",
                "OutOfMemoryError:\\s*Metaspace",
                TipoProblema.FALTA_MEMORIA, Severidad.CRITICA,
                "Se lleno el Metaspace: la zona donde Java guarda las clases cargadas. "
                        + "Pasa con muchisimos mods.",
                "Agregá el argumento -XX:MaxMetaspaceSize=512m en las opciones de Java del launcher.",
                100));

        // ---------------------------------------------------- dependencias
        FIRMAS.add(Firma.de("deps_faltantes",
                "Missing or unsupported mandatory dependencies",
                TipoProblema.DEPENDENCIA_FALTANTE, Severidad.CRITICA,
                "Uno o mas mods piden otro mod que no esta instalado, o piden una version distinta.",
                "Mirá la pestana Mods: ahi esta la lista exacta de que falta. "
                        + "Hay que INSTALAR lo que falta, no sacar mods.",
                95));

        FIRMAS.add(Firma.conMod("noclassdef",
                "java\\.lang\\.NoClassDefFoundError:\\s*([a-z0-9_]+)/",
                TipoProblema.DEPENDENCIA_FALTANTE, Severidad.CRITICA,
                "Un mod busco una clase que no existe. Casi siempre significa que falta "
                        + "una libreria, o que esta en una version que ya no tiene esa clase.",
                "Revisá que la libreria correspondiente este instalada y en la version que el mod pide.",
                90));

        FIRMAS.add(Firma.de("nosuchmethod",
                "java\\.lang\\.NoSuchMethodError",
                TipoProblema.VERSION_INCORRECTA, Severidad.CRITICA,
                "Un mod llamo a un metodo que en la version instalada de la libreria ya no existe. "
                        + "Es el sintoma tipico de un addon viejo con una dependencia nueva.",
                "Es un choque de versiones. Actualizá el addon, o bajá la libreria a la version "
                        + "que el addon espera. Mirá los avisos de 'compatibilidad no verificable'.",
                90));

        FIRMAS.add(Firma.de("nosuchfield",
                "java\\.lang\\.NoSuchFieldError",
                TipoProblema.VERSION_INCORRECTA, Severidad.CRITICA,
                "Un mod busco un campo que ya no existe en la version instalada de otro mod.",
                "Mismo caso que arriba: hay un desajuste de versiones entre un mod y su dependencia.",
                88));

        // ----------------------------------------------------------- mixins
        FIRMAS.add(Firma.conMod("mixin_apply",
                "Mixin apply(?:ing)? failed:?\\s*([a-z0-9_\\-]+)",
                TipoProblema.MIXIN_FALLIDO, Severidad.CRITICA,
                "Un mod intento parchear codigo del juego y no encontro lo que esperaba. "
                        + "Suele pasar cuando dos mods parchean lo mismo, o cuando el mod es para "
                        + "otra version.",
                "El mod nombrado es el que no pudo aplicar su parche. Probá desactivandolo, "
                        + "o buscá si tiene una version mas nueva.",
                92));

        FIRMAS.add(Firma.de("mixin_injection",
                "org\\.spongepowered\\.asm\\.mixin\\.injection\\.throwables\\.(?:Invalid)?InjectionException",
                TipoProblema.MIXIN_FALLIDO, Severidad.CRITICA,
                "Un parche de mod no encontro el punto exacto del codigo donde queria engancharse.",
                "Casi siempre es un mod desactualizado, o dos mods de optimizacion peleando por "
                        + "el mismo metodo.",
                90));

        FIRMAS.add(Firma.de("mixin_transformer",
                "MixinTransformerError|mixin\\.transformer\\.throwables",
                TipoProblema.MIXIN_FALLIDO, Severidad.CRITICA,
                "Fallo el sistema de parcheo en tiempo de carga.",
                "Revisá si agregaste hace poco algun mod que reemplace librerias del nucleo "
                        + "(por ejemplo, mods que cambian Mixin). Suelen ser la causa.",
                88));

        // -------------------------------------------------------- conflictos
        FIRMAS.add(Firma.de("duplicados",
                "DuplicateModsFoundException|Duplicate mod ids",
                TipoProblema.CONFLICTO_ENTRE_MODS, Severidad.CRITICA,
                "Hay dos copias del mismo mod en la carpeta.",
                "Dejá una sola. La pestana Mods te muestra cuales estan duplicados.",
                95));

        FIRMAS.add(Firma.de("registro_dup",
                "Duplicate registration|already contains|Registry Object not present",
                TipoProblema.CONFLICTO_ENTRE_MODS, Severidad.ALTA,
                "Dos mods intentaron registrar lo mismo, o uno pidio algo que nunca se registro.",
                "Suele resolverse sacando el mod mas nuevo del par en conflicto.",
                80));

        // ------------------------------------------------------------ mundo
        FIRMAS.add(Firma.de("registro_faltante",
                "Missing registry entries|does not exist in registry|Unknown (?:block|item|entity)",
                TipoProblema.ERROR_DE_MUNDO, Severidad.ALTA,
                "El mundo guardado tiene bloques o entidades de un mod que ya no esta instalado.",
                "Es lo que pasa al sacar mods de contenido de un mundo ya generado. "
                        + "Volvé a poner el mod que sacaste, o empezá un mundo nuevo.",
                85));

        FIRMAS.add(Firma.de("chunk_roto",
                "Failed to load chunk|ChunkLoadingException|Chunk file at .* is missing",
                TipoProblema.ERROR_DE_MUNDO, Severidad.ALTA,
                "No se pudo leer un trozo del mundo.",
                "Puede venir de sacar un mod de generacion de mundo. "
                        + "Probá el mundo con los mods que tenia cuando se creo.",
                80));

        // ------------------------------------------------------------- java
        FIRMAS.add(Firma.de("java_version",
                "UnsupportedClassVersionError",
                TipoProblema.VERSION_INCORRECTA, Severidad.CRITICA,
                "Un mod fue compilado para una version de Java mas nueva que la que usa el juego.",
                "Minecraft 1.20.1 corre con Java 17. Si un mod pide Java 21, no sirve para este pack.",
                95));

        // --------------------------------------------------------- runtime
        FIRMAS.add(Firma.de("stackoverflow",
                "java\\.lang\\.StackOverflowError",
                TipoProblema.CONFLICTO_ENTRE_MODS, Severidad.CRITICA,
                "Una llamada se repitio infinitamente. Casi siempre son dos mods parchandose "
                        + "mutuamente en circulo.",
                "Mirá que dos mods aparecen alternados en el stacktrace: ese par es el conflicto.",
                85));

        FIRMAS.add(Firma.de("concurrent_mod",
                "java\\.util\\.ConcurrentModificationException",
                TipoProblema.CONFLICTO_ENTRE_MODS, Severidad.ALTA,
                "Un mod modifico una lista mientras otro la estaba recorriendo. "
                        + "Tipico de mods que mueven trabajo a otros hilos.",
                "Sospechá de los mods de optimizacion que hacen cosas en paralelo "
                        + "(generacion asincrona, entidades multihilo).",
                75));

        FIRMAS.add(Firma.de("watchdog",
                "Watchdog|Server thread.*took.*ms|Ticking entity|Ticking block entity",
                TipoProblema.EXCEPCION_GENERICA, Severidad.ALTA,
                "Un tick tardo tanto que el juego se dio por colgado, o una entidad fallo al tickear.",
                "Mirá la pestana Rendimiento. En una CPU modesta esto suele venir de mobs "
                        + "o de maquinaria muy grande.",
                70));

        FIRMAS.add(Firma.de("npe",
                "java\\.lang\\.NullPointerException",
                TipoProblema.EXCEPCION_GENERICA, Severidad.ALTA,
                "Algo esperaba un valor y encontro vacio. Por si solo no dice quien tuvo la culpa.",
                "Hay que mirar el stacktrace para ver por que mod pasa. "
                        + "Fijate en el detalle tecnico.",
                40));
    }

    private BaseConocimiento() {
    }

    public static List<Firma> firmas() {
        return List.copyOf(FIRMAS);
    }

    public static int cantidad() {
        return FIRMAS.size();
    }

    /**
     * Aplica todas las firmas y devuelve las coincidencias, de mayor a menor peso.
     * Devuelve lista vacia si no reconocio nada — que es un resultado legitimo.
     */
    public static List<Firma.Coincidencia> reconocer(String texto) {
        List<Firma.Coincidencia> encontradas = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            return encontradas;
        }
        for (Firma f : FIRMAS) {
            Firma.Coincidencia c = f.aplicar(texto);
            if (c != null) {
                encontradas.add(c);
            }
        }
        encontradas.sort((a, b) -> Integer.compare(b.firma().peso(), a.firma().peso()));
        return encontradas;
    }
}
