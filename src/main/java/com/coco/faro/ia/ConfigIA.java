package com.coco.faro.ia;

import com.coco.faro.Faro;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuracion de la capa de IA. Vive en faro/ia-config.json, aparte del config
 * normal del mod.
 *
 * Esta separada a proposito: los archivos de config/ se comparten y se suben a
 * repos de modpacks sin pensarlo. Una API key no tiene que viajar en ese paquete.
 * Ademas el archivo lleva un aviso adentro recordandolo.
 *
 * La key nunca esta en el codigo. Si el usuario no carga ninguna, toda la capa de
 * IA queda apagada y Faro funciona exactamente igual que sin ella.
 */
public final class ConfigIA {

    public enum Proveedor {
        ANTHROPIC("Anthropic (Claude)", "claude-sonnet-5"),
        OPENAI("OpenAI", "gpt-4o-mini");

        public final String etiqueta;
        public final String modeloPorDefecto;

        Proveedor(String etiqueta, String modeloPorDefecto) {
            this.etiqueta = etiqueta;
            this.modeloPorDefecto = modeloPorDefecto;
        }
    }

    private static ConfigIA instancia;

    private final Path archivo;
    private String apiKey = "";
    private Proveedor proveedor = Proveedor.ANTHROPIC;
    private String modelo = Proveedor.ANTHROPIC.modeloPorDefecto;
    private int consultasHechas = 0;

    private ConfigIA(Path carpetaFaro) {
        this.archivo = carpetaFaro.resolve("ia-config.json");
        cargar();
    }

    public static synchronized ConfigIA get(Path carpetaFaro) {
        if (instancia == null) {
            instancia = new ConfigIA(carpetaFaro);
        }
        return instancia;
    }

    public static ConfigIA getSiExiste() {
        return instancia;
    }

    private void cargar() {
        try {
            if (!Files.isRegularFile(archivo)) {
                return;
            }
            String texto = Files.readString(archivo, StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(texto).getAsJsonObject();
            if (o.has("apiKey")) {
                apiKey = o.get("apiKey").getAsString();
            }
            if (o.has("proveedor")) {
                try {
                    proveedor = Proveedor.valueOf(o.get("proveedor").getAsString());
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (o.has("modelo")) {
                modelo = o.get("modelo").getAsString();
            }
            if (o.has("consultasHechas")) {
                consultasHechas = o.get("consultasHechas").getAsInt();
            }
        } catch (Throwable t) {
            Faro.LOG.warn("[Faro] No pude leer ia-config.json: {}", t.toString());
        }
    }

    public synchronized void guardar() {
        try {
            Files.createDirectories(archivo.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("_aviso", "Este archivo contiene tu API key. No lo compartas ni lo "
                    + "subas a ningun repositorio junto con el modpack.");
            o.addProperty("apiKey", apiKey);
            o.addProperty("proveedor", proveedor.name());
            o.addProperty("modelo", modelo);
            o.addProperty("consultasHechas", consultasHechas);
            Files.writeString(archivo, o.toString(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Faro.LOG.error("[Faro] No pude guardar ia-config.json", t);
        }
    }

    /** true si hay una key cargada. Sin esto, toda la capa de IA esta apagada. */
    public boolean habilitada() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** La key nunca se muestra entera en pantalla. */
    public String keyEnmascarada() {
        if (!habilitada()) {
            return "(sin configurar)";
        }
        int n = apiKey.length();
        if (n <= 10) {
            return "*".repeat(n);
        }
        return apiKey.substring(0, 6) + "..." + apiKey.substring(n - 4);
    }

    public String apiKey() {
        return apiKey;
    }

    public void apiKey(String v) {
        this.apiKey = v == null ? "" : v.trim();
    }

    public Proveedor proveedor() {
        return proveedor;
    }

    public void proveedor(Proveedor p) {
        this.proveedor = p;
        this.modelo = p.modeloPorDefecto;
    }

    public String modelo() {
        return modelo;
    }

    public void modelo(String m) {
        this.modelo = (m == null || m.isBlank()) ? proveedor.modeloPorDefecto : m.trim();
    }

    public int consultasHechas() {
        return consultasHechas;
    }

    public synchronized void contarConsulta() {
        consultasHechas++;
        guardar();
    }

    public Path archivo() {
        return archivo;
    }
}
