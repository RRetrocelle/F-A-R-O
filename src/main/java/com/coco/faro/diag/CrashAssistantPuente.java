package com.coco.faro.diag;

import com.coco.faro.Faro;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Integracion con Crash Assistant, de verdad y no solo nombrandolo.
 *
 * Que aporta Crash Assistant que Faro no puede: aparece DESPUES de que el juego
 * se cerro, en una ventana propia, incluso cuando el crash fue tan temprano que
 * Faro no llego a cargar. Ese es el hueco que Faro nunca va a cubrir solo — si el
 * mod no se construyo, no hay nada que pueda mostrar una pantalla.
 *
 * Que aporta Faro que Crash Assistant no: el contexto de la instalacion. El grafo
 * de dependencias, los duplicados, las versiones fuera de rango, los mixins
 * compartidos. Crash Assistant lee el crash; Faro sabe como esta armado el pack.
 *
 * La integracion real hace tres cosas concretas:
 *
 *   1. DETECTA su presencia y su configuracion, y te dice si esta prendido. Un
 *      Crash Assistant instalado pero desactivado en su config es peor que no
 *      tenerlo: creés que estas cubierto y no lo estas.
 *   2. LEE los reportes que deja en disco y los cruza con el analisis de Faro,
 *      marcando donde coinciden y donde no. Que dos analisis independientes
 *      apunten al mismo mod vale mucho mas que cualquiera de los dos solo.
 *   3. DEJA un informe de Faro donde Crash Assistant lo pueda mostrar, para que
 *      el proximo crash tenga el contexto del pack a mano.
 *
 * Si el mod no esta instalado, todo esto se apaga solo y se explica que aporta.
 * Faro no lo declara como dependencia ni lo necesita.
 */
public final class CrashAssistantPuente {

    public static final String MOD_ID = "crashassistant";

    /** Un reporte encontrado en la carpeta de Crash Assistant. */
    public record ReporteExterno(Path archivo, long fecha, String resumen,
                                 List<String> modsNombrados) {
    }

    public record Estado(boolean instalado, boolean configEncontrada, boolean activoSegunConfig,
                         Path carpetaReportes, List<ReporteExterno> reportes,
                         List<String> coincidencias, List<String> discrepancias) {

        public boolean funcionando() {
            return instalado && activoSegunConfig;
        }
    }

    private CrashAssistantPuente() {
    }

    public static boolean instalado() {
        return Integraciones.hay(MOD_ID);
    }

    /**
     * Estado completo de la integracion.
     *
     * @param diagnostico el analisis de Faro, para poder cruzarlo. Puede ser null.
     */
    public static Estado analizar(Path carpetaJuego, Diagnostico diagnostico) {
        boolean hay = instalado();

        Path config = buscarConfig(carpetaJuego);
        boolean activo = config == null || leerActivo(config);

        Path carpetaReportes = buscarCarpetaReportes(carpetaJuego);
        List<ReporteExterno> reportes = leerReportes(carpetaReportes);

        List<String> coincidencias = new ArrayList<>();
        List<String> discrepancias = new ArrayList<>();
        cruzar(diagnostico, reportes, coincidencias, discrepancias);

        return new Estado(hay, config != null, activo, carpetaReportes, reportes,
                coincidencias, discrepancias);
    }

    /**
     * Busca el archivo de config del mod.
     *
     * Se prueban varios nombres porque distintas versiones lo llaman distinto, y
     * porque no vale la pena depender de un nombre exacto para algo opcional.
     */
    private static Path buscarConfig(Path carpetaJuego) {
        Path config = carpetaJuego.resolve("config");
        if (!Files.isDirectory(config)) {
            return null;
        }
        String[] candidatos = {
                "crashassistant.toml", "crashassistant-client.toml", "crashassistant-common.toml",
                "crash_assistant.toml", "crashassistant.json",
        };
        for (String nombre : candidatos) {
            Path p = config.resolve(nombre);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        // Ultimo intento: cualquier archivo del config que lo mencione en el nombre.
        try (var flujo = Files.list(config)) {
            for (Path p : flujo.toList()) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (n.contains("crashassistant") || n.contains("crash_assistant")) {
                    return p;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Lee si el mod esta habilitado segun su propia config.
     *
     * Sin conocer el esquema exacto se busca cualquier clave que hable de
     * habilitado/enabled y su valor booleano. Si no se encuentra nada, se asume
     * que si — no inventar un "esta apagado" que no se pudo verificar.
     */
    private static boolean leerActivo(Path config) {
        try {
            String texto = Files.readString(config, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            for (String linea : texto.split("\\R")) {
                String t = linea.trim();
                if (t.startsWith("#")) {
                    continue;
                }
                if ((t.contains("enabled") || t.contains("enable") || t.contains("activo"))
                        && t.contains("=")) {
                    return !t.endsWith("false");
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** Carpetas donde Crash Assistant suele dejar sus reportes. */
    private static Path buscarCarpetaReportes(Path carpetaJuego) {
        String[] candidatas = {
                "crash-assistant", "crashassistant", "crash_assistant",
                "crash-reports/analyzed", "logs/crashassistant",
        };
        for (String c : candidatas) {
            Path p = carpetaJuego.resolve(c.replace("/", java.io.File.separator));
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    private static List<ReporteExterno> leerReportes(Path carpeta) {
        List<ReporteExterno> out = new ArrayList<>();
        if (carpeta == null || !Files.isDirectory(carpeta)) {
            return out;
        }
        try (var flujo = Files.list(carpeta)) {
            List<Path> archivos = flujo.filter(Files::isRegularFile).toList();
            for (Path p : archivos) {
                try {
                    String contenido = Files.readString(p, StandardCharsets.UTF_8);
                    out.add(new ReporteExterno(p,
                            Files.getLastModifiedTime(p).toMillis(),
                            primeraLineaUtil(contenido),
                            modsNombradosEn(contenido)));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        out.sort(Comparator.comparingLong(ReporteExterno::fecha).reversed());
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    private static String primeraLineaUtil(String contenido) {
        for (String linea : contenido.split("\\R")) {
            String t = linea.trim();
            if (!t.isEmpty() && !t.startsWith("#") && t.length() > 10) {
                return t.length() > 200 ? t.substring(0, 200) + "..." : t;
            }
        }
        return "(vacio)";
    }

    /** Extrae modIds mencionados en el reporte externo. */
    private static List<String> modsNombradosEn(String contenido) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\b(?:mod|modid)\\s*[:=]?\\s*['\"]?([a-z0-9_\\-]{3,32})['\"]?")
                .matcher(contenido);
        while (m.find() && out.size() < 12) {
            String id = m.group(1).toLowerCase(Locale.ROOT);
            if (!out.contains(id) && !id.equals("minecraft") && !id.equals("forge")) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Cruza lo que dice Crash Assistant con lo que concluyo Faro.
     *
     * Coincidencia = los dos apuntan al mismo mod. Eso sube la confianza mas que
     * cualquier ajuste de puntaje interno, porque son dos analisis con metodos
     * distintos llegando al mismo lugar.
     *
     * Discrepancia = uno nombra un mod que el otro no. No significa que alguno se
     * equivoque; significa que hay que mirar los dos. Se muestra como tal.
     */
    private static void cruzar(Diagnostico d, List<ReporteExterno> reportes,
                               List<String> coincidencias, List<String> discrepancias) {
        if (d == null || !d.huboCrash() || reportes.isEmpty()) {
            return;
        }
        List<String> deFaro = d.ranking().stream()
                .map(s -> s.modId().toLowerCase(Locale.ROOT))
                .toList();
        if (deFaro.isEmpty()) {
            return;
        }

        java.util.Set<String> deCrashAssistant = new java.util.LinkedHashSet<>();
        for (ReporteExterno r : reportes) {
            deCrashAssistant.addAll(r.modsNombrados());
        }

        for (String id : deFaro) {
            if (deCrashAssistant.contains(id)) {
                coincidencias.add(id);
            }
        }
        for (String id : deCrashAssistant) {
            if (!deFaro.contains(id)) {
                discrepancias.add(id);
            }
        }
    }

    /**
     * Deja el contexto del pack donde Crash Assistant lo pueda encontrar.
     *
     * Se escribe en la carpeta de Faro y en la de Crash Assistant si existe. Un
     * archivo de texto plano, para que sirva aunque nadie lo lea con un programa:
     * despues de un crash, tener el estado del pack en un .txt al lado del reporte
     * es lo que permite entender que paso sin volver a abrir el juego.
     */
    public static Path escribirContexto(Path carpetaJuego, MotorDiagnostico motor) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Contexto del pack, escrito por Faro ===\n");
        sb.append("Generado: ").append(java.time.LocalDateTime.now()).append("\n\n");

        if (motor != null && motor.listo()) {
            motor.inventario().ifPresent(inv -> {
                sb.append("Mods cargados: ").append(inv.cantidadCargados()).append("\n");
                sb.append("Jars en la carpeta: ").append(inv.cantidadJarsEnCarpeta()).append("\n");
                List<String> no = inv.jarsQueNoCargaron();
                if (!no.isEmpty()) {
                    sb.append("NO cargaron (").append(no.size()).append("):\n");
                    for (String n : no) {
                        sb.append("  - ").append(n).append('\n');
                    }
                }
            });
            sb.append("\nProblemas detectados:\n");
            List<Problema> serios = motor.problemasSerios();
            if (serios.isEmpty()) {
                sb.append("  ninguno serio.\n");
            } else {
                for (Problema p : serios) {
                    sb.append("  [").append(p.severidad().etiqueta()).append("] ")
                            .append(p.titulo()).append('\n');
                    sb.append("      ").append(p.detalle()).append('\n');
                }
            }
            motor.diagnostico().filter(Diagnostico::huboCrash).ifPresent(d -> {
                sb.append("\nUltimo crash segun Faro: ").append(d.tipo().titulo())
                        .append("  (confianza ").append(d.confianza().etiqueta()).append(")\n");
                for (Sospechoso s : d.ranking()) {
                    sb.append("  ").append(s.puntaje()).append(" pts  ")
                            .append(s.modId()).append('\n');
                }
            });
        } else {
            sb.append("El analisis de Faro no llego a completarse en esta sesion.\n");
        }

        sb.append("\nEste archivo lo genera Faro para que Crash Assistant (o vos) tengan el "
                + "estado del pack a mano al mirar un crash.\n");

        Path faro = carpetaJuego.resolve("faro");
        Files.createDirectories(faro);
        Path destino = faro.resolve("contexto-para-crash.txt");
        Files.writeString(destino, sb.toString(), StandardCharsets.UTF_8);

        Path carpetaCa = buscarCarpetaReportes(carpetaJuego);
        if (carpetaCa != null && Files.isDirectory(carpetaCa)) {
            try {
                Files.writeString(carpetaCa.resolve("faro-contexto.txt"), sb.toString(),
                        StandardCharsets.UTF_8);
            } catch (Throwable t) {
                Faro.LOG.debug("[Faro] No pude escribir en la carpeta de Crash Assistant: {}",
                        t.toString());
            }
        }
        return destino;
    }

    public static String veredicto(Estado e) {
        if (!e.instalado()) {
            return "Crash Assistant no esta instalado. Es el complemento natural de Faro: "
                    + "muestra el analisis del crash apenas el juego se cierra, incluso cuando "
                    + "el fallo fue tan temprano que Faro no llego ni a cargar. Ese hueco Faro "
                    + "no lo puede cubrir solo.";
        }
        if (!e.activoSegunConfig()) {
            return "Crash Assistant esta instalado pero su config lo tiene desactivado. "
                    + "Eso es peor que no tenerlo: crees que estas cubierto y no lo estas.";
        }
        if (!e.coincidencias().isEmpty()) {
            return "Los dos analisis coinciden en: " + String.join(", ", e.coincidencias())
                    + ". Que dos metodos distintos lleguen al mismo mod es la senal mas fuerte "
                    + "que se puede tener.";
        }
        if (!e.reportes().isEmpty()) {
            return "Crash Assistant activo, con " + e.reportes().size()
                    + " reporte(s) leidos. No coincide con ningun sospechoso de Faro: "
                    + "conviene mirar los dos analisis.";
        }
        return "Crash Assistant activo. Todavia no dejo reportes que Faro pueda leer.";
    }
}
