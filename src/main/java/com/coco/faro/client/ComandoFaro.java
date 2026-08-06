package com.coco.faro.client;

import com.coco.faro.diag.Diagnostico;
import com.coco.faro.diag.MonitorRendimiento;
import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.diag.Problema;
import com.coco.faro.diag.Severidad;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Comando /faro scan: resumen rapido de rendimiento por chat, sin abrir la interfaz.
 *
 * Se registra como comando de CLIENTE ({@code RegisterClientCommandsEvent}), no de
 * servidor. Eso significa que funciona en un mundo local y tambien conectado a un
 * servidor sin que el servidor tenga el mod, y que la respuesta la ve solo quien
 * lo escribio: nadie mas recibe el spam.
 */
public final class ComandoFaro {

    private ComandoFaro() {
    }

    public static void registrar(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("faro")
                .then(Commands.literal("scan").executes(ctx -> {
                    escanear(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("conflictos").executes(ctx -> {
                    conflictos(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("red").executes(ctx -> {
                    red(ctx.getSource());
                    return 1;
                }))
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "Usá /faro scan para un resumen rapido, o el boton Faro del menu "
                                    + "para el diagnostico completo.").withStyle(ChatFormatting.GRAY),
                            false);
                    return 1;
                }));
    }

    /**
     * /faro conflictos — lista los conflictos por chat.
     *
     * Existe para el caso en que la notificacion no se vea, no se llegue a
     * clickear, o el usuario prefiera leerlo sin abrir la interfaz.
     */
    private static void conflictos(CommandSourceStack fuente) {
        MotorDiagnostico motor = MotorDiagnostico.get();
        linea(fuente, "── Faro · conflictos entre mods ──", ChatFormatting.GOLD);

        if (motor == null || !motor.listo()) {
            linea(fuente, "El analisis todavia esta corriendo.", ChatFormatting.GRAY);
            return;
        }

        List<Problema> lista = motor.problemas().stream()
                .filter(p -> p.categoria() == Problema.Categoria.CONFLICTO_DECLARADO
                        || p.categoria() == Problema.Categoria.POSIBLE_SOLAPAMIENTO)
                .toList();

        if (lista.isEmpty()) {
            linea(fuente, "No detecte conflictos entre los mods instalados.",
                    ChatFormatting.GREEN);
            return;
        }

        for (Problema p : lista) {
            boolean declarado = p.categoria() == Problema.Categoria.CONFLICTO_DECLARADO;
            // Se distingue siempre lo confirmado de lo probable: son cosas
            // distintas y mezclarlas haria que el usuario desconfie de ambas.
            linea(fuente, (declarado ? "[CONFIRMADO] " : "[POSIBLE] ") + p.titulo(),
                    declarado ? ChatFormatting.RED : ChatFormatting.YELLOW);
            linea(fuente, "   " + p.detalle(), ChatFormatting.GRAY);
            linea(fuente, "   " + p.sugerencia(), ChatFormatting.AQUA);
        }
        linea(fuente, lista.size() + " en total. Mira la pestana Problemas para resolverlos.",
                ChatFormatting.GOLD);
    }

    /**
     * /faro red — numeros reales de latencia y trafico.
     *
     * Aclaracion que va en el propio comando: un mod cliente NO puede bajar el
     * ping. La latencia la define la ruta de red entre tu PC y el servidor.
     * Lo util de verdad es saber si el problema es de red o es rendimiento local
     * mal atribuido, y para eso alcanza con mostrar lo que Minecraft ya mide.
     */
    private static void red(CommandSourceStack fuente) {
        Minecraft mc = Minecraft.getInstance();
        linea(fuente, "── Faro · red ──", ChatFormatting.GOLD);

        if (mc.getConnection() == null) {
            linea(fuente, "No estas conectado a nada.", ChatFormatting.GRAY);
            return;
        }
        if (mc.isLocalServer()) {
            linea(fuente, "Estas en un mundo local: no hay red de por medio.",
                    ChatFormatting.GRAY);
            linea(fuente, "Si va lento, el problema es de rendimiento, no de conexion. "
                    + "Mira /faro scan.", ChatFormatting.AQUA);
            return;
        }

        int ping = -1;
        try {
            var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) {
                ping = info.getLatency();
            }
        } catch (Throwable ignored) {
        }

        if (ping >= 0) {
            ChatFormatting c = ping > 250 ? ChatFormatting.RED
                    : (ping > 120 ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
            linea(fuente, "Latencia: " + ping + " ms", c);
            linea(fuente, ping > 250
                            ? "Es alta. El retraso que sentis viene de la conexion."
                            : ping > 120
                            ? "Aceptable. Se puede notar algo de retraso."
                            : "Buena. Si el juego va lento, no es la red.",
                    ChatFormatting.GRAY);
        } else {
            linea(fuente, "Latencia: sin dato todavia.", ChatFormatting.GRAY);
        }

        linea(fuente, "Faro no puede bajar el ping: eso lo define la ruta entre tu PC y "
                + "el servidor, y ningun mod cliente la cambia.", ChatFormatting.DARK_GRAY);
    }

    private static void escanear(CommandSourceStack fuente) {
        MotorDiagnostico motor = MotorDiagnostico.get();

        linea(fuente, "── Faro · chequeo rapido ──", ChatFormatting.GOLD);

        if (motor == null) {
            linea(fuente, "El motor de diagnostico no arranco.", ChatFormatting.RED);
            return;
        }

        // --- Rendimiento
        MonitorRendimiento r = motor.rendimiento();
        double p95 = r.p95Ms();
        double prom = r.promedioMs();

        if (r.totalTicks() < 20) {
            linea(fuente, "Tick: todavia sin datos suficientes, jugá un rato mas.",
                    ChatFormatting.GRAY);
        } else {
            ChatFormatting color = p95 >= 50 ? ChatFormatting.RED
                    : (p95 >= 40 ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
            linea(fuente, String.format("Tick: %.1f ms promedio, %.1f ms p95  (limite 50 ms)",
                    prom, p95), color);
            // TPS efectivo: mientras el tick entre en su presupuesto de 50 ms el
            // juego mantiene 20 por segundo. Recien cuando lo pasa, la frecuencia
            // real cae a 1000/duracion.
            double tps = (prom <= 50.0) ? 20.0 : 1000.0 / prom;
            linea(fuente, String.format("TPS efectivo: %.1f de 20", tps), color);
        }

        // --- Memoria
        int pct = MonitorRendimiento.porcentajeMemoria();
        ChatFormatting colorMem = pct > 90 ? ChatFormatting.RED
                : (pct > 75 ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
        linea(fuente, "Memoria: " + MonitorRendimiento.memoriaUsadaMB() + " / "
                + MonitorRendimiento.memoriaMaximaMB() + " MB (" + pct + "%)", colorMem);

        // --- Problemas
        if (!motor.listo()) {
            linea(fuente, "El analisis de mods todavia esta corriendo.", ChatFormatting.GRAY);
        } else {
            List<Problema> serios = motor.problemasSerios();
            if (serios.isEmpty()) {
                linea(fuente, "Sin problemas de instalacion detectados.", ChatFormatting.GREEN);
            } else {
                long criticos = serios.stream()
                        .filter(p -> p.severidad() == Severidad.CRITICA).count();
                linea(fuente, serios.size() + " problemas activos (" + criticos + " criticos)",
                        criticos > 0 ? ChatFormatting.RED : ChatFormatting.YELLOW);
                for (Problema p : serios.stream().limit(3).toList()) {
                    linea(fuente, "  · " + p.titulo(), ChatFormatting.GRAY);
                }
                if (serios.size() > 3) {
                    linea(fuente, "  (y " + (serios.size() - 3) + " mas, mirá la pestana Problemas)",
                            ChatFormatting.DARK_GRAY);
                }
            }

            motor.diagnostico().filter(Diagnostico::huboCrash).ifPresent(d ->
                    linea(fuente, "Ultimo crash: " + d.tipo().titulo()
                            + " (confianza " + d.confianza().etiqueta().toLowerCase() + ")",
                            ChatFormatting.YELLOW));
        }

        // --- Aclaracion de alcance en multijugador
        avisarSiEsMultijugador(fuente);
    }

    /**
     * Deja claro que estos numeros son del cliente.
     *
     * Faro mide el tick del lado del jugador. En un servidor, el tick del server
     * es otra cosa y no se puede leer desde aca; y atribuirle lag a un jugador
     * concreto directamente no se puede hacer con precision desde el cliente.
     * Antes que dar un numero por jugador que seria inventado, se dice que el
     * resumen es general y de donde sale.
     */
    private static void avisarSiEsMultijugador(CommandSourceStack fuente) {
        Minecraft mc = Minecraft.getInstance();
        boolean enServidor = mc.getCurrentServer() != null || !mc.isLocalServer();
        if (!enServidor) {
            return;
        }
        int jugadores = (mc.level != null) ? mc.level.players().size() : 1;
        if (jugadores <= 1) {
            return;
        }
        linea(fuente, "Nota: estos numeros son de TU cliente. El rendimiento del server "
                + "se mide aparte y no lo puedo leer desde aca.", ChatFormatting.DARK_GRAY);
    }

    private static void linea(CommandSourceStack fuente, String texto, ChatFormatting color) {
        fuente.sendSuccess(() -> Component.literal(texto).withStyle(color), false);
    }
}
