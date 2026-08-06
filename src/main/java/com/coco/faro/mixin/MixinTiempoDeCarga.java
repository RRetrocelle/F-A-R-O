package com.coco.faro.mixin;

import com.coco.faro.diag.PerfilCarga;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.ModContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cronometra cuanto tarda cada mod en procesar los eventos del ciclo de vida.
 *
 * Forge le entrega cada evento de carga (construccion, setup comun, setup de
 * cliente, InterModComms, load complete) a cada mod llamando
 * {@code ModContainer.acceptEvent}. Rodear esa llamada da el tiempo REAL que se
 * pasa adentro del codigo de ese mod, sin estimaciones ni promedios.
 *
 * Por que aca y no en un evento de Forge: no existe ningun evento que avise
 * "empezo a cargar el mod X". La informacion solo esta en este punto.
 *
 * Costo: dos llamadas a nanoTime por evento y por mod. Con 190 mods y 5 eventos
 * son unas 1900 llamadas en todo el arranque. Es despreciable, y ademas solo
 * ocurre durante la carga: cuando el juego esta andando, esto no se ejecuta mas.
 *
 * Salvaguardas: require = 0, y el registro esta envuelto en try/catch. Si Forge
 * cambia la firma del metodo, el mixin no aplica y la pantalla de carga de mods
 * simplemente dice que no hay datos.
 */
@Mixin(value = ModContainer.class, remap = false)
public abstract class MixinTiempoDeCarga {

    /**
     * Marca de inicio por hilo.
     *
     * Forge despacha los eventos de carga EN PARALELO entre mods. Un campo comun
     * mezclaria los tiempos de dos mods cargando a la vez y daria numeros sin
     * sentido. Con ThreadLocal cada hilo lleva su propia marca.
     */
    private static final ThreadLocal<Long> faro$inicio = new ThreadLocal<>();

    @Inject(method = "acceptEvent(Lnet/minecraftforge/eventbus/api/Event;)V",
            at = @At("HEAD"), require = 0)
    private void faro$antesDelEvento(Event evento, CallbackInfo ci) {
        faro$inicio.set(System.nanoTime());
    }

    @Inject(method = "acceptEvent(Lnet/minecraftforge/eventbus/api/Event;)V",
            at = @At("RETURN"), require = 0)
    private void faro$despuesDelEvento(Event evento, CallbackInfo ci) {
        try {
            Long t0 = faro$inicio.get();
            if (t0 == null) {
                return;
            }
            faro$inicio.remove();
            ModContainer contenedor = (ModContainer) (Object) this;
            PerfilCarga.registrar(contenedor.getModId(), System.nanoTime() - t0);
        } catch (Throwable ignored) {
            // Medir la carga jamas puede impedir la carga.
        }
    }
}
