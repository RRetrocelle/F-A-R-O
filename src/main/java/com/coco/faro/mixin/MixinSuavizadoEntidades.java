package com.coco.faro.mixin;

import com.coco.faro.client.SuavizadoEntidades;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Suavizado visual de entidades para disimular la latencia.
 *
 * Que problema ataca, con precision: con ping alto, las posiciones de las otras
 * entidades llegan espaciadas. El cliente interpola entre la ultima posicion
 * conocida y la nueva en una cantidad fija de pasos; si el paquete siguiente
 * tarda mas que esos pasos, la entidad llega a destino, se queda quieta, y de
 * golpe salta al recibir la actualizacion. Ese es el "teletransporte" tipico de
 * los mobs y jugadores en un server lejano.
 *
 * Que hace este mixin: aumenta la cantidad de pasos de interpolacion en
 * proporcion al ping medido. Con mas pasos, el movimiento se reparte a lo largo
 * del intervalo real entre paquetes y el salto desaparece.
 *
 * Lo que hay que decir de frente, porque es la parte honesta:
 *
 *   - NO baja el ping. Nada del lado del cliente puede: la latencia la define la
 *     ruta de red. Esto es puramente cosmetico.
 *   - Cambia una compensacion por otra. La entidad se ve mas fluida pero queda un
 *     poco mas atrasada respecto de donde esta realmente en el servidor. En PvP
 *     eso importa y puede jugarte en contra; en supervivencia cooperativa no.
 *   - Por eso viene APAGADO por defecto y el ajuste lo explica con estas mismas
 *     palabras. No se prende solo.
 *
 * Se limita al jugador propio quedando fuera: la entidad del jugador local no
 * usa esta ruta, asi que tu propio movimiento no se toca nunca.
 */
@Mixin(Entity.class)
public abstract class MixinSuavizadoEntidades {

    /**
     * Ajusta la cantidad de pasos de interpolacion.
     *
     * El indice 9 es el parametro {@code steps} de
     * {@code lerpTo(double, double, double, float, float, int, boolean)}: this=0,
     * los tres double ocupan 1-6, los dos float 7 y 8, y el int cae en 9.
     */
    @ModifyVariable(method = "lerpTo(DDDFFIZ)V", at = @At("HEAD"),
            index = 9, argsOnly = true, require = 0)
    private int faro$masPasosDeInterpolacion(int pasos) {
        try {
            return SuavizadoEntidades.ajustar(pasos);
        } catch (Throwable t) {
            return pasos;
        }
    }
}
