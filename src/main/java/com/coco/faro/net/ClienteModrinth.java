package com.coco.faro.net;

import com.coco.faro.Faro;
import com.coco.faro.diag.RangoVersion;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Cliente minimo de la API publica de Modrinth, solo para lo que Faro necesita:
 * encontrar el .jar exacto de una dependencia que falta.
 *
 * Criterio de privacidad que se aplica en todo el archivo: lo unico que sale de
 * la PC es el modId que se esta buscando. Nunca la lista de mods instalados, ni
 * rutas, ni nada del sistema. Y ninguna llamada se hace sola: siempre la dispara
 * un click del usuario.
 */
public final class ClienteModrinth {

    private static final String BASE = "https://api.modrinth.com/v2";
    private static final String UA = "faro-modpack-companion/0.3.0 (diagnostico local)";
    private static final int TIMEOUT_MS = 15000;

    /** Un .jar concreto listo para descargar, con todo lo que hay que mostrarle al usuario. */
    public record Candidato(
            String modId,
            String tituloProyecto,
            String slug,
            String autor,
            String versionNumero,
            String tipoVersion,
            String nombreArchivo,
            String url,
            String sha1,
            long tamanoBytes,
            long descargasProyecto,
            boolean cumpleRango) {

        public String tamanoLegible() {
            if (tamanoBytes < 1024) {
                return tamanoBytes + " B";
            }
            if (tamanoBytes < 1024 * 1024) {
                return String.format(Locale.ROOT, "%.0f KB", tamanoBytes / 1024.0);
            }
            return String.format(Locale.ROOT, "%.1f MB", tamanoBytes / (1024.0 * 1024.0));
        }

        public String fuente() {
            return "modrinth.com/mod/" + slug;
        }
    }

    /** Identificacion exacta de un .jar por su hash. */
    public record PorHash(String idProyecto, String idVersion, String versionNumero,
                          String nombreArchivo) {
    }

    private ClienteModrinth() {
    }

    /**
     * Identifica un .jar por su SHA-1. Es el camino sin ambiguedad.
     *
     * Buscar por nombre siempre puede equivocarse: hay mods homonimos, slugs que
     * no coinciden con el modId, y forks. El hash identifica ESE archivo exacto,
     * o no lo identifica. No hay punto medio, y por eso este es el metodo que usa
     * el actualizador.
     *
     * Lo unico que sale de la PC es el hash. Un SHA-1 no permite reconstruir nada
     * del archivo ni dice nada del sistema.
     *
     * @return null si Modrinth no conoce ese archivo, que es un resultado valido
     */
    public static PorHash identificarPorHash(String sha1) {
        if (sha1 == null || sha1.isBlank()) {
            return null;
        }
        JsonObject version = getObjeto(BASE + "/version_file/" + enc(sha1.toLowerCase(Locale.ROOT))
                + "?algorithm=sha1");
        if (version == null) {
            return null;
        }
        String idProyecto = texto(version, "project_id");
        if (idProyecto.isEmpty()) {
            return null;
        }
        String archivo = "";
        if (version.has("files") && version.get("files").isJsonArray()) {
            JsonObject f = archivoPrincipal(version);
            if (f != null) {
                archivo = texto(f, "filename");
            }
        }
        return new PorHash(idProyecto, texto(version, "id"),
                texto(version, "version_number"), archivo);
    }

    /**
     * La version mas nueva de un proyecto para 1.20.1 Forge.
     *
     * Se prefiere una 'release' sobre beta o alpha: al actualizar un pack que
     * funciona, ofrecer una alpha por ser mas nueva es un mal negocio. Si solo
     * hay pre-releases, se devuelve la primera igual y el tipo queda visible en
     * la pantalla de confirmacion.
     */
    public static Optional<Candidato> ultimaVersionDeProyecto(String idProyecto, String modId) {
        if (idProyecto == null || idProyecto.isBlank()) {
            return Optional.empty();
        }
        JsonObject proyecto = getObjeto(BASE + "/project/" + enc(idProyecto));
        if (proyecto == null) {
            return Optional.empty();
        }
        String slug = texto(proyecto, "slug");
        String titulo = texto(proyecto, "title");
        long descargas = numero(proyecto, "downloads");

        JsonArray versiones = getArreglo(BASE + "/project/" + enc(idProyecto)
                + "/version?loaders=" + enc("[\"forge\"]")
                + "&game_versions=" + enc("[\"1.20.1\"]"));
        if (versiones == null || versiones.isEmpty()) {
            return Optional.empty();
        }

        // La API las devuelve de mas nueva a mas vieja.
        JsonObject estable = null;
        JsonObject cualquiera = null;
        for (JsonElement el : versiones) {
            JsonObject v = el.getAsJsonObject();
            if (cualquiera == null) {
                cualquiera = v;
            }
            if (estable == null && "release".equals(texto(v, "version_type"))) {
                estable = v;
            }
        }
        JsonObject elegida = estable != null ? estable : cualquiera;
        if (elegida == null) {
            return Optional.empty();
        }
        JsonObject archivo = archivoPrincipal(elegida);
        if (archivo == null) {
            return Optional.empty();
        }
        String sha1 = "";
        if (archivo.has("hashes") && archivo.get("hashes").isJsonObject()) {
            sha1 = texto(archivo.getAsJsonObject("hashes"), "sha1");
        }
        return Optional.of(new Candidato(
                modId, titulo, slug, autorDe(proyecto),
                texto(elegida, "version_number"), texto(elegida, "version_type"),
                texto(archivo, "filename"), texto(archivo, "url"), sha1,
                numero(archivo, "size"), descargas, true));
    }

    /**
     * Busca el jar de un modId para 1.20.1 Forge que cumpla el rango pedido.
     *
     * Estrategia en dos pasos, de mas fiable a menos:
     *   1. Pedir el proyecto por slug directo — en Modrinth el slug suele ser el
     *      modId ("expandability" -> modrinth.com/mod/expandability).
     *   2. Si no existe, buscar por texto y quedarse solo con proyectos cuyo slug
     *      o titulo normalizado coincida con el modId.
     *
     * El segundo paso es deliberadamente estricto: preferimos no encontrar nada
     * antes que ofrecerle al usuario descargar un mod parecido pero equivocado.
     */
    public static Optional<Candidato> buscar(String modId, RangoVersion rango) {
        if (modId == null || modId.isBlank()) {
            return Optional.empty();
        }
        String id = modId.trim().toLowerCase(Locale.ROOT);

        Optional<Candidato> directo = porSlug(id, id, rango);
        if (directo.isPresent()) {
            return directo;
        }
        // Modrinth usa guiones donde los modId usan guion bajo, y viceversa.
        if (id.contains("_")) {
            Optional<Candidato> guion = porSlug(id.replace('_', '-'), id, rango);
            if (guion.isPresent()) {
                return guion;
            }
        }
        return porBusqueda(id, rango);
    }

    private static Optional<Candidato> porSlug(String slug, String modId, RangoVersion rango) {
        JsonObject proyecto = getObjeto(BASE + "/project/" + enc(slug));
        if (proyecto == null) {
            return Optional.empty();
        }
        return versionDe(proyecto, modId, rango);
    }

    private static Optional<Candidato> porBusqueda(String modId, RangoVersion rango) {
        String facets = "[[\"categories:forge\"],[\"versions:1.20.1\"]]";
        String url = BASE + "/search?query=" + enc(modId) + "&facets=" + enc(facets) + "&limit=8";
        JsonObject raiz = getObjeto(url);
        if (raiz == null || !raiz.has("hits")) {
            return Optional.empty();
        }
        for (JsonElement el : raiz.getAsJsonArray("hits")) {
            JsonObject hit = el.getAsJsonObject();
            String slug = texto(hit, "slug");
            String titulo = texto(hit, "title");
            // Estricto a proposito: solo aceptamos coincidencia exacta normalizada.
            if (!normalizar(slug).equals(normalizar(modId))
                    && !normalizar(titulo).equals(normalizar(modId))) {
                continue;
            }
            JsonObject proyecto = getObjeto(BASE + "/project/" + enc(slug));
            if (proyecto != null) {
                Optional<Candidato> c = versionDe(proyecto, modId, rango);
                if (c.isPresent()) {
                    return c;
                }
            }
        }
        return Optional.empty();
    }

    /** De un proyecto, elige la mejor version 1.20.1+forge que cumpla el rango. */
    private static Optional<Candidato> versionDe(JsonObject proyecto, String modId, RangoVersion rango) {
        String slug = texto(proyecto, "slug");
        String titulo = texto(proyecto, "title");
        long descargas = numero(proyecto, "downloads");

        String urlVersiones = BASE + "/project/" + enc(slug)
                + "/version?loaders=" + enc("[\"forge\"]")
                + "&game_versions=" + enc("[\"1.20.1\"]");
        JsonArray versiones = getArreglo(urlVersiones);
        if (versiones == null || versiones.isEmpty()) {
            return Optional.empty();
        }

        JsonObject mejorEnRango = null;
        JsonObject mejorSuelta = null;

        for (JsonElement el : versiones) {
            JsonObject v = el.getAsJsonObject();
            String num = texto(v, "version_number");
            boolean estable = "release".equals(texto(v, "version_type"));

            if (mejorSuelta == null || (estable && !"release".equals(texto(mejorSuelta, "version_type")))) {
                mejorSuelta = v;
            }
            if (rango != null && rango.acepta(limpiarVersion(num))) {
                if (mejorEnRango == null) {
                    mejorEnRango = v;
                }
            }
        }

        JsonObject elegida = mejorEnRango != null ? mejorEnRango : mejorSuelta;
        if (elegida == null) {
            return Optional.empty();
        }

        JsonObject archivo = archivoPrincipal(elegida);
        if (archivo == null) {
            return Optional.empty();
        }

        String sha1 = "";
        if (archivo.has("hashes") && archivo.get("hashes").isJsonObject()) {
            sha1 = texto(archivo.getAsJsonObject("hashes"), "sha1");
        }

        return Optional.of(new Candidato(
                modId,
                titulo,
                slug,
                autorDe(proyecto),
                texto(elegida, "version_number"),
                texto(elegida, "version_type"),
                texto(archivo, "filename"),
                texto(archivo, "url"),
                sha1,
                numero(archivo, "size"),
                descargas,
                mejorEnRango != null));
    }

    private static JsonObject archivoPrincipal(JsonObject version) {
        if (!version.has("files")) {
            return null;
        }
        JsonArray archivos = version.getAsJsonArray("files");
        for (JsonElement el : archivos) {
            JsonObject f = el.getAsJsonObject();
            if (f.has("primary") && f.get("primary").getAsBoolean()) {
                return f;
            }
        }
        return archivos.isEmpty() ? null : archivos.get(0).getAsJsonObject();
    }

    private static String autorDe(JsonObject proyecto) {
        // La API v2 no siempre trae el autor en el proyecto; no es critico.
        String team = texto(proyecto, "team");
        return team.isEmpty() ? "—" : team;
    }

    /** Quita prefijos tipo "forge-1.20.1-" o "mc1.20.1-" que ensucian la comparacion. */
    private static String limpiarVersion(String v) {
        if (v == null) {
            return "";
        }
        String s = v.trim();
        s = s.replaceAll("(?i)^(forge|fabric|neoforge)[-_]", "");
        s = s.replaceAll("(?i)^mc?1\\.20(\\.1)?[-_+]", "");
        s = s.replaceAll("(?i)[-_+](forge|fabric)([-_+].*)?$", "");
        s = s.replaceAll("(?i)[-_+]mc?1\\.20(\\.1)?$", "");
        return s;
    }

    // ------------------------------------------------------------------ HTTP

    private static JsonObject getObjeto(String url) {
        JsonElement el = get(url);
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
    }

    private static JsonArray getArreglo(String url) {
        JsonElement el = get(url);
        return (el != null && el.isJsonArray()) ? el.getAsJsonArray() : null;
    }

    private static JsonElement get(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", UA);
            con.setRequestProperty("Accept", "application/json");
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);

            int codigo = con.getResponseCode();
            if (codigo != 200) {
                return null;
            }
            try (InputStream in = con.getInputStream();
                 InputStreamReader rd = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(rd);
            }
        } catch (Throwable t) {
            Faro.LOG.warn("[Faro] Consulta a Modrinth fallida: {}", t.toString());
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    // ------------------------------------------------------------ utilidades

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String texto(JsonObject o, String clave) {
        if (o == null || !o.has(clave) || o.get(clave).isJsonNull()) {
            return "";
        }
        JsonElement el = o.get(clave);
        return el.isJsonPrimitive() ? el.getAsString() : "";
    }

    private static long numero(JsonObject o, String clave) {
        try {
            return (o != null && o.has(clave) && !o.get(clave).isJsonNull())
                    ? o.get(clave).getAsLong() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String normalizar(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
                .replace("_", "").replace("-", "").replace(" ", "");
    }

    /** Lista de nombres alternativos probados, util para explicar por que no se encontro. */
    public static List<String> variantesProbadas(String modId) {
        List<String> v = new ArrayList<>();
        String id = modId.toLowerCase(Locale.ROOT);
        v.add(id);
        if (id.contains("_")) {
            v.add(id.replace('_', '-'));
        }
        v.add("busqueda por texto");
        return v;
    }
}
