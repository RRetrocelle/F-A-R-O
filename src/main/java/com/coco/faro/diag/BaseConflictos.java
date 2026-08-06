package com.coco.faro.diag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Conflictos entre mods y alternativas conocidas.
 *
 * Hay una separacion deliberada en dos niveles de confianza, y no se mezclan:
 *
 *   1. CONFLICTO DECLARADO — el propio mod dice en su mods.toml que es
 *      incompatible con otro. Eso es un hecho, no una opinion, y se muestra
 *      como certeza.
 *
 *   2. POSIBLE SOLAPAMIENTO — dos mods que probablemente hacen lo mismo. Esto
 *      NO se puede deducir del metadata: sale de una lista curada a mano. Se
 *      muestra como "revisar", nunca como conflicto confirmado, porque hay casos
 *      legitimos de convivencia y porque la lista siempre va a estar incompleta.
 *
 * Las listas son cortas a proposito. Prefiero pocos pares ciertos que muchos
 * dudosos: un falso positivo aca lleva al usuario a desinstalar algo que
 * funcionaba bien.
 */
public final class BaseConflictos {

    /** Un par de mods que probablemente se pisan, con el motivo. */
    public record Solapamiento(String modA, String modB, String motivo, String recomendacion) {
    }

    /** Un reemplazo conocido para un mod discontinuado o problematico. */
    public record Alternativa(String modViejo, String nombreLindo, String reemplazo,
                              String motivo) {
    }

    private static final List<Solapamiento> SOLAPAMIENTOS = new ArrayList<>();
    private static final Map<String, Alternativa> ALTERNATIVAS = new LinkedHashMap<>();

    static {
        // ---- Solapamientos funcionales (nivel 2: "revisar") -------------
        sol("canary", "radium",
                "Los dos son ports de Lithium para Forge: optimizan lo mismo del lado del servidor.",
                "Dejá uno solo. Tener ambos no suma rendimiento y pueden pisarse entre si.");

        sol("canary", "lithium",
                "Canary ya es el port de Lithium para Forge.",
                "No hace falta tener los dos.");

        sol("embeddium", "rubidium",
                "Embeddium es el sucesor directo de Rubidium: el mismo motor de renderizado.",
                "Quedate con Embeddium y sacá Rubidium.");

        sol("embeddium", "sodium",
                "Sodium es la version Fabric de lo que Embeddium hace en Forge.",
                "En Forge va Embeddium. Sodium ni siquiera carga.");

        sol("embeddium", "xenon",
                "Xenon es otro port de Sodium para Forge.",
                "Dejá uno solo de los dos.");

        sol("entityculling", "brute_force_rendering_culling",
                "Los dos ocultan lo que no se ve para ganar FPS, por caminos distintos.",
                "Suelen convivir, pero si tenés fallos visuales probá sacando uno.");

        sol("memoryleakfix", "alltheleaks",
                "Ambos parchean fugas de memoria y se superponen bastante.",
                "Normalmente conviven bien. Si hay crashes raros al cargar, dejá uno.");

        sol("ferritecore", "redirected",
                "Los dos reducen memoria deduplicando estados de bloque.",
                "Conviven, pero la ganancia del segundo es chica si ya tenés el primero.");

        sol("starlight", "phosphor",
                "Los dos reescriben el motor de luz.",
                "Son incompatibles entre si. Dejá solo Starlight en 1.20.1.");

        // ---- Alternativas conocidas (nivel 2: "mejor esfuerzo") ---------
        alt("rubidium", "Rubidium", "embeddium",
                "Rubidium quedo discontinuado. Embeddium es su continuacion directa.");

        alt("optifine", "OptiFine", "embeddium",
                "OptiFine choca con casi todos los mods de rendimiento en Forge 1.20.1. "
                        + "Embeddium cubre el rendimiento, y Oculus los shaders.");

        alt("sodium", "Sodium", "embeddium",
                "Sodium es de Fabric. El equivalente en Forge es Embeddium.");

        alt("lithium", "Lithium", "canary",
                "Lithium es de Fabric. El port para Forge es Canary.");

        alt("phosphor", "Phosphor", "starlight",
                "Phosphor quedo obsoleto. Starlight lo reemplaza y es mas rapido.");

        alt("iris", "Iris", "oculus",
                "Iris es de Fabric. En Forge el equivalente es Oculus.");

        alt("magnesium", "Magnesium", "embeddium",
                "Magnesium quedo discontinuado.");
    }

    private static void sol(String a, String b, String motivo, String recomendacion) {
        SOLAPAMIENTOS.add(new Solapamiento(a, b, motivo, recomendacion));
    }

    private static void alt(String viejo, String lindo, String reemplazo, String motivo) {
        ALTERNATIVAS.put(viejo, new Alternativa(viejo, lindo, reemplazo, motivo));
    }

    private BaseConflictos() {
    }

    /**
     * Nivel 1: conflictos que los propios mods declaran.
     *
     * Forge permite marcar una dependencia como incompatible poniendo
     * {@code incompatible} o {@code discouraged} en el ordering/type. Cuando eso
     * aparece, no hay ambiguedad posible.
     */
    public static List<Problema> conflictosDeclarados(List<MetadatosJar> jars) {
        List<Problema> out = new ArrayList<>();
        Map<String, MetadatosJar> porId = new LinkedHashMap<>();
        for (MetadatosJar j : jars) {
            for (String id : j.todosLosModIds()) {
                porId.putIfAbsent(id, j);
            }
        }

        for (MetadatosJar j : jars) {
            for (MetadatosJar.Dependencia d : j.dependencias()) {
                if (!d.esIncompatible()) {
                    continue;
                }
                MetadatosJar otro = porId.get(d.modId());
                if (otro == null) {
                    continue; // el incompatible no esta instalado: no hay conflicto
                }
                out.add(new Problema(
                        Severidad.CRITICA,
                        Problema.Categoria.CONFLICTO_DECLARADO,
                        j.nombreVisible() + " declara ser incompatible con '" + d.modId() + "'",
                        "Archivos: " + j.nombreArchivo() + "  y  " + otro.nombreArchivo(),
                        "El propio mod avisa que no puede convivir con el otro. "
                                + "Hay que sacar uno de los dos.",
                        j.modIdPrincipal(),
                        j.archivo()));
            }
        }
        return out;
    }

    /**
     * Nivel 2: pares que probablemente se pisan. Se reportan como MEDIA y con el
     * texto "posible solapamiento", nunca como conflicto confirmado.
     */
    public static List<Problema> posiblesSolapamientos(List<MetadatosJar> jars) {
        List<Problema> out = new ArrayList<>();
        Map<String, MetadatosJar> porId = new LinkedHashMap<>();
        for (MetadatosJar j : jars) {
            for (String id : j.todosLosModIds()) {
                porId.putIfAbsent(id, j);
            }
        }

        for (Solapamiento s : SOLAPAMIENTOS) {
            MetadatosJar a = porId.get(s.modA());
            MetadatosJar b = porId.get(s.modB());
            if (a == null || b == null || a == b) {
                continue;
            }
            out.add(new Problema(
                    Severidad.MEDIA,
                    Problema.Categoria.POSIBLE_SOLAPAMIENTO,
                    "Posible solapamiento: " + s.modA() + " y " + s.modB(),
                    s.motivo() + "  (" + a.nombreArchivo() + " / " + b.nombreArchivo() + ")",
                    s.recomendacion() + "  Esto NO es un conflicto confirmado: "
                            + "sale de una lista curada a mano, revisalo vos.",
                    s.modB(),
                    b.archivo()));
        }
        return out;
    }

    /**
     * Solapamiento conocido entre dos modIds concretos, sin importar el orden.
     *
     * Lo usa el predictor de compatibilidad, que pregunta por un candidato contra
     * cada mod ya instalado. La lista es la misma que alimenta
     * {@link #posiblesSolapamientos}: una sola fuente para el mismo criterio.
     */
    public static Solapamiento solapamientoEntre(String modA, String modB) {
        if (modA == null || modB == null) {
            return null;
        }
        String a = modA.toLowerCase(Locale.ROOT);
        String b = modB.toLowerCase(Locale.ROOT);
        if (a.equals(b)) {
            return null;
        }
        for (Solapamiento s : SOLAPAMIENTOS) {
            if ((s.modA().equals(a) && s.modB().equals(b))
                    || (s.modA().equals(b) && s.modB().equals(a))) {
                return s;
            }
        }
        return null;
    }

    /** Reemplazo conocido para un mod, si lo hay. */
    public static Alternativa alternativaPara(String modId) {
        return modId == null ? null : ALTERNATIVAS.get(modId.toLowerCase(Locale.ROOT));
    }

    /** Mods instalados que tienen un reemplazo recomendado. */
    public static List<Problema> modsConAlternativa(List<MetadatosJar> jars) {
        List<Problema> out = new ArrayList<>();
        Set<String> instalados = new java.util.HashSet<>();
        for (MetadatosJar j : jars) {
            instalados.addAll(j.todosLosModIds());
        }

        for (MetadatosJar j : jars) {
            for (String id : j.todosLosModIds()) {
                Alternativa a = alternativaPara(id);
                if (a == null) {
                    continue;
                }
                boolean yaTieneElReemplazo = instalados.contains(a.reemplazo());
                out.add(new Problema(
                        yaTieneElReemplazo ? Severidad.MEDIA : Severidad.ALTA,
                        Problema.Categoria.ALTERNATIVA_SUGERIDA,
                        a.nombreLindo() + ": conviene reemplazarlo por '" + a.reemplazo() + "'",
                        a.motivo() + (yaTieneElReemplazo
                                ? "  Ya tenés " + a.reemplazo() + " instalado, asi que este sobra."
                                : ""),
                        yaTieneElReemplazo
                                ? "Deshabilitá " + j.nombreArchivo() + "."
                                : "Instalá " + a.reemplazo() + " y despues sacá este.",
                        id,
                        j.archivo()));
            }
        }
        return out;
    }

    public static int cantidadSolapamientos() {
        return SOLAPAMIENTOS.size();
    }

    public static int cantidadAlternativas() {
        return ALTERNATIVAS.size();
    }
}
