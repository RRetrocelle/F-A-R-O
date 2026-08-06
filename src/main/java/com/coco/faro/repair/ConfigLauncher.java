package com.coco.faro.repair;

import com.coco.faro.Faro;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Lee y modifica la memoria asignada en el archivo de instancias del launcher.
 *
 * El formato NO se asumio de memoria: se inspecciono el archivo real. En este
 * launcher la estructura es
 *
 *   instances.json -> { "instances": [ { "id": ..., "directory": ...,
 *                                        "memoryMax": 7680 }, ... ] }
 *
 * y la memoria esta en MB. Un hallazgo importante de esa inspeccion: NO hay
 * clave de argumentos de JVM por instancia, asi que lo unico que se puede
 * cambiar desde aca es la memoria. Los flags hay que ponerlos a mano donde el
 * launcher los acepte.
 *
 * Reglas de seguridad, iguales al resto de Faro:
 *   - se hace copia del archivo original ANTES de tocarlo;
 *   - se muestra el cambio exacto y se espera confirmacion;
 *   - la instancia se identifica por su carpeta, no por el nombre, para no
 *     editar la instancia equivocada si hay varias parecidas.
 */
public final class ConfigLauncher {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Lo que se sabe del archivo del launcher. */
    public record Estado(boolean encontrado, Path archivo, String nombreInstancia,
                         long memoriaActualMB, String detalle) {
    }

    private ConfigLauncher() {
    }

    /**
     * Busca el instances.json que contiene la instancia en la que corremos.
     *
     * Se sube desde la carpeta del juego: mods/ -> instancia -> instances/ ->
     * raiz del launcher. Es la unica forma de encontrarlo sin hardcodear rutas
     * de un launcher concreto.
     */
    public static Estado detectar(Path carpetaJuego) {
        try {
            Path instancias = carpetaJuego.getParent();               // .../instances
            Path raiz = instancias == null ? null : instancias.getParent();
            if (raiz == null) {
                return new Estado(false, null, "", -1, "No pude ubicar la carpeta del launcher.");
            }
            Path archivo = raiz.resolve("instances.json");
            if (!Files.isRegularFile(archivo)) {
                return new Estado(false, null, "", -1,
                        "Este launcher no usa instances.json. Cambiá la memoria desde su interfaz.");
            }

            JsonObject raizJson = leer(archivo);
            JsonObject inst = buscarInstancia(raizJson, carpetaJuego);
            if (inst == null) {
                return new Estado(false, archivo, "", -1,
                        "Encontre el archivo pero no la instancia actual dentro.");
            }
            long mem = inst.has("memoryMax") ? inst.get("memoryMax").getAsLong() : -1;
            String nombre = inst.has("name") ? inst.get("name").getAsString() : "?";
            return new Estado(true, archivo, nombre, mem, "");

        } catch (Throwable t) {
            return new Estado(false, null, "", -1, "No pude leer la config: " + t.getMessage());
        }
    }

    /**
     * Cambia la memoria asignada. Devuelve un mensaje para mostrar en pantalla.
     *
     * Hace backup con marca de tiempo antes de escribir, y conserva el formato
     * indentado para que el archivo siga siendo legible a mano.
     */
    public static String aplicarMemoria(Path carpetaJuego, long nuevaMB,
                                        RegistroAcciones registro) {
        Estado e = detectar(carpetaJuego);
        if (!e.encontrado()) {
            return "No se pudo: " + e.detalle();
        }
        try {
            Path respaldo = e.archivo().resolveSibling(
                    "instances.json.faro-" + LocalDateTime.now().format(FMT) + ".bak");
            Files.copy(e.archivo(), respaldo, StandardCopyOption.REPLACE_EXISTING);

            JsonObject raiz = leer(e.archivo());
            JsonObject inst = buscarInstancia(raiz, carpetaJuego);
            if (inst == null) {
                return "No se pudo: la instancia desaparecio del archivo.";
            }
            long antes = inst.has("memoryMax") ? inst.get("memoryMax").getAsLong() : -1;
            inst.addProperty("memoryMax", nuevaMB);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(e.archivo(), gson.toJson(raiz), StandardCharsets.UTF_8);

            registro.anotar("LAUNCHER  memoryMax " + antes + " -> " + nuevaMB
                    + " MB en " + e.archivo());
            registro.anotar("          copia previa en " + respaldo.getFileName());

            return "Listo: " + antes + " MB -> " + nuevaMB + " MB.\n"
                    + "Copia del archivo original: " + respaldo.getFileName() + "\n"
                    + "Cerrá el juego y el launcher, y volvé a abrirlos para que tome efecto.";

        } catch (Throwable t) {
            Faro.LOG.error("[Faro] Fallo al escribir la config del launcher", t);
            return "No se pudo escribir: " + t.getMessage();
        }
    }

    /** Texto del cambio propuesto, para mostrarlo antes de confirmar. */
    public static String diff(Estado e, long nuevaMB) {
        if (!e.encontrado()) {
            return e.detalle();
        }
        return "Archivo:  " + e.archivo().getFileName() + "\n"
                + "Instancia: " + e.nombreInstancia() + "\n\n"
                + "- \"memoryMax\": " + e.memoriaActualMB() + "\n"
                + "+ \"memoryMax\": " + nuevaMB + "\n\n"
                + "Es el unico valor que cambia. Se guarda una copia del archivo antes de tocarlo.";
    }

    // ------------------------------------------------------------ auxiliares

    private static JsonObject leer(Path archivo) throws Exception {
        String texto = Files.readString(archivo, StandardCharsets.UTF_8);
        return JsonParser.parseString(texto).getAsJsonObject();
    }

    /** Identifica la instancia por su carpeta: el nombre puede repetirse. */
    private static JsonObject buscarInstancia(JsonObject raiz, Path carpetaJuego) {
        if (raiz == null || !raiz.has("instances")) {
            return null;
        }
        String buscada = carpetaJuego.toAbsolutePath().normalize().toString()
                .replace('/', '\\').toLowerCase();

        JsonArray arr = raiz.getAsJsonArray("instances");
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (!o.has("directory")) {
                continue;
            }
            String dir = o.get("directory").getAsString()
                    .replace('/', '\\').toLowerCase();
            if (dir.equals(buscada)) {
                return o;
            }
        }
        return null;
    }
}
