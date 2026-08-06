package com.coco.faro.client;

import com.coco.faro.config.ConfigFaro;
import net.minecraft.client.Minecraft;

/**
 * La logica del suavizado de entidades, fuera del mixin.
 *
 * El mixin queda como una linea que delega aca. Asi la regla se puede leer, y
 * cambiar, sin tocar codigo de instrumentacion — que es lo mas delicado del mod.
 *
 * La regla: se estima cuantos ticks pasan realmente entre dos actualizaciones de
 * posicion segun el ping, y se usa ese numero como cantidad de pasos, en vez del
 * valor fijo que manda el servidor.
 *
 *   ping 50 ms  -> 1 tick de retraso   -> sin cambio
 *   ping 150 ms -> 3 ticks             -> 3 pasos
 *   ping 300 ms -> 6 ticks             -> 6 pasos
 *
 * Con tope, porque pasado cierto punto el suavizado deja de disimular y empieza a
 * mentir: la entidad se veria deslizandose suavemente hacia un lugar donde ya no
 * esta hace medio segundo.
 */
public final class SuavizadoEntidades {

    /** Mas alla de esto el retraso visual se nota mas que el salto que corrige. */
    private static final int PASOS_MAXIMOS = 8;

    /** Cada cuanto se relee el ping. No hace falta por entidad y por tick. */
    private static final long INTERVALO_PING_MS = 2000L;

    private static volatile int pingCacheado = -1;
    private static volatile long ultimaLectura = 0L;

    private SuavizadoEntidades() {
    }

    public static boolean activo() {
        try {
            return ConfigFaro.INSTANCIA.suavizarEntidades.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Devuelve cuantos pasos de interpolacion usar.
     *
     * Nunca devuelve menos que lo que pidio el servidor: el suavizado solo puede
     * agregar fluidez, no quitarla. Si el ping es bajo o no se puede leer, se
     * respeta el valor original sin tocar nada.
     */
    public static int ajustar(int pasosOriginales) {
        if (!activo()) {
            return pasosOriginales;
        }
        int ping = ping();
        if (ping < 80) {
            // Por debajo de esto el salto no se percibe y suavizar solo agrega retraso.
            return pasosOriginales;
        }
        // Un tick son 50 ms. El retraso en ticks es el ping partido eso.
        int ticksDeRetraso = Math.round(ping / 50f);
        int propuesta = Math.min(PASOS_MAXIMOS, Math.max(pasosOriginales, ticksDeRetraso));
        return propuesta;
    }

    /** Ping actual del jugador, cacheado. -1 si no se puede leer. */
    public static int ping() {
        long ahora = System.currentTimeMillis();
        if (ahora - ultimaLectura < INTERVALO_PING_MS) {
            return pingCacheado;
        }
        ultimaLectura = ahora;
        pingCacheado = leerPing();
        return pingCacheado;
    }

    private static int leerPing() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() == null || mc.player == null || mc.isLocalServer()) {
                return -1;
            }
            var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            return info == null ? -1 : info.getLatency();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Estado en una linea, para los ajustes y el HUD. */
    public static String estado() {
        if (!activo()) {
            return "Apagado. Sirve solo en servidores con ping alto, y a cambio de que las "
                    + "entidades queden un poco mas atrasadas.";
        }
        int p = ping();
        if (p < 0) {
            return "Activo, pero no hay ping que leer: estas en un mundo local o sin conexion. "
                    + "Aca no cambia nada.";
        }
        if (p < 80) {
            return "Activo. Con " + p + " ms de ping no hace falta suavizar nada, asi que no "
                    + "esta interviniendo.";
        }
        int pasos = Math.min(PASOS_MAXIMOS, Math.round(p / 50f));
        return "Activo. Con " + p + " ms de ping esta interpolando en " + pasos + " pasos. "
                + "Las entidades se ven fluidas pero quedan hasta " + (pasos * 50)
                + " ms atrasadas respecto del servidor.";
    }
}
