package com.coco.faro.repair;

import com.coco.faro.diag.Diagnostico;
import com.coco.faro.diag.Firma;
import com.coco.faro.diag.MetadatosJar;
import com.coco.faro.diag.MonitorRendimiento;
import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.diag.Problema;
import com.coco.faro.diag.Sospechoso;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Arma un reporte de texto plano con todo el diagnostico, listo para pegar en un
 * foro, un Discord o una conversacion con alguien que pueda ayudar.
 *
 * Se escriben rutas relativas y nombres de archivo, nunca rutas absolutas: el
 * reporte esta pensado para compartirse, y una ruta absoluta de Windows lleva
 * adentro el nombre de usuario.
 */
public final class ExportadorReporte {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private ExportadorReporte() {
    }

    /** Genera el texto del reporte. */
    public static String generar(MotorDiagnostico motor) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== REPORTE DE FARO ===\n");
        sb.append("Generado: ").append(LocalDateTime.now()).append('\n');
        sb.append("Minecraft 1.20.1 / Forge\n\n");

        if (motor == null || !motor.listo()) {
            sb.append("El analisis todavia no habia terminado.\n");
            return sb.toString();
        }

        motor.inventario().ifPresent(inv -> {
            sb.append("--- CARGA ---\n");
            sb.append("Mods cargados: ").append(inv.cantidadCargados()).append('\n');
            sb.append("Jars en la carpeta: ").append(inv.cantidadJarsEnCarpeta()).append('\n');
            List<String> no = inv.jarsQueNoCargaron();
            sb.append("Jars que no cargaron: ").append(no.size()).append('\n');
            for (String n : no) {
                sb.append("  - ").append(n).append('\n');
            }
            sb.append('\n');
        });

        sb.append("--- PROBLEMAS DETECTADOS (").append(motor.problemas().size()).append(") ---\n");
        for (Problema p : motor.problemas()) {
            sb.append('[').append(p.severidad().etiqueta()).append("] ")
                    .append(p.categoria().etiqueta()).append(": ")
                    .append(p.titulo()).append('\n');
            sb.append("    ").append(p.detalle()).append('\n');
        }
        sb.append('\n');

        motor.diagnostico().filter(Diagnostico::huboCrash).ifPresent(d -> {
            sb.append("--- ULTIMO CRASH ---\n");
            sb.append("Tipo: ").append(d.tipo().titulo()).append('\n');
            sb.append("Confianza: ").append(d.confianza().etiqueta()).append('\n');
            sb.append("Excepcion: ").append(d.excepcionPrincipal()).append('\n');
            sb.append("Description: ").append(d.descripcion()).append('\n');

            if (!d.firmas().isEmpty()) {
                sb.append("Firmas reconocidas:\n");
                for (Firma.Coincidencia c : d.firmas()) {
                    sb.append("  - ").append(c.firma().id())
                            .append(" (peso ").append(c.firma().peso()).append(")\n");
                }
            }
            if (!d.ranking().isEmpty()) {
                sb.append("Sospechosos:\n");
                for (Sospechoso s : d.ranking()) {
                    sb.append("  ").append(s.puntaje()).append(" pts  ")
                            .append(s.modId()).append('\n');
                    for (Sospechoso.Indicio i : s.indicios()) {
                        sb.append("      ").append(i.puntos() > 0 ? "+" : "")
                                .append(i.puntos()).append("  ").append(i.descripcion()).append('\n');
                    }
                }
            }
            sb.append("Stacktrace (recortado):\n");
            for (String l : d.lineasStack()) {
                sb.append("    ").append(l).append('\n');
            }
            sb.append('\n');
        });

        MonitorRendimiento r = motor.rendimiento();
        sb.append("--- RENDIMIENTO ---\n");
        sb.append(String.format("tick promedio %.1f ms / p95 %.1f ms / peor %.1f ms (%d ticks)%n",
                r.promedioMs(), r.p95Ms(), r.peorMs(), r.totalTicks()));
        sb.append("Memoria: ").append(MonitorRendimiento.memoriaUsadaMB()).append(" MB de ")
                .append(MonitorRendimiento.memoriaMaximaMB()).append(" MB\n\n");

        sb.append("--- MODS INSTALADOS (").append(motor.jars().size()).append(") ---\n");
        for (MetadatosJar j : motor.jars()) {
            sb.append(j.nombreArchivo());
            if (!j.version().isBlank()) {
                sb.append("  v").append(j.version());
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /** Escribe el reporte en faro/reportes/ y devuelve la ruta. */
    public static Path escribir(MotorDiagnostico motor) throws Exception {
        String texto = generar(motor);
        Path carpeta = motor.carpetaFaro().resolve("reportes");
        Files.createDirectories(carpeta);
        Path archivo = carpeta.resolve("faro-reporte-" + LocalDateTime.now().format(FMT) + ".txt");
        Files.writeString(archivo, texto, StandardCharsets.UTF_8);
        return archivo;
    }

    /**
     * Version corta pensada para pegar en un chat: lo esencial sin la lista
     * completa de mods, que hace ilegible cualquier conversacion.
     */
    public static String resumenParaPegar(MotorDiagnostico motor) {
        StringBuilder sb = new StringBuilder();
        sb.append("Minecraft 1.20.1 Forge. Diagnostico de Faro:\n");
        if (motor == null || !motor.listo()) {
            return sb.append("(analisis incompleto)").toString();
        }
        motor.inventario().ifPresent(inv ->
                sb.append("- ").append(inv.cantidadCargados()).append(" mods cargados, ")
                        .append(inv.jarsQueNoCargaron().size()).append(" jars sin cargar\n"));

        for (Problema p : motor.problemasSerios()) {
            sb.append("- [").append(p.severidad().etiqueta()).append("] ")
                    .append(p.titulo()).append('\n');
        }
        motor.diagnostico().filter(Diagnostico::huboCrash).ifPresent(d -> {
            sb.append("- Crash: ").append(d.tipo().titulo())
                    .append(" (confianza ").append(d.confianza().etiqueta()).append(")\n");
            sb.append("  ").append(d.excepcionPrincipal()).append('\n');
            d.modSospechoso().ifPresent(m -> sb.append("  sospechoso: ").append(m).append('\n'));
        });
        return sb.toString();
    }
}
