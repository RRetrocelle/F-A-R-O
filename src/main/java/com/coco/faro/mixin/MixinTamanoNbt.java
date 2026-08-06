package com.coco.faro.mixin;

import com.coco.faro.diag.MonitorRed;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mide cuanto pesan los datos NBT que viajan por la red.
 *
 * Que es un NBT en este contexto: la estructura de datos con la que Minecraft
 * manda cosas complejas — el contenido de un cofre, los atributos de un item, el
 * estado de una maquina de un mod. Cuando un mod guarda demasiado ahi, el
 * sintoma es un tiron al abrir un contenedor o al acercarse a una base, y en el
 * log no aparece absolutamente nada.
 *
 * Como se mide: {@link FriendlyByteBuf} es el buffer por el que pasa todo el
 * trafico del protocolo. Al escribir o leer un NBT, la diferencia del indice del
 * buffer antes y despues ES el tamano exacto en bytes. No es una estimacion ni
 * un promedio: es el numero que efectivamente viajo.
 *
 * Se usa {@code ThreadLocal} para la marca porque Netty procesa paquetes en
 * varios hilos a la vez y un campo compartido mezclaria mediciones.
 *
 * Costo: dos lecturas de un entero por operacion de NBT. Es lo mas barato que se
 * puede instrumentar; aun asi solo cuenta cuando la medicion esta activada.
 */
@Mixin(FriendlyByteBuf.class)
public abstract class MixinTamanoNbt {

    private static final ThreadLocal<Integer> faro$indiceEscritura = new ThreadLocal<>();
    private static final ThreadLocal<Integer> faro$indiceLectura = new ThreadLocal<>();

    // ------------------------------------------------------------ escritura

    @Inject(method = "writeNbt(Lnet/minecraft/nbt/Tag;)Lnet/minecraft/network/FriendlyByteBuf;",
            at = @At("HEAD"), require = 0)
    private void faro$antesDeEscribir(Tag tag, CallbackInfoReturnable<FriendlyByteBuf> ci) {
        if (!MonitorRed.midiendoNbt()) {
            return;
        }
        try {
            faro$indiceEscritura.set(((FriendlyByteBuf) (Object) this).writerIndex());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "writeNbt(Lnet/minecraft/nbt/Tag;)Lnet/minecraft/network/FriendlyByteBuf;",
            at = @At("RETURN"), require = 0)
    private void faro$despuesDeEscribir(Tag tag, CallbackInfoReturnable<FriendlyByteBuf> ci) {
        Integer antes = faro$indiceEscritura.get();
        if (antes == null) {
            return;
        }
        faro$indiceEscritura.remove();
        try {
            int bytes = ((FriendlyByteBuf) (Object) this).writerIndex() - antes;
            MonitorRed.registrarNbt(bytes, "enviado — " + faro$describir(tag));
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------- lectura

    @Inject(method = "readNbt()Lnet/minecraft/nbt/CompoundTag;", at = @At("HEAD"), require = 0)
    private void faro$antesDeLeer(CallbackInfoReturnable<CompoundTag> ci) {
        if (!MonitorRed.midiendoNbt()) {
            return;
        }
        try {
            faro$indiceLectura.set(((FriendlyByteBuf) (Object) this).readerIndex());
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "readNbt()Lnet/minecraft/nbt/CompoundTag;", at = @At("RETURN"), require = 0)
    private void faro$despuesDeLeer(CallbackInfoReturnable<CompoundTag> ci) {
        Integer antes = faro$indiceLectura.get();
        if (antes == null) {
            return;
        }
        faro$indiceLectura.remove();
        try {
            int bytes = ((FriendlyByteBuf) (Object) this).readerIndex() - antes;
            MonitorRed.registrarNbt(bytes, "recibido — " + faro$describir(ci.getReturnValue()));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Describe un NBT sin recorrerlo entero.
     *
     * Se listan solo las claves del primer nivel, y como maximo cuatro. Recorrer
     * un NBT grande para describirlo costaria mas que el problema que estamos
     * midiendo, y las claves de arriba ya suelen delatar de que mod es
     * ("BlockEntityTag", "createGoggles", "curios:...").
     */
    private static String faro$describir(Tag tag) {
        if (tag == null) {
            return "vacio";
        }
        if (!(tag instanceof CompoundTag c)) {
            return tag.getType().getName();
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String clave : c.getAllKeys()) {
            if (n++ > 0) {
                sb.append(", ");
            }
            sb.append(clave);
            if (n >= 4) {
                sb.append(", ...");
                break;
            }
        }
        return sb.length() == 0 ? "compuesto vacio" : sb.toString();
    }
}
