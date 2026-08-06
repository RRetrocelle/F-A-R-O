package com.coco.faro.client;

import com.coco.faro.config.ConfigFaro;
import com.coco.faro.diag.MetadatosJar;
import com.coco.faro.diag.MonitorRed;
import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.diag.Problema;
import com.coco.faro.diag.Severidad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Faro-Vision: tooltips contextuales sobre lo que estas mirando.
 *
 * La pregunta que responde, y que hoy no tiene forma facil de responderse: "¿de
 * que mod es esta cosa?". Con 190 mods, cuando algo se comporta raro, identificar
 * al responsable implica salir del juego, abrir JEI o buscar el nombre a mano.
 *
 * Faro-Vision lo dice en pantalla, mirando el bloque o la entidad, y ademas cruza
 * ese mod con lo que Faro ya sabe de el:
 *
 *   - si tiene problemas de dependencias detectados;
 *   - si aparece como sospechoso del ultimo crash;
 *   - si es el que mas errores esta metiendo en el log de esta sesion;
 *   - si esta generando trafico de red anormal.
 *
 * O sea: no es un "identificador de bloques" mas — de esos hay varios. Es el
 * unico que te conecta lo que estas viendo con el diagnostico del pack.
 *
 * De donde sale el modId: del namespace del identificador de registro. Un bloque
 * registrado como {@code create:cogwheel} es de Create, sin heuristica de por
 * medio. Es dato duro.
 *
 * Rendimiento: se recalcula como maximo cuatro veces por segundo y solo cuando
 * el objetivo cambia. El resto de los cuadros dibuja texto ya armado.
 */
public final class FaroVision {

    /** Cada cuanto se recalcula el contenido. */
    private static final long INTERVALO_MS = 250L;

    private static final int ANCHO = 190;

    /** Una linea del cartel, con su color. */
    private record Linea(String texto, int color) {
    }

    private static long ultimoCalculo = 0L;
    private static String claveObjetivo = "";
    private static String titulo = "";
    private static String subtitulo = "";
    private static final List<Linea> lineas = new ArrayList<>();
    private static int acento = Paleta.NEUTRO;

    private FaroVision() {
    }

    public static boolean activo() {
        try {
            return ConfigFaro.INSTANCIA.faroVision.get();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void alternar() {
        try {
            boolean nuevo = !ConfigFaro.INSTANCIA.faroVision.get();
            ConfigFaro.INSTANCIA.faroVision.set(nuevo);
            ConfigFaro.INSTANCIA.faroVision.save();
        } catch (Throwable ignored) {
        }
    }

    /** Dibuja el cartel. Se llama desde el overlay del HUD. */
    public static void render(GuiGraphics g, int anchoPantalla, int altoPantalla) {
        if (!activo()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null || mc.options.hideGui
                || mc.screen != null) {
            return;
        }

        long ahora = System.currentTimeMillis();
        if (ahora - ultimoCalculo > INTERVALO_MS) {
            ultimoCalculo = ahora;
            recalcular(mc);
        }
        if (titulo.isEmpty()) {
            return;
        }

        int alto = 24 + lineas.size() * 10;
        // Abajo al centro, encima de la barra de experiencia pero sin taparla.
        int x = (anchoPantalla - ANCHO) / 2;
        int y = altoPantalla - alto - 62;

        g.fill(x, y, x + ANCHO, y + alto, 0xE0090C10);
        Widgets.borde(g, x, y, ANCHO, alto, Paleta.conAlfa(acento, 0.8f));
        g.fill(x, y, x + 2, y + alto, acento);

        Widgets.lineaRecortada(g, mc.font, titulo, x + 6, y + 5, ANCHO - 12, Paleta.TEXTO);
        Widgets.lineaRecortada(g, mc.font, subtitulo, x + 6, y + 15, ANCHO - 12, acento);

        int yy = y + 27;
        for (Linea l : lineas) {
            Widgets.lineaRecortada(g, mc.font, l.texto(), x + 6, yy, ANCHO - 12, l.color());
            yy += 10;
        }
    }

    private static void recalcular(Minecraft mc) {
        HitResult objetivo = mc.hitResult;
        if (objetivo == null || objetivo.getType() == HitResult.Type.MISS) {
            limpiar();
            return;
        }

        String id;
        String nombreVisible;

        if (objetivo instanceof BlockHitResult bloque) {
            BlockState estado = mc.level.getBlockState(bloque.getBlockPos());
            var clave = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(estado.getBlock());
            if (clave == null) {
                limpiar();
                return;
            }
            id = clave.toString();
            nombreVisible = estado.getBlock().getName().getString();
        } else if (objetivo instanceof EntityHitResult ent) {
            Entity e = ent.getEntity();
            var clave = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
            if (clave == null) {
                limpiar();
                return;
            }
            id = clave.toString();
            nombreVisible = e.getDisplayName().getString();
        } else {
            limpiar();
            return;
        }

        if (id.equals(claveObjetivo)) {
            return; // mismo objetivo: no hay nada que recalcular
        }
        claveObjetivo = id;

        String modId = id.substring(0, Math.max(0, id.indexOf(':')));
        titulo = nombreVisible;
        subtitulo = id;
        lineas.clear();
        acento = Paleta.NEUTRO;

        if (modId.equals("minecraft")) {
            lineas.add(new Linea("Del juego base. Ningun mod interviene aca.", Paleta.TEXTO_APAGADO));
            acento = Paleta.TEXTO_APAGADO;
            return;
        }

        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor == null || !motor.listo()) {
            lineas.add(new Linea("Mod: " + modId, Paleta.TEXTO_TENUE));
            lineas.add(new Linea("(el analisis todavia esta corriendo)", Paleta.TEXTO_APAGADO));
            return;
        }

        // Nombre lindo del mod, si se puede resolver.
        String nombreMod = modId;
        for (MetadatosJar j : motor.jars()) {
            if (j.todosLosModIds().stream().anyMatch(x -> x.equalsIgnoreCase(modId))) {
                nombreMod = j.nombreVisible();
                if (!j.version().isBlank()) {
                    nombreMod += "  " + j.version();
                }
                break;
            }
        }
        lineas.add(new Linea(nombreMod, Paleta.TEXTO_TENUE));

        // --- Cruce con el diagnostico. Esto es lo que ningun otro mod hace.
        boolean algoMal = false;

        List<Problema> suyos = motor.problemas().stream()
                .filter(p -> p.mod().map(m -> m.equalsIgnoreCase(modId)).orElse(false))
                .toList();
        for (Problema p : suyos) {
            if (p.severidad() == Severidad.CRITICA || p.severidad() == Severidad.ALTA) {
                lineas.add(new Linea("! " + p.titulo(), Paleta.porSeveridad(p.severidad())));
                acento = Paleta.porSeveridad(p.severidad());
                algoMal = true;
            }
        }

        motor.diagnostico().ifPresent(d -> {
            if (!d.huboCrash()) {
                return;
            }
            d.ranking().stream()
                    .filter(s -> s.modId().equalsIgnoreCase(modId))
                    .findFirst()
                    .ifPresent(s -> {
                        lineas.add(new Linea("! sospechoso del ultimo crash (" + s.puntaje() + " pts)",
                                Paleta.ERROR));
                        acento = Paleta.ERROR;
                    });
        });

        String ruidoso = motor.vigilante().origenMasRuidoso();
        if (ruidoso != null && ruidoso.equalsIgnoreCase(modId)) {
            lineas.add(new Linea("! es el que mas errores mete en el log", Paleta.ADVERTENCIA));
            if (acento == Paleta.NEUTRO) {
                acento = Paleta.ADVERTENCIA;
            }
            algoMal = true;
        }

        for (MonitorRed.Canal c : MonitorRed.anormales()) {
            if (c.modId().equalsIgnoreCase(modId)) {
                lineas.add(new Linea("! trafico de red alto: " + c.bytesLegibles(),
                        Paleta.ADVERTENCIA));
                if (acento == Paleta.NEUTRO) {
                    acento = Paleta.ADVERTENCIA;
                }
                algoMal = true;
                break;
            }
        }

        if (!algoMal && acento == Paleta.NEUTRO) {
            lineas.add(new Linea("Sin problemas detectados en este mod.", Paleta.OK));
            acento = Paleta.OK;
        }

        // Tope de altura: el cartel no puede taparte media pantalla.
        while (lineas.size() > 5) {
            lineas.remove(lineas.size() - 1);
        }
    }

    private static void limpiar() {
        claveObjetivo = "";
        titulo = "";
        subtitulo = "";
        lineas.clear();
    }

    /** Texto de estado para la pantalla de ajustes. */
    public static String estado() {
        if (!activo()) {
            return "Apagado. Prendelo y mirá cualquier bloque o criatura: te dice de que mod es "
                    + "y si ese mod tiene problemas detectados.";
        }
        return "Activo. Mirá un bloque o una criatura y aparece el cartel abajo. "
                + "Se puede prender y apagar con la tecla de Faro-Vision.";
    }

    /** Lo que se muestra en el reporte exportado, sin colores. */
    public static String descripcionObjetivo() {
        if (titulo.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(titulo).append("  (").append(subtitulo).append(")");
        for (Linea l : lineas) {
            sb.append("\n  ").append(l.texto());
        }
        return sb.toString().toLowerCase(Locale.ROOT).isEmpty() ? "" : sb.toString();
    }
}
