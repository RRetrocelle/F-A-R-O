package com.coco.faro.diag;

import net.minecraft.SharedConstants;
import net.minecraftforge.versions.forge.ForgeVersion;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Revisa la instalacion entera y reporta problemas ANTES de que causen un crash.
 *
 * Esta es la parte del mod que mas veces le va a servir al usuario, porque no
 * necesita que algo se rompa primero. Detecta:
 *
 *   - dos jars declarando el mismo modId (crash garantizado al arrancar);
 *   - jars de Fabric o NeoForge tirados en una instancia Forge (no cargan nunca);
 *   - dependencias obligatorias que no estan instaladas;
 *   - dependencias presentes pero con una version fuera del rango pedido;
 *   - rangos "abiertos" que aceptan la version instalada de casualidad, que es
 *     el caso mas traicionero porque Forge no avisa y el juego revienta despues.
 */
public final class AnalizadorDependencias {

    private AnalizadorDependencias() {
    }

    public static List<Problema> analizar(List<MetadatosJar> jars) {
        List<Problema> problemas = new ArrayList<>();

        // Mapa modId -> version instalada, incluyendo los pseudo-mods del entorno.
        Map<String, String> versionPorId = new HashMap<>();
        Map<String, List<MetadatosJar>> jarsPorId = new LinkedHashMap<>();

        versionPorId.put("minecraft", versionMinecraft());
        versionPorId.put("forge", versionForge());
        versionPorId.put("fml", versionForge());

        for (MetadatosJar j : jars) {
            for (String id : j.modIds()) {
                versionPorId.put(id, j.version());
                jarsPorId.computeIfAbsent(id, k -> new ArrayList<>()).add(j);
            }
            // Los mods anidados (JarInJar) cuentan como instalados: Forge los
            // carga igual que si estuvieran sueltos en la carpeta. Lo que NO
            // hacemos es sumarlos a jarsPorId, porque dos jars que empaquetan la
            // misma libreria no son un duplicado — Forge resuelve eso solo
            // quedandose con la version mas alta.
            for (String id : j.modIdsAnidados()) {
                versionPorId.putIfAbsent(id, "");
            }
        }

        detectarDuplicados(jarsPorId, problemas);
        detectarLoaderIncorrecto(jars, problemas);
        detectarDependencias(jars, versionPorId, problemas);

        // Nivel 1: lo que los mods declaran. Certeza.
        problemas.addAll(BaseConflictos.conflictosDeclarados(jars));
        // Nivel 2: lista curada a mano. Se muestra como "revisar", no como certeza.
        problemas.addAll(BaseConflictos.posiblesSolapamientos(jars));
        problemas.addAll(BaseConflictos.modsConAlternativa(jars));
        // Candidatos a purga: librerias huerfanas y jars deshabilitados hace
        // mucho. Sugerencias, nunca borrado automatico.
        if (!jars.isEmpty()) {
            problemas.addAll(EtiquetadorMods.candidatosAPurga(
                    jars, jars.get(0).archivo().getParent()));
        }

        problemas.sort(Comparator
                .comparingInt((Problema p) -> -p.severidad().peso())
                .thenComparing(Problema::titulo));
        return problemas;
    }

    // ------------------------------------------------------------------ chequeos

    private static void detectarDuplicados(Map<String, List<MetadatosJar>> jarsPorId,
                                           List<Problema> out) {
        for (Map.Entry<String, List<MetadatosJar>> e : jarsPorId.entrySet()) {
            List<MetadatosJar> lista = e.getValue();
            if (lista.size() < 2) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (MetadatosJar j : lista) {
                if (sb.length() > 0) sb.append("  |  ");
                sb.append(j.nombreArchivo());
            }
            out.add(new Problema(
                    Severidad.CRITICA,
                    Problema.Categoria.MOD_DUPLICADO,
                    "Dos jars declaran el mod '" + e.getKey() + "'",
                    "Archivos: " + sb,
                    "Forge no arranca con dos copias del mismo mod. Deja una sola "
                            + "(normalmente la de version mas alta) y sacá la otra.",
                    e.getKey(),
                    lista.get(0).archivo()));
        }
    }

    private static void detectarLoaderIncorrecto(List<MetadatosJar> jars, List<Problema> out) {
        for (MetadatosJar j : jars) {
            switch (j.loader()) {
                case FABRIC -> out.add(new Problema(
                        Severidad.ALTA,
                        Problema.Categoria.LOADER_INCORRECTO,
                        "Mod de Fabric en una instancia Forge",
                        j.nombreArchivo() + " solo trae fabric.mod.json, sin META-INF/mods.toml.",
                        "Forge lo ignora por completo: ocupa lugar y no hace nada. "
                                + "Sacalo o buscá la version para Forge.",
                        null,
                        j.archivo()));

                case NEOFORGE -> out.add(new Problema(
                        Severidad.ALTA,
                        Problema.Categoria.LOADER_INCORRECTO,
                        "Mod de NeoForge en una instancia Forge",
                        j.nombreArchivo() + " declara neoforge.mods.toml.",
                        "NeoForge y Forge no son compatibles. Necesitás la build de Forge.",
                        null,
                        j.archivo()));

                case NINGUNO -> {
                    // No es un problema si el manifest lo declara libreria, o si
                    // aporta mods por JarInJar: en ambos casos hace su trabajo
                    // sin tener un mods.toml propio.
                    if (j.esLibreria() || !j.modIdsAnidados().isEmpty()) {
                        break;
                    }
                    out.add(new Problema(
                            Severidad.INFO,
                            Problema.Categoria.SIN_METADATOS,
                            "Jar sin metadatos de mod",
                            j.nombreArchivo() + " no declara ningun mod.",
                            "Suele ser una libreria o un coremod, y eso es normal. "
                                    + "Pero si esperabas que fuera un mod, no va a aparecer en la lista.",
                            null,
                            j.archivo()));
                }

                default -> {
                    // FORGE y MIXTO cargan bien en esta instancia.
                }
            }
        }
    }

    private static void detectarDependencias(List<MetadatosJar> jars,
                                             Map<String, String> versionPorId,
                                             List<Problema> out) {
        for (MetadatosJar j : jars) {
            for (MetadatosJar.Dependencia d : j.dependencias()) {
                String idDep = d.modId();
                boolean instalada = versionPorId.containsKey(idDep);

                if (!instalada) {
                    if (!d.obligatoria()) {
                        continue; // las opcionales que faltan no son un problema
                    }
                    out.add(new Problema(
                            Severidad.CRITICA,
                            Problema.Categoria.DEPENDENCIA_AUSENTE,
                            j.nombreVisible() + " necesita '" + idDep + "' y no esta",
                            "Rango pedido: " + textoRango(d.rango()),
                            "Instalá " + idDep + " para 1.20.1 Forge. "
                                    + "Sacar mods no arregla esto: falta uno, no sobra.",
                            j.modIdPrincipal(),
                            j.archivo()));
                    continue;
                }

                String versionInstalada = versionPorId.get(idDep);
                if (versionInstalada == null || versionInstalada.isBlank()) {
                    continue; // sin version conocida no podemos afirmar nada
                }

                if (!d.rango().acepta(versionInstalada)) {
                    out.add(new Problema(
                            Severidad.ALTA,
                            Problema.Categoria.DEPENDENCIA_VERSION,
                            j.nombreVisible() + " pide otra version de '" + idDep + "'",
                            "Instalada: " + versionInstalada + "   |   pedida: " + textoRango(d.rango()),
                            "Actualizá o bajá alguno de los dos hasta que las versiones coincidan.",
                            j.modIdPrincipal(),
                            j.archivo()));
                    continue;
                }

                // Entra en el rango, pero conviene mirar POR QUE entra.
                if (d.obligatoria() && d.rango().aceptaPorFaltaDeTecho(versionInstalada)) {
                    out.add(new Problema(
                            Severidad.MEDIA,
                            Problema.Categoria.RANGO_BLANDO,
                            j.nombreVisible() + ": compatibilidad con '" + idDep + "' no verificable",
                            "Declara " + textoRango(d.rango()) + " y hay instalada la "
                                    + versionInstalada + ". Entra solo porque el rango no tiene techo.",
                            "El mod fue escrito para una version bastante anterior de " + idDep
                                    + ". Forge lo va a cargar igual y, si la API cambio, revienta "
                                    + "en pleno juego. Probalo en un mundo de prueba primero.",
                            j.modIdPrincipal(),
                            j.archivo()));
                }
            }
        }
    }

    // ------------------------------------------------------------------ utilidades

    private static String textoRango(RangoVersion r) {
        if (r == null || r.esVacio()) {
            return "cualquiera";
        }
        return r.esBlando() ? r.original() + " (requisito blando)" : r.original();
    }

    private static String versionMinecraft() {
        try {
            return SharedConstants.getCurrentVersion().getName();
        } catch (Throwable t) {
            return "1.20.1";
        }
    }

    private static String versionForge() {
        try {
            String v = ForgeVersion.getVersion();
            return v == null ? "47.0.0" : v.toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "47.0.0";
        }
    }
}
