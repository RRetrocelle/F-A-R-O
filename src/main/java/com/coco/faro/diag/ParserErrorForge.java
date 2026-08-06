package com.coco.faro.diag;

import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsea los mensajes de la pantalla nativa "Error loading mods" de Forge.
 *
 * Esa pantalla aparece antes que cualquier crash report, cuando la resolucion de
 * dependencias falla. El texto que muestra es estructurado y predecible:
 *
 *   Mod hexalia requires geckolib 4.4.2 or above
 *   Currently, geckolib is not installed
 *
 * Con ese formato no hace falta ninguna heuristica ni IA: sale el nombre exacto
 * del mod que falta y la version minima, leyendo texto. La IA se guarda para los
 * casos ambiguos, que son otros.
 */
public final class ParserErrorForge {

    /** "Mod X requires Y Z or above" / "... requires Y [1.0,2.0)". */
    private static final Pattern REQUIERE = Pattern.compile(
            "Mod\\s+([A-Za-z0-9_\\-]+)\\s+requires\\s+([A-Za-z0-9_\\-]+)\\s+(.+?)\\s*(?:or above)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** "Currently, Y is not installed". */
    private static final Pattern NO_INSTALADO = Pattern.compile(
            "Currently,?\\s+([A-Za-z0-9_\\-]+)\\s+is\\s+not\\s+installed",
            Pattern.CASE_INSENSITIVE);

    /** "Currently, Y is 1.2.3" — instalada pero con version que no sirve. */
    private static final Pattern VERSION_ACTUAL = Pattern.compile(
            "Currently,?\\s+([A-Za-z0-9_\\-]+)\\s+is\\s+(\\S+)",
            Pattern.CASE_INSENSITIVE);

    /** Una dependencia faltante extraida del texto de Forge. */
    public record Faltante(String modQueLaPide, String modIdFaltante,
                           String versionPedida, String versionActual) {

        public boolean estaInstalada() {
            return versionActual != null && !versionActual.isBlank();
        }

        public RangoVersion rango() {
            return RangoVersion.de(normalizarRango(versionPedida));
        }

        /** Forge escribe "4.4.2 or above"; lo pasamos a rango Maven. */
        private static String normalizarRango(String v) {
            if (v == null || v.isBlank()) {
                return "";
            }
            String s = v.trim();
            if (s.startsWith("[") || s.startsWith("(")) {
                return s;
            }
            return "[" + s + ",)";
        }
    }

    private ParserErrorForge() {
    }

    /**
     * Extrae las dependencias faltantes de un bloque de texto.
     *
     * Se agrupan por modId faltante para no repetir: si cuatro mods piden
     * geckolib, es un solo problema a resolver, no cuatro.
     */
    public static List<Faltante> parsear(String texto) {
        List<Faltante> out = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            return out;
        }

        Map<String, Faltante> porModIdFaltante = new LinkedHashMap<>();
        String[] lineas = texto.split("\\R");

        for (int i = 0; i < lineas.length; i++) {
            Matcher m = REQUIERE.matcher(lineas[i].trim());
            if (!m.find()) {
                continue;
            }
            String pide = m.group(1);
            String falta = m.group(2).toLowerCase(Locale.ROOT);
            String version = m.group(3).trim();

            // La linea siguiente suele decir si esta instalada y en que version.
            String actual = null;
            if (i + 1 < lineas.length) {
                String sig = lineas[i + 1].trim();
                if (NO_INSTALADO.matcher(sig).find()) {
                    actual = null;
                } else {
                    Matcher va = VERSION_ACTUAL.matcher(sig);
                    if (va.find()) {
                        actual = va.group(2);
                    }
                }
            }

            Faltante f = new Faltante(pide, falta, version, actual);
            Faltante previo = porModIdFaltante.get(falta);
            if (previo == null) {
                porModIdFaltante.put(falta, f);
            } else {
                // Ya lo teniamos: sumamos el nombre del mod que tambien lo pide.
                porModIdFaltante.put(falta, new Faltante(
                        previo.modQueLaPide() + ", " + pide,
                        falta, previo.versionPedida(), previo.versionActual()));
            }
        }

        out.addAll(porModIdFaltante.values());
        return out;
    }

    /**
     * Lee latest.log y saca de ahi el bloque de errores de carga.
     *
     * Se usa cuando la pantalla de error de Forge no expone su texto de forma
     * accesible: el log siempre tiene lo mismo, y leerlo es fiable.
     */
    public static List<Faltante> desdeLog() {
        try {
            Path log = FMLPaths.GAMEDIR.get().resolve("logs").resolve("latest.log");
            if (!Files.isRegularFile(log)) {
                return List.of();
            }
            String contenido = Files.readString(log, StandardCharsets.UTF_8);
            return parsear(contenido);
        } catch (Throwable t) {
            try {
                Path log = FMLPaths.GAMEDIR.get().resolve("logs").resolve("latest.log");
                return parsear(new String(Files.readAllBytes(log), StandardCharsets.ISO_8859_1));
            } catch (Throwable t2) {
                return List.of();
            }
        }
    }

    /** true si el texto tiene la pinta del error de dependencias de Forge. */
    public static boolean pareceErrorDeDependencias(String texto) {
        return texto != null
                && (REQUIERE.matcher(texto).find() || NO_INSTALADO.matcher(texto).find());
    }

    // ------------------------------------------------------ otros fallos de precarga

    /** "Mod X is built for Minecraft/Forge Y, but Z is installed". */
    private static final Pattern VERSION_ENTORNO = Pattern.compile(
            "Mod\\s+([A-Za-z0-9_\\-]+)\\s+.*?(?:built for|requires)\\s+(minecraft|forge)\\s+(\\S+)",
            Pattern.CASE_INSENSITIVE);

    /** "Mod file X has mods that were not found" / errores de construccion. */
    private static final Pattern FALLO_CONSTRUCCION = Pattern.compile(
            "(?:Failed to create mod instance|Exception caught during firing event|"
                    + "constructing mod|Mod Loading has failed)[^\\n]*",
            Pattern.CASE_INSENSITIVE);

    /** "Duplicate mod ids" o el nombre de la excepcion. */
    private static final Pattern DUPLICADOS = Pattern.compile(
            "(?:DuplicateModsFoundException|Duplicate mod ids)[^\\n]*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Diagnostica cualquier fallo de precarga, no solo dependencias faltantes.
     *
     * La ronda anterior cubria un unico patron. Aca se agregan los otros fallos
     * que frenan el arranque antes de que se construya un mod: version de
     * Minecraft o Forge incompatible, ids duplicados, y excepciones al construir
     * un mod.
     *
     * Lo importante es el ultimo bloque: cuando NINGUN patron coincide, se
     * devuelve un problema que dice exactamente eso y adjunta el texto crudo. Es
     * preferible a fallar en silencio o a dejar la pantalla vanilla sin agregar
     * nada, porque el usuario al menos ve el error y puede copiarlo.
     */
    public static List<Problema> diagnosticarPrecarga(String texto) {
        List<Problema> out = new ArrayList<>();
        if (texto == null || texto.isBlank()) {
            return out;
        }

        Matcher mv = VERSION_ENTORNO.matcher(texto);
        while (mv.find()) {
            out.add(new Problema(
                    Severidad.CRITICA,
                    Problema.Categoria.DEPENDENCIA_VERSION,
                    "'" + mv.group(1) + "' es para otra version de " + mv.group(2).toLowerCase(),
                    "Pide " + mv.group(2).toLowerCase() + " " + mv.group(3),
                    "Ese mod no es para este pack. Buscá la build correspondiente a "
                            + "Minecraft 1.20.1 con Forge, o sacalo.",
                    mv.group(1), null));
        }

        Matcher md = DUPLICADOS.matcher(texto);
        if (md.find()) {
            out.add(new Problema(
                    Severidad.CRITICA,
                    Problema.Categoria.MOD_DUPLICADO,
                    "Hay dos copias del mismo mod",
                    md.group().trim(),
                    "Forge no arranca con dos jars que declaran el mismo mod. "
                            + "Dejá una sola copia, normalmente la de version mas alta.",
                    null, null));
        }

        Matcher mc = FALLO_CONSTRUCCION.matcher(texto);
        if (mc.find()) {
            out.add(new Problema(
                    Severidad.CRITICA,
                    Problema.Categoria.SIN_METADATOS,
                    "Un mod fallo al construirse",
                    mc.group().trim(),
                    "El mod se cargo pero reviento al inicializarse. Suele ser un choque con "
                            + "otro mod o una version equivocada de una libreria. Mirá la "
                            + "consola para ver cual es.",
                    null, null));
        }

        if (out.isEmpty()) {
            // Honestidad explicita: no lo reconocemos, pero mostramos el texto.
            out.add(new Problema(
                    Severidad.ALTA,
                    Problema.Categoria.SIN_METADATOS,
                    "No reconozco este tipo de error todavia",
                    recorte(texto),
                    "Faro no tiene un patron para este fallo, asi que no te voy a sugerir un "
                            + "arreglo que puede estar mal. Arriba esta el texto crudo: copialo "
                            + "con el boton de exportar y buscá por ese mensaje, o pedí ayuda "
                            + "con eso.",
                    null, null));
        }
        return out;
    }

    private static String recorte(String texto) {
        String limpio = texto.strip();
        // Nos quedamos con las primeras lineas con contenido, que es donde suele
        // estar el mensaje util; el resto es ruido de arranque.
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String l : limpio.split("\\R")) {
            String t = l.strip();
            if (t.isEmpty()) {
                continue;
            }
            sb.append(t).append('\n');
            if (++n >= 6) {
                break;
            }
        }
        return sb.toString().strip();
    }
}
