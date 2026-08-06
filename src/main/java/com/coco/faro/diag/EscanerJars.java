package com.coco.faro.diag;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Lee la carpeta mods y extrae los metadatos de cada .jar directamente del archivo.
 *
 * Deliberadamente NO usa ModList: eso solo conoce lo que Forge logro cargar. Aca
 * queremos ver tambien lo que NO cargo, que es justo donde estan los problemas.
 *
 * El parser de mods.toml es minimo pero respeta las secciones, que es el detalle
 * que importa: los modId de los bloques [[dependencies.x]] son dependencias, no
 * mods provistos por el jar. Confundirlos hace que un addon de Create parezca
 * proveer Create.
 */
public final class EscanerJars {

    private static final Pattern CLAVE_VALOR =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*[\"']([^\"']*)[\"']");
    private static final Pattern CLAVE_BOOL =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(true|false)");
    private static final Pattern CABECERA_DEP =
            Pattern.compile("^\\[\\[?dependencies(?:\\.([A-Za-z0-9_\\-]+))?\\]?\\]$",
                    Pattern.CASE_INSENSITIVE);

    private EscanerJars() {
    }

    public static List<MetadatosJar> escanear(Path carpetaMods) {
        List<MetadatosJar> resultado = new ArrayList<>();
        if (carpetaMods == null || !Files.isDirectory(carpetaMods)) {
            return resultado;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(carpetaMods, "*.jar")) {
            for (Path jar : stream) {
                MetadatosJar m = leer(jar);
                if (m != null) {
                    resultado.add(m);
                }
            }
        } catch (IOException ignored) {
        }
        return resultado;
    }

    private static MetadatosJar leer(Path jar) {
        long tamano = 0L;
        long modificado = 0L;
        try {
            tamano = Files.size(jar);
            modificado = Files.getLastModifiedTime(jar).toMillis();
        } catch (IOException ignored) {
        }

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            boolean forge = zip.getEntry("META-INF/mods.toml") != null;
            boolean fabric = zip.getEntry("fabric.mod.json") != null;
            boolean neo = zip.getEntry("META-INF/neoforge.mods.toml") != null;

            MetadatosJar.Loader loader;
            if (forge && fabric) {
                loader = MetadatosJar.Loader.MIXTO;
            } else if (forge) {
                loader = MetadatosJar.Loader.FORGE;
            } else if (fabric) {
                loader = MetadatosJar.Loader.FABRIC;
            } else if (neo) {
                loader = MetadatosJar.Loader.NEOFORGE;
            } else {
                loader = MetadatosJar.Loader.NINGUNO;
            }

            MetadatosJar meta = new MetadatosJar(jar, tamano, modificado, loader);

            if (forge) {
                ZipEntry e = zip.getEntry("META-INF/mods.toml");
                try (InputStream in = zip.getInputStream(e)) {
                    parsearModsToml(new String(in.readAllBytes(), StandardCharsets.UTF_8), meta);
                }
            }

            leerTipoFML(zip, meta);
            resolverVersionDelManifest(zip, meta);
            leerJarsAnidados(zip, meta);

            return meta;
        } catch (Throwable t) {
            // Un jar ilegible se reporta igual, con loader NINGUNO, para que el
            // analizador pueda avisar en vez de omitirlo en silencio.
            return new MetadatosJar(jar, tamano, modificado, MetadatosJar.Loader.NINGUNO);
        }
    }

    /**
     * Resuelve el placeholder ${file.jarVersion} de mods.toml.
     *
     * Muchisimos mods declaran version="${file.jarVersion}" y dejan que Forge lo
     * sustituya al arrancar, leyendo Implementation-Version del MANIFEST. Si se
     * lee el .toml crudo queda el literal "${file.jarVersion}", y compararlo
     * contra un rango de versiones nunca da verdadero.
     *
     * Ese era el origen de una tanda entera de falsos positivos: Faro reportaba
     * "pide otra version" de mods que en realidad estaban perfectos, y el aviso
     * no se iba nunca porque no habia nada que arreglar.
     */
    private static void resolverVersionDelManifest(ZipFile zip, MetadatosJar meta) {
        String v = meta.version();
        if (v == null || !v.contains("${")) {
            return;
        }
        ZipEntry mf = zip.getEntry("META-INF/MANIFEST.MF");
        if (mf == null) {
            meta.version("");
            return;
        }
        try (InputStream in = zip.getInputStream(mf)) {
            String contenido = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String linea : contenido.split("\\R")) {
                String t = linea.trim();
                if (t.regionMatches(true, 0, "Implementation-Version:", 0, 23)) {
                    meta.version(t.substring(23).trim());
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        // Sin manifest utilizable se deja vacio a proposito: "no se" es mejor
        // que un placeholder que se compara mal y genera avisos falsos.
        if (meta.version().contains("${")) {
            meta.version("");
        }
    }

    /** Lee FMLModType del MANIFEST: distingue una libreria de un mod normal. */
    private static void leerTipoFML(ZipFile zip, MetadatosJar meta) {
        ZipEntry mf = zip.getEntry("META-INF/MANIFEST.MF");
        if (mf == null) {
            return;
        }
        try (InputStream in = zip.getInputStream(mf)) {
            String contenido = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String linea : contenido.split("\\R")) {
                String t = linea.trim();
                if (t.regionMatches(true, 0, "FMLModType:", 0, 11)) {
                    meta.tipoFML(t.substring(11).trim());
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Abre los .jar embebidos en META-INF/jarjar/ y anota que mods aportan.
     *
     * Este es el paso que evita el falso positivo mas comun del analisis: sin el,
     * un mod que trae su dependencia adentro parece que le falta. PuzzlesLib
     * empaqueta puzzlesaccessapi, Kotlin For Forge empaqueta kffmod, y asi.
     *
     * Se lee el jar anidado en memoria con ZipInputStream. Un nivel de anidamiento
     * alcanza en la practica: Forge no anida mas hondo que eso.
     */
    private static void leerJarsAnidados(ZipFile zip, MetadatosJar meta) {
        Enumeration<? extends ZipEntry> entradas = zip.entries();
        while (entradas.hasMoreElements()) {
            ZipEntry e = entradas.nextElement();
            String nombre = e.getName();
            if (e.isDirectory()
                    || !nombre.startsWith("META-INF/jarjar/")
                    || !nombre.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }

            String simple = nombre.substring(nombre.lastIndexOf('/') + 1);
            meta.agregarJarAnidado(simple);

            try (ZipInputStream zis = new ZipInputStream(zip.getInputStream(e))) {
                ZipEntry interna;
                while ((interna = zis.getNextEntry()) != null) {
                    if (!"META-INF/mods.toml".equals(interna.getName())) {
                        continue;
                    }
                    String toml = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    for (String id : modIdsDeToml(toml)) {
                        meta.agregarModIdAnidado(id);
                    }
                    break;
                }
            } catch (Throwable ignored) {
                // Un jar anidado ilegible no debe tumbar el escaneo del resto.
            }
        }
    }

    /** Extrae solo los modId propios (seccion [[mods]]) de un mods.toml suelto. */
    private static Set<String> modIdsDeToml(String contenido) {
        Set<String> ids = new LinkedHashSet<>();
        boolean enMods = false;
        for (String cruda : contenido.split("\\R")) {
            String linea = quitarComentario(cruda).trim();
            if (linea.startsWith("[")) {
                enMods = linea.toLowerCase(Locale.ROOT).startsWith("[[mods]]");
                continue;
            }
            if (!enMods) {
                continue;
            }
            String id = valor(linea, "modId");
            if (id != null) {
                ids.add(id.toLowerCase(Locale.ROOT));
            }
        }
        return ids;
    }

    private enum Seccion { NINGUNA, MODS, DEPENDENCIAS }

    private static void parsearModsToml(String contenido, MetadatosJar meta) {
        Seccion seccion = Seccion.NINGUNA;

        String depModId = null;
        boolean depObligatoria = true;
        String depRango = "";
        String depLado = "BOTH";
        String depTipo = "";
        boolean hayDepPendiente = false;

        boolean primerMod = true;

        for (String cruda : contenido.split("\\R")) {
            String linea = quitarComentario(cruda).trim();
            if (linea.isEmpty()) {
                continue;
            }

            if (linea.startsWith("[")) {
                // Cambio de seccion: primero cerramos lo que veniamos juntando.
                if (hayDepPendiente && depModId != null) {
                    meta.agregarDependencia(new MetadatosJar.Dependencia(
                            depModId.toLowerCase(Locale.ROOT), depObligatoria,
                            RangoVersion.de(depRango), depLado, depTipo));
                }
                depModId = null;
                depObligatoria = true;
                depRango = "";
                depLado = "BOTH";
                depTipo = "";
                hayDepPendiente = false;

                Matcher dep = CABECERA_DEP.matcher(linea);
                if (dep.matches()) {
                    seccion = Seccion.DEPENDENCIAS;
                    hayDepPendiente = true;
                } else if (linea.toLowerCase(Locale.ROOT).startsWith("[[mods]]")) {
                    seccion = Seccion.MODS;
                } else {
                    seccion = Seccion.NINGUNA;
                }
                continue;
            }

            switch (seccion) {
                case MODS -> {
                    String id = valor(linea, "modId");
                    if (id != null) {
                        meta.agregarModId(id.toLowerCase(Locale.ROOT));
                        if (primerMod) {
                            String v = valor(linea, "version");
                            if (v != null) meta.version(v);
                            String n = valor(linea, "displayName");
                            if (n != null) meta.nombreVisible(n);
                            primerMod = false;
                        }
                    }
                    // Formato clasico: claves en lineas separadas.
                    if (id == null) {
                        String v = valor(linea, "version");
                        if (v != null && meta.version().isEmpty()) meta.version(v);
                        String n = valor(linea, "displayName");
                        if (n != null && meta.nombreVisible().equals(meta.modIdPrincipal())) {
                            meta.nombreVisible(n);
                        }
                    }
                }
                case DEPENDENCIAS -> {
                    String id = valor(linea, "modId");
                    if (id != null) depModId = id;
                    String rango = valor(linea, "versionRange");
                    if (rango != null) depRango = rango;
                    String lado = valor(linea, "side");
                    if (lado != null) depLado = lado;
                    String tipo = valor(linea, "type");
                    if (tipo != null) depTipo = tipo;
                    Boolean obl = valorBool(linea, "mandatory");
                    if (obl != null) depObligatoria = obl;
                }
                default -> {
                    // Fuera de seccion conocida: ignoramos.
                }
            }
        }

        if (hayDepPendiente && depModId != null) {
            meta.agregarDependencia(new MetadatosJar.Dependencia(
                    depModId.toLowerCase(Locale.ROOT), depObligatoria,
                    RangoVersion.de(depRango), depLado, depTipo));
        }
    }

    private static String quitarComentario(String linea) {
        // Corta en '#' salvo que este dentro de comillas.
        boolean enComillaSimple = false;
        boolean enComillaDoble = false;
        for (int i = 0; i < linea.length(); i++) {
            char c = linea.charAt(i);
            if (c == '\'' && !enComillaDoble) enComillaSimple = !enComillaSimple;
            else if (c == '"' && !enComillaSimple) enComillaDoble = !enComillaDoble;
            else if (c == '#' && !enComillaSimple && !enComillaDoble) return linea.substring(0, i);
        }
        return linea;
    }

    private static String valor(String linea, String clave) {
        Matcher m = CLAVE_VALOR.matcher(linea);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(clave)) {
                return m.group(2);
            }
        }
        return null;
    }

    private static Boolean valorBool(String linea, String clave) {
        Matcher m = CLAVE_BOOL.matcher(linea);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase(clave)) {
                return Boolean.parseBoolean(m.group(2));
            }
        }
        return null;
    }
}
