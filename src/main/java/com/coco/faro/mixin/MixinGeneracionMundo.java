package com.coco.faro.mixin;

import com.coco.faro.diag.MonitorWorldgen;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cronometra cada enganche de generacion de mundo.
 *
 * Todo lo que un mod agrega al terreno —arboles, minerales, lagos, estructuras
 * chicas— pasa por un "placed feature". Al generar un chunk, el juego recorre
 * todos los features del bioma y los coloca uno por uno. Rodear esa colocacion da
 * el costo real de cada uno, en el lugar exacto donde se paga.
 *
 * Y como el identificador del feature tiene formato {@code modid:nombre}, el
 * resultado dice literalmente cuanto cuesta cada mod al explorar. Eso convierte
 * "se traba cuando camino hacia terreno nuevo" en "el mod X se lleva 14 ms por
 * chunk".
 *
 * Aclaraciones de alcance:
 *   - En un mundo local esto mide la generacion del servidor integrado, que corre
 *     en la misma maquina. En un servidor remoto la generacion pasa alla y desde
 *     el cliente no se ve nada. La pantalla lo dice.
 *   - Solo cuenta cuando la medicion esta activada, porque son dos llamadas a
 *     nanoTime por feature y por chunk, y hay cientos de features por chunk.
 *
 * require = 0 y todo en try/catch: si Forge o Minecraft cambian esta ruta, el
 * mixin no aplica y la pantalla dice que no hay datos, sin romper nada.
 */
@Mixin(PlacedFeature.class)
public abstract class MixinGeneracionMundo {

    private static final ThreadLocal<Long> faro$inicio = new ThreadLocal<>();

    @Inject(method = "placeWithBiomeCheck", at = @At("HEAD"), require = 0)
    private void faro$antesDeColocar(WorldGenLevel nivel, ChunkGenerator generador,
                                     RandomSource aleatorio, BlockPos posicion,
                                     CallbackInfoReturnable<Boolean> ci) {
        if (!MonitorWorldgen.midiendo()) {
            return;
        }
        faro$inicio.set(System.nanoTime());
    }

    @Inject(method = "placeWithBiomeCheck", at = @At("RETURN"), require = 0)
    private void faro$despuesDeColocar(WorldGenLevel nivel, ChunkGenerator generador,
                                       RandomSource aleatorio, BlockPos posicion,
                                       CallbackInfoReturnable<Boolean> ci) {
        Long t0 = faro$inicio.get();
        if (t0 == null) {
            return;
        }
        faro$inicio.remove();
        try {
            MonitorWorldgen.registrarFeature(faro$identificar(), System.nanoTime() - t0);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Saca el identificador registrado del feature.
     *
     * Se pide la clave del Holder, que es la referencia al registro. Cuando el
     * feature es anonimo (definido en linea, sin registrar) no hay clave y se
     * agrupa bajo "sin_registrar" en vez de perderlo.
     */
    private String faro$identificar() {
        try {
            PlacedFeature propio = (PlacedFeature) (Object) this;
            return propio.feature().unwrapKey()
                    .map(clave -> clave.location().toString())
                    .orElse("sin_registrar:anonimo");
        } catch (Throwable t) {
            return "sin_registrar:anonimo";
        }
    }
}
