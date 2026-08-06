package com.coco.faro.client;

import com.coco.faro.config.ConfigFaro;
import com.coco.faro.diag.Severidad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * Alertas sonoras que se entienden sin mirar la pantalla.
 *
 * El razonamiento: una notificacion visual en la esquina se pierde. Estas
 * minando, mirando el inventario, o simplemente en otra parte de la pantalla, y
 * el aviso aparece y se va sin que lo hayas visto. Un sonido no.
 *
 * Criterio de eleccion de los sonidos — es la parte que hace la diferencia entre
 * util y molesto:
 *
 *   - Se usan sonidos del propio juego, no archivos nuevos. Suenan como
 *     Minecraft porque SON Minecraft, y no hay que descargar nada.
 *   - Cada gravedad tiene un sonido con la carga emocional que le corresponde.
 *     Un dato informativo no puede sonar como una alarma, o en dos dias ya nadie
 *     le presta atencion a ninguno.
 *   - Volumen bajo y tono fijo: no compiten con el juego, se reconocen.
 *   - Nunca dos seguidos: hay un tiempo minimo entre alertas, porque diez
 *     notificaciones encoladas sonando de corrido serian insoportables.
 *
 * Se reproducen en el mundo del jugador, asi que respetan el volumen general y
 * el de "amigables" del propio Minecraft. Bajarle el volumen al juego les baja
 * el volumen a estas tambien, que es lo esperable.
 */
public final class AlertasSonoras {

    /** Tiempo minimo entre dos alertas. */
    private static final long MINIMO_ENTRE_ALERTAS_MS = 3000L;

    private static long ultimaAlerta = 0L;

    private AlertasSonoras() {
    }

    public static boolean activas() {
        try {
            return ConfigFaro.INSTANCIA.alertasSonoras.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Suena la alerta que corresponde a esa gravedad. */
    public static void alerta(Severidad severidad) {
        if (!activas()) {
            return;
        }
        long ahora = System.currentTimeMillis();
        if (ahora - ultimaAlerta < MINIMO_ENTRE_ALERTAS_MS) {
            return;
        }
        ultimaAlerta = ahora;

        switch (severidad) {
            // Grave: el sonido de una campana rota. Es inconfundible y suena mal
            // a proposito — algo se rompio y hay que mirarlo.
            case CRITICA -> reproducir(SoundEvents.ANVIL_LAND, 0.55f, 0.6f);

            // Importante: el "ding" del cofre de shulker al cerrarse, en tono
            // grave. Llama la atencion sin sonar a catastrofe.
            case ALTA -> reproducir(SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), 0.5f, 0.7f);

            // Aviso: nota corta y neutra.
            case MEDIA -> reproducir(SoundEvents.NOTE_BLOCK_BELL.value(), 0.35f, 1.2f);

            // Informativo: el mismo sonido que usa el juego al completar un
            // avance. Ya significa "algo termino bien" en la cabeza del jugador.
            case INFO -> reproducir(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.25f, 1.6f);
        }
    }

    /** Sonido de "tarea terminada", para cuando una operacion larga finaliza. */
    public static void listo() {
        if (!activas()) {
            return;
        }
        reproducir(SoundEvents.PLAYER_LEVELUP, 0.35f, 1.4f);
    }

    /** Sonido de "no se pudo", para un fallo de una accion del usuario. */
    public static void fallo() {
        if (!activas()) {
            return;
        }
        reproducir(SoundEvents.ITEM_BREAK, 0.4f, 0.9f);
    }

    /**
     * Sonido del benchmark al terminar la cuenta regresiva.
     *
     * Se separa del resto porque tiene que sonar SIEMPRE, aunque las alertas
     * esten apagadas: durante un benchmark la pantalla esta girando y el usuario
     * no la puede mirar, asi que el sonido es la unica senal de que ya termino.
     */
    public static void benchmarkTerminado() {
        reproducir(SoundEvents.NOTE_BLOCK_CHIME.value(), 0.6f, 1.5f);
    }

    private static void reproducir(SoundEvent sonido, float volumen, float tono) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getSoundManager() == null) {
                return;
            }
            mc.getSoundManager().play(SimpleSoundInstance.forUI(sonido, tono, volumen));
        } catch (Throwable ignored) {
            // Un sonido que no se puede reproducir no es motivo para nada mas.
        }
    }
}
