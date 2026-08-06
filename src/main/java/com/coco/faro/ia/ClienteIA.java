package com.coco.faro.ia;

import com.coco.faro.Faro;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Consulta opcional a un modelo de lenguaje, SOLO para crashes que la heuristica
 * no pudo explicar.
 *
 * Limites que se cumplen siempre:
 *   - Nunca se llama sola. La dispara un click del usuario.
 *   - Se manda unicamente el bloque de error, ya recortado y limpiado. Nunca la
 *     lista de mods, ni rutas, ni nada del sistema.
 *   - La respuesta es una HIPOTESIS y se muestra etiquetada como tal.
 *   - No puede instalar, mover ni borrar nada. Si sugiere una accion, esa accion
 *     pasa por el mismo boton con confirmacion manual que todo lo demas.
 */
public final class ClienteIA {

    private static final int TIMEOUT_MS = 45000;
    private static final int MAX_CARACTERES = 6000;

    /** Rutas absolutas de Windows, Linux y macOS: contienen el nombre de usuario. */
    private static final Pattern RUTA_WINDOWS = Pattern.compile("[A-Za-z]:\\\\[^\\s\"']+");
    private static final Pattern RUTA_UNIX = Pattern.compile("/(?:home|Users)/[^\\s\"']+");

    private static final String INSTRUCCIONES = """
            Sos un asistente que explica errores de Minecraft con Forge 1.20.1 a alguien \
            que juega pero no programa.

            Te paso un bloque de error. Responde en castellano rioplatense, en 4 lineas o menos:
            1. Que paso, en una oracion simple.
            2. Que mod parece ser el responsable, si se puede saber por el texto.
            3. Que conviene probar.

            Si el texto no alcanza para saberlo, deci exactamente eso. No inventes nombres de \
            mods ni causas. Es preferible "no se puede determinar" antes que una respuesta \
            que suene segura y este mal.
            """;

    public record Respuesta(boolean exito, String texto, String error) {
    }

    private ClienteIA() {
    }

    /**
     * Quita del texto todo lo que no haga falta para el diagnostico.
     *
     * Las rutas absolutas son el dato mas sensible que aparece en un stacktrace:
     * incluyen el nombre de usuario de Windows. Se reemplazan por un marcador
     * antes de que el texto salga de la maquina.
     */
    public static String sanitizar(String texto) {
        if (texto == null) {
            return "";
        }
        String limpio = RUTA_WINDOWS.matcher(texto).replaceAll("<ruta-local>");
        limpio = RUTA_UNIX.matcher(limpio).replaceAll("<ruta-local>");

        if (limpio.length() > MAX_CARACTERES) {
            limpio = limpio.substring(0, MAX_CARACTERES) + "\n...(recortado)";
        }
        return limpio;
    }

    /** Lo que se va a enviar, para poder mostrarselo al usuario ANTES de mandarlo. */
    public static String vistaPrevia(String bloqueError) {
        return sanitizar(bloqueError);
    }

    public static Respuesta consultar(ConfigIA config, String bloqueError) {
        if (config == null || !config.habilitada()) {
            return new Respuesta(false, null, "No hay una API key configurada.");
        }
        String contenido = sanitizar(bloqueError);
        if (contenido.isBlank()) {
            return new Respuesta(false, null, "No hay texto de error para analizar.");
        }

        try {
            Respuesta r = switch (config.proveedor()) {
                case ANTHROPIC -> anthropic(config, contenido);
                case OPENAI -> openai(config, contenido);
            };
            if (r.exito()) {
                config.contarConsulta();
            }
            return r;
        } catch (Throwable t) {
            Faro.LOG.warn("[Faro] Consulta de IA fallida: {}", t.toString());
            return new Respuesta(false, null, "Fallo la consulta: " + t.getMessage());
        }
    }

    private static Respuesta anthropic(ConfigIA config, String contenido) throws Exception {
        JsonObject mensaje = new JsonObject();
        mensaje.addProperty("role", "user");
        mensaje.addProperty("content", INSTRUCCIONES + "\n\n--- BLOQUE DE ERROR ---\n" + contenido);

        JsonArray mensajes = new JsonArray();
        mensajes.add(mensaje);

        JsonObject cuerpo = new JsonObject();
        cuerpo.addProperty("model", config.modelo());
        cuerpo.addProperty("max_tokens", 700);
        cuerpo.add("messages", mensajes);

        JsonObject respuesta = postJson("https://api.anthropic.com/v1/messages", cuerpo, con -> {
            con.setRequestProperty("x-api-key", config.apiKey());
            con.setRequestProperty("anthropic-version", "2023-06-01");
        });
        if (respuesta == null) {
            return new Respuesta(false, null, "El servidor no respondio correctamente.");
        }
        if (respuesta.has("content") && respuesta.get("content").isJsonArray()) {
            JsonArray partes = respuesta.getAsJsonArray("content");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < partes.size(); i++) {
                JsonObject p = partes.get(i).getAsJsonObject();
                if (p.has("text")) {
                    sb.append(p.get("text").getAsString());
                }
            }
            return new Respuesta(true, sb.toString().trim(), null);
        }
        return new Respuesta(false, null, mensajeDeError(respuesta));
    }

    private static Respuesta openai(ConfigIA config, String contenido) throws Exception {
        JsonArray mensajes = new JsonArray();

        JsonObject sistema = new JsonObject();
        sistema.addProperty("role", "system");
        sistema.addProperty("content", INSTRUCCIONES);
        mensajes.add(sistema);

        JsonObject usuario = new JsonObject();
        usuario.addProperty("role", "user");
        usuario.addProperty("content", "--- BLOQUE DE ERROR ---\n" + contenido);
        mensajes.add(usuario);

        JsonObject cuerpo = new JsonObject();
        cuerpo.addProperty("model", config.modelo());
        cuerpo.addProperty("max_tokens", 700);
        cuerpo.add("messages", mensajes);

        JsonObject respuesta = postJson("https://api.openai.com/v1/chat/completions", cuerpo,
                con -> con.setRequestProperty("Authorization", "Bearer " + config.apiKey()));
        if (respuesta == null) {
            return new Respuesta(false, null, "El servidor no respondio correctamente.");
        }
        if (respuesta.has("choices")) {
            JsonArray choices = respuesta.getAsJsonArray("choices");
            if (!choices.isEmpty()) {
                JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (msg != null && msg.has("content")) {
                    return new Respuesta(true, msg.get("content").getAsString().trim(), null);
                }
            }
        }
        return new Respuesta(false, null, mensajeDeError(respuesta));
    }

    private interface Cabeceras {
        void aplicar(HttpURLConnection con);
    }

    private static JsonObject postJson(String url, JsonObject cuerpo, Cabeceras extra)
            throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("User-Agent", "faro-modpack-companion/0.3.0");
            extra.aplicar(con);
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setDoOutput(true);

            byte[] datos = cuerpo.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = con.getOutputStream()) {
                out.write(datos);
            }

            int codigo = con.getResponseCode();
            InputStream in = (codigo >= 200 && codigo < 300)
                    ? con.getInputStream() : con.getErrorStream();
            if (in == null) {
                return null;
            }
            try (InputStreamReader rd = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(rd).getAsJsonObject();
            }
        } finally {
            con.disconnect();
        }
    }

    private static String mensajeDeError(JsonObject respuesta) {
        if (respuesta.has("error")) {
            JsonObject e = respuesta.getAsJsonObject("error");
            if (e.has("message")) {
                return e.get("message").getAsString();
            }
        }
        return "Respuesta inesperada del servidor.";
    }
}
