package com.coco.faro.diag;

import com.coco.faro.Faro;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Escaneo profundo de la carpeta config, para cuando el crash no tiene causa clara.
 *
 * Por que existe: el motor de sospecha trabaja sobre el stacktrace y los
 * metadatos. Cuando el fallo no deja rastro util —el juego se cierra sin
 * excepcion, o el stack es puro codigo de Minecraft— no hay a quien apuntar. Pero
 * hay una causa que ese camino nunca ve: un ARCHIVO DE CONFIGURACION editado a
 * mano que quedo roto o con un valor absurdo.
 *
 * Pasa mas seguido de lo que parece. Se toca un .toml para subir un limite, se
 * borra una coma, y el mod que lo lee revienta al arrancar sin decir cual era.
 * O se pone un valor de 1000 donde el mod espera hasta 64 y el juego se come
 * toda la memoria.
 *
 * Que revisa, en orden de utilidad:
 *
 *   1. ARCHIVOS ROTOS — .json y .toml que no parsean. Esto es certeza absoluta:
 *      o el archivo es valido o no lo es, no hay interpretacion.
 *   2. EDITADOS JUSTO ANTES DEL CRASH — un config modificado en la hora previa al
 *      ultimo crash report es el sospechoso mas natural que existe, y es
 *      exactamente el dato que nadie mira.
 *   3. VALORES FUERA DE ESCALA — numeros absurdamente grandes en claves que
 *      hablan de distancia, radio, cantidad o memoria.
 *   4. ARCHIVOS VACIOS — un config de 0 bytes suele ser un mod que no llego a
 *      escribirlo porque se cayo antes.
 *
 * Los puntos 1, 2 y 4 son verificables y se reportan con certeza alta. El 3 es
 * heuristica y se marca como tal: un valor grande puede ser perfectamente valido.
 */
public final class EscanerConfigs {

    public enum Tipo {
        ROTO("Archivo con formato invalido", Severidad.CRITICA, Certeza.ALTA),
        RECIENTE("Editado justo antes del crash", Severidad.ALTA, Certeza.ALTA),
        VACIO("Archivo vacio", Severidad.MEDIA, Certeza.ALTA),
        VALOR_EXTREMO("Valor fuera de escala", Severidad.MEDIA, Certeza.MEDIA),
        GRANDE("Config inusualmente grande", Severidad.INFO, Certeza.ALTA);

        public final String etiqueta;
        public final Severidad severidad;
        public final Certeza certeza;

        Tipo(String etiqueta, Severidad severidad, Certeza certeza) {
            this.etiqueta = etiqueta;
            this.severidad = severidad;
            this.certeza = certeza;
        }
    }

    public record Hallazgo(Tipo tipo, Path archivo, String modProbable, String detalle,
                           String queHacer, long modificado) {

        public String nombreCorto() {
            return archivo.getFileName().toString();
        }
    }

    public record Reporte(List<Hallazgo> hallazgos, int archivosRevisados,
                          long duracionMs, Path carpeta) {

        public List<Hallazgo> por(Tipo t) {
            return hallazgos.stream().filter(h -> h.tipo() == t).toList();
        }

        public boolean hayAlgoGrave() {
            return hallazgos.stream().anyMatch(h -> h.tipo() == Tipo.ROTO || h.tipo() == Tipo.RECIENTE);
        }
    }

    /** Claves cuyo valor tiene una escala esperable. clave -> maximo razonable. */
    private static final Pattern ASIGNACION_NUMERICA =
            Pattern.compile("^\\s*[\"']?([A-Za-z_][A-Za-z0-9_.\\-]*)[\"']?\\s*[=:]\\s*(-?\\d+)\\s*,?\\s*$");

    /** Un config de mas de esto es raro y suele indicar un archivo que crecio solo. */
    private static final long TAMANO_SOSPECHOSO = 2 * 1024 * 1024;

    private EscanerConfigs() {
    }

    /**
     * Recorre config/ entera.
     *
     * @param carpetaJuego raiz de la instancia
     * @param momentoCrash timestamp del ultimo crash, o 0 si no hubo. Cuando hay,
     *                     se usa para marcar los archivos editados justo antes.
     */
    public static Reporte analizar(Path carpetaJuego, long momentoCrash) {
        long inicio = System.currentTimeMillis();
        Path carpeta = carpetaJuego.resolve("config");
        List<Hallazgo> hallazgos = new ArrayList<>();
        int revisados = 0;

        if (!Files.isDirectory(carpeta)) {
            return new Reporte(List.of(), 0, 0, carpeta);
        }

        List<Path> archivos = new ArrayList<>();
        try (var flujo = Files.walk(carpeta, 4)) {
            for (Path p : flujo.filter(Files::isRegularFile).toList()) {
                String nombre = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (nombre.endsWith(".toml") || nombre.endsWith(".json") || nombre.endsWith(".json5")
                        || nombre.endsWith(".properties") || nombre.endsWith(".cfg")
                        || nombre.endsWith(".conf") || nombre.endsWith(".yaml")
                        || nombre.endsWith(".yml")) {
                    archivos.add(p);
                }
            }
        } catch (Throwable t) {
            Faro.LOG.warn("[Faro] No pude recorrer config/: {}", t.toString());
        }

        for (Path archivo : archivos) {
            revisados++;
            try {
                revisar(archivo, carpeta, momentoCrash, hallazgos);
            } catch (Throwable ignored) {
                // Un archivo ilegible no debe cortar el escaneo del resto.
            }
        }

        // Lo mas grave primero, y dentro de eso lo mas reciente.
        hallazgos.sort(Comparator
                .comparingInt((Hallazgo h) -> -h.tipo().severidad.peso())
                .thenComparing(Comparator.comparingLong(Hallazgo::modificado).reversed()));

        long duracion = System.currentTimeMillis() - inicio;
        MonitorHardware.get().registrarTrabajoPropio(duracion * 1_000_000L);
        return new Reporte(hallazgos, revisados, duracion, carpeta);
    }

    private static void revisar(Path archivo, Path raiz, long momentoCrash,
                                List<Hallazgo> out) throws Exception {
        long tamano = Files.size(archivo);
        long modificado = Files.getLastModifiedTime(archivo).toMillis();
        String mod = modProbable(archivo, raiz);
        String nombre = archivo.getFileName().toString().toLowerCase(Locale.ROOT);

        if (tamano == 0) {
            out.add(new Hallazgo(Tipo.VACIO, archivo, mod,
                    "El archivo esta vacio (0 bytes).",
                    "Borralo y dejá que el mod lo regenere al arrancar. Si el mod no arranca "
                            + "por culpa de esto, borrarlo es justamente lo que lo destraba.",
                    modificado));
            return;
        }

        if (tamano > TAMANO_SOSPECHOSO) {
            out.add(new Hallazgo(Tipo.GRANDE, archivo, mod,
                    "Ocupa " + (tamano / 1024) + " KB. Un config normal pesa unos pocos KB.",
                    "Suele ser un mod que acumula datos en su config en vez de en el mundo. "
                            + "No es un error, pero alarga el arranque.",
                    modificado));
        }

        String contenido;
        try {
            contenido = Files.readString(archivo, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            // Si ni siquiera es texto UTF-8 valido, ya es un hallazgo.
            out.add(new Hallazgo(Tipo.ROTO, archivo, mod,
                    "No se puede leer como texto: " + t.getClass().getSimpleName(),
                    "El archivo esta corrupto. Borralo para que el mod lo regenere.",
                    modificado));
            return;
        }

        if (nombre.endsWith(".json") || nombre.endsWith(".json5")) {
            String error = validarJson(contenido);
            if (error != null) {
                out.add(new Hallazgo(Tipo.ROTO, archivo, mod,
                        "JSON invalido: " + error,
                        "Un config .json roto hace que el mod que lo lee falle al arrancar, "
                                + "muchas veces sin decir cual era. Arreglá el error o borrá el "
                                + "archivo para que se regenere.",
                        modificado));
            }
        } else if (nombre.endsWith(".toml")) {
            String error = validarTomlBasico(contenido);
            if (error != null) {
                out.add(new Hallazgo(Tipo.ROTO, archivo, mod,
                        "TOML sospechoso: " + error,
                        "Revisá esa linea. Forge lee los .toml al arrancar y un error ahi "
                                + "puede tumbar el juego antes de mostrar nada.",
                        modificado));
            }
        }

        // Editado en la hora previa al crash: el dato mas util de todo el escaneo.
        if (momentoCrash > 0) {
            long antes = momentoCrash - modificado;
            if (antes >= 0 && antes < 60 * 60 * 1000L) {
                out.add(new Hallazgo(Tipo.RECIENTE, archivo, mod,
                        "Se modifico " + (antes / 60000) + " minutos antes del ultimo crash.",
                        "Es el sospechoso mas directo que hay. Si lo tocaste vos, revisá el "
                                + "cambio. Si no, el mod lo reescribio justo antes de caerse.",
                        modificado));
            }
        }

        buscarValoresExtremos(archivo, mod, contenido, modificado, out);
    }

    /**
     * Busca numeros que se salen de la escala esperable de su clave.
     *
     * Es heuristica pura y se marca como MEDIA. El criterio: solo se mira si el
     * NOMBRE de la clave habla de una magnitud con escala conocida. Un
     * "maxEntities = 2000000" es sospechoso; un "seed = 8374928374" es normal.
     */
    private static void buscarValoresExtremos(Path archivo, String mod, String contenido,
                                              long modificado, List<Hallazgo> out) {
        int encontrados = 0;
        int linea = 0;
        for (String cruda : contenido.split("\\R")) {
            linea++;
            if (encontrados >= 3) {
                break; // no inundar el reporte con el mismo archivo
            }
            Matcher m = ASIGNACION_NUMERICA.matcher(cruda);
            if (!m.matches()) {
                continue;
            }
            String clave = m.group(1).toLowerCase(Locale.ROOT);
            long valor;
            try {
                valor = Long.parseLong(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }

            Long maximo = maximoRazonable(clave);
            if (maximo == null || valor <= maximo) {
                continue;
            }
            encontrados++;
            out.add(new Hallazgo(Tipo.VALOR_EXTREMO, archivo, mod,
                    "Linea " + linea + ": " + m.group(1) + " = " + valor
                            + " (lo esperable para una clave asi es hasta " + maximo + ")",
                    "Puede ser intencional y estar perfecto. Pero si el juego se come la "
                            + "memoria o se traba, un valor asi es el primer lugar donde mirar. "
                            + "Esto es una sospecha, no un error confirmado.",
                    modificado));
        }
    }

    /**
     * Escala esperable segun lo que dice el nombre de la clave.
     *
     * null = no sabemos que escala tiene esta clave, asi que no opinamos. Preferir
     * el silencio al falso positivo es la regla en todo Faro.
     */
    private static Long maximoRazonable(String clave) {
        if (clave.contains("seed") || clave.contains("uuid") || clave.contains("version")
                || clave.contains("timestamp") || clave.contains("time") || clave.contains("id")) {
            return null;
        }
        if (clave.contains("renderdistance") || clave.contains("render_distance")
                || clave.contains("viewdistance") || clave.contains("chunkdistance")) {
            return 64L;
        }
        if (clave.contains("radius") || clave.contains("radio") || clave.contains("range")
                || clave.contains("distance")) {
            return 2048L;
        }
        if (clave.contains("maxentit") || clave.contains("entitylimit")
                || clave.contains("mobcap") || clave.contains("spawnlimit")) {
            return 10000L;
        }
        if (clave.contains("memory") || clave.contains("ram") || clave.contains("heap")) {
            return 65536L;
        }
        if (clave.contains("threads") || clave.contains("hilos") || clave.contains("workers")) {
            return 64L;
        }
        if (clave.contains("count") || clave.contains("amount") || clave.contains("cantidad")
                || clave.contains("max") || clave.contains("limit")) {
            return 1_000_000L;
        }
        return null;
    }

    /** Valida JSON. Devuelve el mensaje de error, o null si esta bien. */
    private static String validarJson(String contenido) {
        try {
            com.google.gson.JsonParser.parseString(contenido);
            return null;
        } catch (Throwable t) {
            String m = t.getMessage();
            return m == null ? t.getClass().getSimpleName() : recortar(m, 120);
        }
    }

    /**
     * Chequeos basicos de TOML, sin traer un parser entero.
     *
     * Se buscan los tres errores que la gente comete al editar a mano:
     * comillas sin cerrar, corchetes de seccion mal formados, y el "=" faltante.
     * No pretende validar TOML completo — pretende atrapar lo que rompe de verdad.
     */
    private static String validarTomlBasico(String contenido) {
        int linea = 0;
        for (String cruda : contenido.split("\\R")) {
            linea++;
            String t = cruda.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.startsWith("[")) {
                if (!t.endsWith("]")) {
                    return "linea " + linea + ": seccion sin cerrar -> " + recortar(t, 60);
                }
                continue;
            }
            // Comillas dobles impares fuera de comentario.
            String sinComentario = t.split("#", 2)[0];
            long comillas = sinComentario.chars().filter(c -> c == '"').count();
            if (comillas % 2 != 0) {
                return "linea " + linea + ": comillas sin cerrar -> " + recortar(t, 60);
            }
            if (!sinComentario.contains("=") && !sinComentario.trim().isEmpty()
                    && !sinComentario.trim().startsWith("]")
                    && !sinComentario.trim().endsWith(",")
                    && !sinComentario.trim().startsWith("\"")) {
                return "linea " + linea + ": no parece una asignacion -> " + recortar(t, 60);
            }
        }
        return null;
    }

    /** Deduce a que mod pertenece un config por su ruta. */
    private static String modProbable(Path archivo, Path raiz) {
        Path rel = raiz.relativize(archivo);
        String primero = rel.getNameCount() > 1
                ? rel.getName(0).toString()
                : rel.getFileName().toString();
        // 'create-client.toml' -> 'create';  'ftbquests/xxx.snbt' -> 'ftbquests'
        String limpio = primero.replaceAll("(?i)[-_](client|server|common|config)?\\.[a-z0-9]+$", "");
        limpio = limpio.replaceAll("\\.[a-z0-9]+$", "");
        return limpio.isBlank() ? "?" : limpio;
    }

    private static String recortar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public static String veredicto(Reporte r) {
        if (r.archivosRevisados() == 0) {
            return "No encontre la carpeta config o esta vacia.";
        }
        if (r.hallazgos().isEmpty()) {
            return r.archivosRevisados() + " archivos revisados, ninguno con problemas. "
                    + "Si el crash no tiene causa clara, no viene de un config roto.";
        }
        List<Hallazgo> rotos = r.por(Tipo.ROTO);
        if (!rotos.isEmpty()) {
            return rotos.size() + " archivo(s) de configuracion con formato invalido. "
                    + "Esto es causa suficiente para que un mod no arranque, y explica los "
                    + "fallos que no dejan stacktrace util.";
        }
        List<Hallazgo> recientes = r.por(Tipo.RECIENTE);
        if (!recientes.isEmpty()) {
            return recientes.size() + " config(s) editados justo antes del ultimo crash. "
                    + "Empeza por ahi: es la pista mas fuerte cuando no hay culpable en el stack.";
        }
        return r.hallazgos().size() + " cosas para revisar en " + r.archivosRevisados()
                + " archivos. Ninguna es concluyente por si sola.";
    }
}
