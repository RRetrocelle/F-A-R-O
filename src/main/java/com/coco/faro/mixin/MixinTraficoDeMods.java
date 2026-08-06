package com.coco.faro.mixin;

import com.coco.faro.diag.MonitorRed;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cuenta el trafico de red que genera cada mod.
 *
 * Por que se puede atribuir a un mod sin ambiguedad: los mods no pueden inventar
 * paquetes del protocolo de Minecraft. Para mandar datos propios usan el paquete
 * de "carga util personalizada", que lleva un identificador con formato
 * {@code modid:canal}. Ese namespace ES el modId, puesto por el propio mod. No
 * hay heuristica de por medio.
 *
 * Se engancha el constructor de LECTURA, que es el que corre cuando llega un
 * paquete del servidor. Ahi el buffer ya tiene el contenido y
 * {@code readableBytes()} da el tamano exacto de la carga util.
 *
 * Que NO mide: los paquetes del protocolo vanilla (movimiento, bloques, chunks).
 * Esos no son de ningun mod en particular. Un mod que hace que el servidor mande
 * mas actualizaciones de bloques no aparece aca — y eso se dice en la pantalla,
 * porque es una limitacion real del metodo.
 *
 * Tampoco baja el ping. La latencia depende de la ruta de red entre tu PC y el
 * servidor; ningun mod del cliente la cambia. Lo que si detecta es al mod que
 * inunda la conexion, que es una causa de tirones que se confunde seguido con
 * "mala conexion".
 */
@Mixin(ClientboundCustomPayloadPacket.class)
public abstract class MixinTraficoDeMods {

    @Inject(method = "<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At("RETURN"), require = 0)
    private void faro$alRecibir(FriendlyByteBuf buffer, CallbackInfo ci) {
        try {
            ClientboundCustomPayloadPacket paquete = (ClientboundCustomPayloadPacket) (Object) this;
            var id = paquete.getIdentifier();
            var datos = paquete.getData();
            if (id == null || datos == null) {
                return;
            }
            MonitorRed.registrarPaquete(id.toString(), datos.readableBytes());
        } catch (Throwable ignored) {
            // Contar trafico nunca puede romper la recepcion de un paquete.
        }
    }
}
