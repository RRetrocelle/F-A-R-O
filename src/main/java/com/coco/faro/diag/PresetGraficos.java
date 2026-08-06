package com.coco.faro.diag;

import com.coco.faro.Faro;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Auto-configurador grafico: un preset elegido a partir del hardware real.
 *
 * Lo que hace distinto a los "optimizadores" de siempre: no aplica una receta
 * generica. Lee lo que hay —nucleos, RAM fisica, RAM asignada, GPU y VRAM si se
 * pueden leer, y el rendimiento MEDIDO en esta sesion— y de ahi sale el preset.
 * Si un dato no se puede leer, se dice y el preset baja de ambicion en vez de
 * asumir lo mejor.
 *
 * Sobre Embeddium: es el motor de renderizado que reemplaza al de vanilla. Sus
 * opciones viven en {@code config/embeddium-options.json}. Faro escribe ahi solo
 * las claves que entiende, respetando el resto del archivo, con respaldo previo
 * y mostrando el diff antes de tocar nada.
 *
 * Regla que no se rompe: primero se muestra QUE cambia, con el valor viejo y el
 * nuevo, y recien despues se escribe. Nada se aplica solo.
 */
public final class PresetGraficos {

    public enum Nivel {
        MINIMO("Minimo", "Equipo justo. Prioriza que no se trabe por encima de como se ve."),
        EQUILIBRADO("Equilibrado", "El punto donde se ve bien y rinde bien en la mayoria de los equipos."),
        CALIDAD("Calidad", "Hay margen de sobra: se prioriza la distancia y el detalle.");

        public final String etiqueta;
        public final String descripcion;

        Nivel(String etiqueta, String descripcion) {
            this.etiqueta = etiqueta;
            this.descripcion = descripcion;
        }
    }

    /** Un ajuste concreto que el preset propone. */
    public record Ajuste(String archivo, String clave, String valorActual, String valorNuevo,
                         String porQue) {

        public boolean cambia() {
            return !valorNuevo.equals(valorActual);
        }
    }

    /** El preset completo, con el razonamiento a la vista. */
    public record Preset(Nivel nivel, List<Ajuste> ajustes, List<String> razones,
                         List<String> datosFaltantes, boolean hayEmbeddium) {

        public List<Ajuste> cambios() {
            return ajustes.stream().filter(Ajuste::cambia).toList();
        }
    }

    private PresetGraficos() {
    }

    /**
     * Calcula el preset a partir del hardware y de lo medido.
     *
     * El puntaje arranca en 0 y suma o resta por cada senal. Se hace asi, y no
     * con una tabla de casos, porque las senales son independientes: 16 GB de RAM
     * con una GPU integrada no es lo mismo que 8 GB con una dedicada, y una tabla
     * necesitaria una fila por combinacion.
     */
    public static Preset calcular() {
        MonitorHardware hw = MonitorHardware.get();
        List<String> razones = new ArrayList<>();
        List<String> faltantes = new ArrayList<>();
        int puntaje = 0;

        int nucleos = hw.nucleos();
        if (nucleos >= 12) {
            puntaje += 2;
            razones.add(nucleos + " nucleos: sobra procesador.");
        } else if (nucleos >= 6) {
            puntaje += 1;
            razones.add(nucleos + " nucleos: alcanza bien.");
        } else {
            puntaje -= 1;
            razones.add(nucleos + " nucleos: es poco para un pack grande. "
                    + "Bajar la distancia de render ayuda mas que cualquier otra cosa.");
        }

        long ramFisica = hw.memoriaFisicaTotalMB();
        if (ramFisica <= 0) {
            faltantes.add("RAM fisica del equipo");
        } else if (ramFisica >= 16384) {
            puntaje += 2;
            razones.add((ramFisica / 1024) + " GB de RAM fisica: comodo.");
        } else if (ramFisica >= 8192) {
            razones.add((ramFisica / 1024) + " GB de RAM fisica: justo pero viable.");
        } else {
            puntaje -= 2;
            razones.add((ramFisica / 1024) + " GB de RAM fisica: es el limitante principal.");
        }

        long asignada = MonitorRendimiento.memoriaMaximaMB();
        if (asignada < 4096) {
            puntaje -= 1;
            razones.add(asignada + " MB asignados al juego: poco para este pack. "
                    + "Mira el asistente de RAM en la pestaña Rendimiento.");
        }

        MonitorHardware.LecturaGpu gpu = hw.gpu();
        if (!gpu.hayDato()) {
            faltantes.add("uso y memoria de la placa de video (solo se puede leer en NVIDIA)");
            razones.add("Sin dato de GPU, el preset se queda del lado conservador.");
        } else {
            if (gpu.memoriaTotalMB() >= 6000) {
                puntaje += 2;
                razones.add(gpu.nombre() + " con " + gpu.memoriaTotalMB() + " MB de VRAM: sobra.");
            } else if (gpu.memoriaTotalMB() >= 3000) {
                puntaje += 1;
                razones.add(gpu.nombre() + " con " + gpu.memoriaTotalMB() + " MB de VRAM: alcanza.");
            } else if (gpu.memoriaTotalMB() > 0) {
                puntaje -= 2;
                razones.add(gpu.nombre() + " con solo " + gpu.memoriaTotalMB()
                        + " MB de VRAM: hay que cuidar texturas y distancia.");
            }
        }

        // Lo medido pesa mas que las especificaciones: si el tick ya sufre, no
        // importa lo que diga la ficha tecnica del equipo.
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor != null && motor.rendimiento().totalTicks() > 100) {
            double p95 = motor.rendimiento().p95Ms();
            if (p95 >= 50) {
                puntaje -= 3;
                razones.add(String.format(Locale.ROOT,
                        "MEDIDO: el p95 del tick esta en %.0f ms, por encima del limite de 50. "
                                + "Esto pesa mas que cualquier especificacion.", p95));
            } else if (p95 >= 35) {
                puntaje -= 1;
                razones.add(String.format(Locale.ROOT,
                        "MEDIDO: el p95 del tick esta en %.0f ms. Hay poco margen.", p95));
            } else {
                puntaje += 1;
                razones.add(String.format(Locale.ROOT,
                        "MEDIDO: el p95 del tick esta en %.0f ms. Va comodo.", p95));
            }
        } else {
            faltantes.add("rendimiento medido (jugá un rato y volvé para afinar el preset)");
        }

        Nivel nivel = puntaje >= 4 ? Nivel.CALIDAD : (puntaje >= 0 ? Nivel.EQUILIBRADO : Nivel.MINIMO);
        boolean hayEmbeddium = Integraciones.hay("embeddium") || Integraciones.hay("rubidium");

        List<Ajuste> ajustes = new ArrayList<>();
        ajustesVanilla(nivel, ajustes);
        if (hayEmbeddium) {
            ajustesEmbeddium(nivel, ajustes);
        }

        return new Preset(nivel, ajustes, razones, faltantes, hayEmbeddium);
    }

    // ------------------------------------------------------------- vanilla

    private static void ajustesVanilla(Nivel n, List<Ajuste> out) {
        Minecraft mc = Minecraft.getInstance();

        int render = switch (n) {
            case MINIMO -> 6;
            case EQUILIBRADO -> 10;
            case CALIDAD -> 16;
        };
        int simulacion = switch (n) {
            case MINIMO -> 5;
            case EQUILIBRADO -> 8;
            case CALIDAD -> 12;
        };

        out.add(new Ajuste("options.txt", "Distancia de render",
                String.valueOf(mc.options.renderDistance().get()), String.valueOf(render),
                "Es el ajuste que mas pesa, de lejos. Cada chunk extra son bloques que hay "
                        + "que construir, guardar en memoria y dibujar."));

        out.add(new Ajuste("options.txt", "Distancia de simulacion",
                String.valueOf(mc.options.simulationDistance().get()), String.valueOf(simulacion),
                "Cuantos chunks siguen 'vivos' (mobs, maquinas, cultivos). Pega en el tick, "
                        + "no en los FPS."));

        String nubes = switch (n) {
            case MINIMO -> "OFF";
            case EQUILIBRADO -> "FAST";
            case CALIDAD -> "FANCY";
        };
        out.add(new Ajuste("options.txt", "Nubes", String.valueOf(mc.options.cloudStatus().get()),
                nubes, "Las nubes en modo elegante cuestan mas de lo que parece en equipos justos."));

        String particulas = n == Nivel.MINIMO ? "MINIMAL"
                : (n == Nivel.EQUILIBRADO ? "DECREASED" : "ALL");
        out.add(new Ajuste("options.txt", "Particulas",
                String.valueOf(mc.options.particles().get()), particulas,
                "Con mods de magia o maquinas, las particulas se multiplican y pueden "
                        + "tirar los FPS a la mitad en una base grande."));

        out.add(new Ajuste("options.txt", "Sincronizacion vertical",
                String.valueOf(mc.options.enableVsync().get()),
                n == Nivel.MINIMO ? "false" : "true",
                n == Nivel.MINIMO
                        ? "Con el equipo justo, limitar los FPS al monitor puede empeorar los tirones."
                        : "Evita cuadros partidos y baja el consumo sin costo perceptible."));

        int fps = switch (n) {
            case MINIMO -> 60;
            case EQUILIBRADO -> 120;
            case CALIDAD -> 260;
        };
        out.add(new Ajuste("options.txt", "Limite de FPS",
                String.valueOf(mc.options.framerateLimit().get()), String.valueOf(fps),
                "Un tope razonable baja la temperatura y el ruido del equipo sin que se note."));
    }

    /** Aplica los ajustes de vanilla. Devuelve cuantos se cambiaron. */
    public static int aplicarVanilla(Nivel n) {
        Minecraft mc = Minecraft.getInstance();
        int cambios = 0;
        try {
            int render = switch (n) {
                case MINIMO -> 6;
                case EQUILIBRADO -> 10;
                case CALIDAD -> 16;
            };
            int simulacion = switch (n) {
                case MINIMO -> 5;
                case EQUILIBRADO -> 8;
                case CALIDAD -> 12;
            };
            mc.options.renderDistance().set(render);
            mc.options.simulationDistance().set(simulacion);
            mc.options.cloudStatus().set(switch (n) {
                case MINIMO -> net.minecraft.client.CloudStatus.OFF;
                case EQUILIBRADO -> net.minecraft.client.CloudStatus.FAST;
                case CALIDAD -> net.minecraft.client.CloudStatus.FANCY;
            });
            mc.options.particles().set(switch (n) {
                case MINIMO -> net.minecraft.client.ParticleStatus.MINIMAL;
                case EQUILIBRADO -> net.minecraft.client.ParticleStatus.DECREASED;
                case CALIDAD -> net.minecraft.client.ParticleStatus.ALL;
            });
            mc.options.enableVsync().set(n != Nivel.MINIMO);
            mc.options.framerateLimit().set(switch (n) {
                case MINIMO -> 60;
                case EQUILIBRADO -> 120;
                case CALIDAD -> 260;
            });
            mc.options.save();
            mc.levelRenderer.allChanged();
            cambios = 6;
        } catch (Throwable t) {
            Faro.LOG.error("[Faro] No pude aplicar el preset de vanilla", t);
        }
        return cambios;
    }

    // ----------------------------------------------------------- Embeddium

    /** Claves de embeddium-options.json que Faro entiende y toca. */
    private static Map<String, String> valoresEmbeddium(Nivel n) {
        Map<String, String> m = new LinkedHashMap<>();
        switch (n) {
            case MINIMO -> {
                m.put("quality.weather_quality", "\"FAST\"");
                m.put("quality.leaves_quality", "\"FAST\"");
                m.put("quality.enable_vignette", "false");
                m.put("performance.chunk_update_threads", "0");
                m.put("performance.always_defer_chunk_updates_v2", "true");
                m.put("performance.animate_only_visible_textures", "true");
                m.put("performance.use_entity_culling", "true");
                m.put("performance.use_fog_occlusion", "true");
                m.put("performance.use_block_face_culling", "true");
                m.put("performance.use_no_error_g_l_context", "true");
            }
            case EQUILIBRADO -> {
                m.put("quality.weather_quality", "\"DEFAULT\"");
                m.put("quality.leaves_quality", "\"DEFAULT\"");
                m.put("quality.enable_vignette", "true");
                m.put("performance.chunk_update_threads", "0");
                m.put("performance.always_defer_chunk_updates_v2", "false");
                m.put("performance.animate_only_visible_textures", "true");
                m.put("performance.use_entity_culling", "true");
                m.put("performance.use_fog_occlusion", "true");
                m.put("performance.use_block_face_culling", "true");
                m.put("performance.use_no_error_g_l_context", "true");
            }
            case CALIDAD -> {
                m.put("quality.weather_quality", "\"FANCY\"");
                m.put("quality.leaves_quality", "\"FANCY\"");
                m.put("quality.enable_vignette", "true");
                m.put("performance.chunk_update_threads", "0");
                m.put("performance.always_defer_chunk_updates_v2", "false");
                m.put("performance.animate_only_visible_textures", "false");
                m.put("performance.use_entity_culling", "true");
                m.put("performance.use_fog_occlusion", "false");
                m.put("performance.use_block_face_culling", "true");
                m.put("performance.use_no_error_g_l_context", "true");
            }
        }
        return m;
    }

    private static void ajustesEmbeddium(Nivel n, List<Ajuste> out) {
        Map<String, String> objetivo = valoresEmbeddium(n);
        Map<String, String> actual = leerEmbeddium();

        for (Map.Entry<String, String> e : objetivo.entrySet()) {
            String clave = e.getKey();
            out.add(new Ajuste("embeddium-options.json", clave,
                    actual.getOrDefault(clave, "(sin definir)"), e.getValue(),
                    explicarClave(clave)));
        }
    }

    private static String explicarClave(String clave) {
        return switch (clave) {
            case "quality.weather_quality" -> "Detalle de lluvia y nieve.";
            case "quality.leaves_quality" -> "Hojas transparentes vs. solidas. En modo rapido "
                    + "no se ve a traves y se dibuja mucho menos.";
            case "quality.enable_vignette" -> "El oscurecimiento de los bordes de la pantalla.";
            case "performance.chunk_update_threads" -> "0 = que lo decida Embeddium segun tus "
                    + "nucleos. Poner un numero a mano casi siempre empeora.";
            case "performance.always_defer_chunk_updates_v2" -> "Prioriza FPS estables sobre que "
                    + "los bloques aparezcan al instante. Se nota al minar rapido.";
            case "performance.animate_only_visible_textures" -> "No animar agua ni lava fuera "
                    + "de la vista. Gratis y sin contras.";
            case "performance.use_entity_culling" -> "No dibujar entidades tapadas por bloques.";
            case "performance.use_fog_occlusion" -> "No dibujar lo que queda detras de la niebla. "
                    + "Ayuda mucho con distancias altas.";
            case "performance.use_block_face_culling" -> "No dibujar las caras de bloque que "
                    + "no se pueden ver.";
            case "performance.use_no_error_g_l_context" -> "Le pide al driver que no valide cada "
                    + "llamada grafica. OJO: con esto activo, el detector de errores de OpenGL "
                    + "de Faro deja de ver nada.";
            default -> "";
        };
    }

    public static Path archivoEmbeddium() {
        try {
            return Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("config").resolve("embeddium-options.json");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Lee las claves que nos interesan del json, sin parsearlo entero. */
    private static Map<String, String> leerEmbeddium() {
        Map<String, String> out = new LinkedHashMap<>();
        Path archivo = archivoEmbeddium();
        if (archivo == null || !Files.isRegularFile(archivo)) {
            return out;
        }
        try {
            com.google.gson.JsonElement raiz = com.google.gson.JsonParser.parseString(
                    Files.readString(archivo, StandardCharsets.UTF_8));
            if (!raiz.isJsonObject()) {
                return out;
            }
            aplanar(raiz.getAsJsonObject(), "", out);
        } catch (Throwable t) {
            Faro.LOG.warn("[Faro] No pude leer embeddium-options.json: {}", t.toString());
        }
        return out;
    }

    private static void aplanar(com.google.gson.JsonObject obj, String prefijo,
                                Map<String, String> out) {
        for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
            String clave = prefijo.isEmpty() ? e.getKey() : prefijo + "." + e.getKey();
            if (e.getValue().isJsonObject()) {
                aplanar(e.getValue().getAsJsonObject(), clave, out);
            } else {
                out.put(clave, e.getValue().toString());
            }
        }
    }

    /**
     * Escribe el json de Embeddium respetando lo que no entendemos.
     *
     * Se modifica el arbol leido en vez de generar uno nuevo: si el usuario tiene
     * ajustes que Faro no conoce, o si una version futura agrega claves, siguen
     * ahi despues de aplicar el preset.
     */
    public static String aplicarEmbeddium(Nivel n) {
        Path archivo = archivoEmbeddium();
        if (archivo == null || !Files.isRegularFile(archivo)) {
            return "No encontre config/embeddium-options.json. ¿Esta instalado Embeddium?";
        }
        try {
            // Respaldo antes de tocar nada. Siempre.
            Path respaldo = archivo.resolveSibling("embeddium-options.json.faro-backup");
            Files.copy(archivo, respaldo, StandardCopyOption.REPLACE_EXISTING);

            com.google.gson.JsonElement raiz = com.google.gson.JsonParser.parseString(
                    Files.readString(archivo, StandardCharsets.UTF_8));
            if (!raiz.isJsonObject()) {
                return "El archivo no tiene el formato esperado. No lo toque.";
            }
            com.google.gson.JsonObject obj = raiz.getAsJsonObject();

            int cambios = 0;
            for (Map.Entry<String, String> e : valoresEmbeddium(n).entrySet()) {
                if (escribirRuta(obj, e.getKey(), e.getValue())) {
                    cambios++;
                }
            }

            Files.writeString(archivo,
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj),
                    StandardCharsets.UTF_8);

            return cambios + " ajustes escritos. Respaldo en embeddium-options.json.faro-backup. "
                    + "Embeddium lee este archivo al arrancar: reinicia el juego para que tome efecto.";
        } catch (Throwable t) {
            Faro.LOG.error("[Faro] No pude escribir embeddium-options.json", t);
            return "No pude escribirlo: " + t.getMessage();
        }
    }

    /** Navega 'a.b.c' creando los objetos que falten y escribe el valor. */
    private static boolean escribirRuta(com.google.gson.JsonObject raiz, String ruta, String valorJson) {
        String[] partes = ruta.split("\\.");
        com.google.gson.JsonObject actual = raiz;
        for (int i = 0; i < partes.length - 1; i++) {
            com.google.gson.JsonElement hijo = actual.get(partes[i]);
            if (hijo == null || !hijo.isJsonObject()) {
                com.google.gson.JsonObject nuevo = new com.google.gson.JsonObject();
                actual.add(partes[i], nuevo);
                actual = nuevo;
            } else {
                actual = hijo.getAsJsonObject();
            }
        }
        try {
            actual.add(partes[partes.length - 1],
                    com.google.gson.JsonParser.parseString(valorJson));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
