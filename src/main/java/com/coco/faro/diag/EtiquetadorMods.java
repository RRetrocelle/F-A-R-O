package com.coco.faro.diag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Clasifica mods por tipo y detecta librerias que ya no usa nadie.
 *
 * La clasificacion se basa en palabras del nombre y del modId. Es una
 * aproximacion, no un dato duro: por eso las etiquetas son informativas y no
 * disparan ninguna accion. Sirven para navegar una lista de 160+ mods, nada mas.
 *
 * La deteccion de librerias huerfanas SI es un dato verificable: se construye el
 * grafo de dependencias y se ve quien no tiene a nadie apuntandolo.
 */
public final class EtiquetadorMods {

    public enum Etiqueta {
        LIBRERIA("Libreria", 0xFF8B98A5),
        OPTIMIZACION("Optimizacion", 0xFF3FB950),
        MAGIA("Magia", 0xFFA371F7),
        TECNOLOGIA("Tecnologia", 0xFF58A6FF),
        AVENTURA("Aventura", 0xFFF0B429),
        CRIATURAS("Criaturas", 0xFFD4537E),
        DECORACION("Decoracion", 0xFF5DCAA5),
        MUNDO("Mundo", 0xFF97C459),
        UTILIDAD("Utilidad", 0xFFB4B2A9),
        OTRO("Otro", 0xFF5A6673);

        public final String texto;
        public final int color;

        Etiqueta(String texto, int color) {
            this.texto = texto;
            this.color = color;
        }
    }

    private EtiquetadorMods() {
    }

    /** Clasifica por palabras del modId y del nombre visible. */
    public static Etiqueta clasificar(MetadatosJar jar) {
        if (jar.esLibreria() || jar.sinMetadatosDeMod()) {
            return Etiqueta.LIBRERIA;
        }
        String t = (jar.modIdPrincipal() + " " + jar.nombreVisible()).toLowerCase(Locale.ROOT);

        if (contiene(t, "lib", "api", "core", "base", "framework")) return Etiqueta.LIBRERIA;
        if (contiene(t, "optim", "performance", "fps", "fast", "lag", "culling",
                "embeddium", "ferrite", "starlight", "canary", "modernfix")) return Etiqueta.OPTIMIZACION;
        if (contiene(t, "magic", "magia", "spell", "wizard", "arcane", "hex", "witch",
                "occult", "azazel", "ritual")) return Etiqueta.MAGIA;
        if (contiene(t, "create", "tech", "machine", "energy", "industrial", "pipe",
                "automation", "factory", "power")) return Etiqueta.TECNOLOGIA;
        if (contiene(t, "dungeon", "structure", "adventure", "quest", "boss", "raid",
                "village", "tower", "temple")) return Etiqueta.AVENTURA;
        if (contiene(t, "mob", "creature", "animal", "beast", "monster", "entity",
                "shark", "dragon", "creeper")) return Etiqueta.CRIATURAS;
        if (contiene(t, "deco", "furniture", "build", "chair", "supplementaries",
                "copycat", "paint")) return Etiqueta.DECORACION;
        if (contiene(t, "biome", "terrain", "world", "cave", "ore", "nether", "end",
                "geophilic", "region")) return Etiqueta.MUNDO;
        if (contiene(t, "jei", "map", "waypoint", "backpack", "storage", "inventory",
                "tooltip", "config", "menu")) return Etiqueta.UTILIDAD;
        return Etiqueta.OTRO;
    }

    private static boolean contiene(String texto, String... claves) {
        for (String k : claves) {
            if (texto.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Candidatos a purga: jars que probablemente sobran.
     *
     * Se sugieren, nunca se borran. Cada uno pasa por el mismo boton de
     * confirmacion que todo lo demas, y va al backup, no a la basura.
     *
     * Tres criterios, todos verificables:
     *   - jars deshabilitados hace mas de 30 dias (ya no los usas);
     *   - librerias que nadie declara usar;
     *   - jars sin metadatos de mod que tampoco son libreria declarada.
     */
    public static List<Problema> candidatosAPurga(List<MetadatosJar> jars, Path carpetaMods) {
        List<Problema> out = new ArrayList<>(librerasHuerfanas(jars));

        long treintaDias = 30L * 24 * 60 * 60 * 1000;
        long ahora = System.currentTimeMillis();

        try (var stream = java.nio.file.Files.newDirectoryStream(carpetaMods, "*.disabled")) {
            for (Path p : stream) {
                long modificado = java.nio.file.Files.getLastModifiedTime(p).toMillis();
                long dias = (ahora - modificado) / (24L * 60 * 60 * 1000);
                if (ahora - modificado < treintaDias) {
                    continue;
                }
                out.add(new Problema(
                        Severidad.INFO,
                        Problema.Categoria.ALTERNATIVA_SUGERIDA,
                        p.getFileName() + " lleva " + dias + " dias deshabilitado",
                        "Sigue ocupando lugar en la carpeta pero el juego no lo carga.",
                        "Si ya no lo vas a usar, movelo al backup para tener la carpeta mas "
                                + "limpia. No cambia nada del juego: ya estaba apagado.",
                        null, p));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Librerias que ningun mod instalado declara como dependencia.
     *
     * Esto SI es verificable: se recorren todas las dependencias declaradas de
     * todos los jars y se ve quien quedo sin referencias. Aun asi se reporta
     * como aviso y no como error, porque un mod puede usar una libreria en
     * tiempo de ejecucion sin declararla en su mods.toml.
     */
    public static List<Problema> librerasHuerfanas(List<MetadatosJar> jars) {
        List<Problema> out = new ArrayList<>();

        Set<String> referenciadas = new HashSet<>();
        for (MetadatosJar j : jars) {
            for (MetadatosJar.Dependencia d : j.dependencias()) {
                referenciadas.add(d.modId());
            }
        }

        for (MetadatosJar j : jars) {
            Etiqueta e = clasificar(j);
            if (e != Etiqueta.LIBRERIA) {
                continue;
            }
            boolean alguienLaUsa = j.todosLosModIds().stream().anyMatch(referenciadas::contains);
            if (alguienLaUsa || j.todosLosModIds().isEmpty()) {
                continue;
            }
            out.add(new Problema(
                    Severidad.INFO,
                    Problema.Categoria.ALTERNATIVA_SUGERIDA,
                    j.nombreVisible() + " es una libreria que nadie declara usar",
                    "Ningun mod instalado la nombra en sus dependencias ("
                            + j.nombreArchivo() + ")",
                    "Podria sobrar y estar sumando tiempo de carga. OJO: algunos mods usan "
                            + "librerias sin declararlas, asi que probá sacarla y verificá que "
                            + "el juego siga arrancando antes de darla por descartada.",
                    j.modIdPrincipal(),
                    j.archivo()));
        }
        return out;
    }
}
