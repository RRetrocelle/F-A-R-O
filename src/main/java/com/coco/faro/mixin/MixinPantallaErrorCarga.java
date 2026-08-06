package com.coco.faro.mixin;

import com.coco.faro.client.PantallaRescate;
import com.coco.faro.client.RescateArranque;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.LoadingErrorScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suma un boton de Faro a la pantalla nativa "Error loading mods" de Forge.
 *
 * Por que tiene que ser un Mixin: cuando la resolucion de dependencias falla,
 * Forge aborta ANTES de construir un solo mod. La clase @Mod nunca se instancia
 * y ningun @EventBusSubscriber llega a registrarse, asi que por la via normal es
 * imposible poner un boton ahi. Los mixins se aplican al cargar clases, mucho
 * antes.
 *
 * El boton se AGREGA: los tres de Forge (Open Mods Folder, Open latest.log,
 * Open crash report) quedan intactos. Se busca la fila donde estan y se coloca
 * uno nuevo debajo, centrado, para no pisar nada aunque Forge cambie el layout.
 *
 * Salvaguardas: require = 0 en el @Inject y todo el cuerpo en try/catch. Si algo
 * falla, se saltea en silencio y el usuario ve la pantalla de Forge tal cual. Un
 * mod de diagnostico jamas debe ser el que rompe el arranque.
 */
@Mixin(LoadingErrorScreen.class)
public abstract class MixinPantallaErrorCarga extends Screen {

    protected MixinPantallaErrorCarga(Component titulo) {
        super(titulo);
    }

    @Inject(method = "init", at = @At("RETURN"), require = 0)
    private void faro$agregarBotonRescate(CallbackInfo ci) {
        try {
            Screen esta = this;

            int ancho = 210;
            int x = this.width / 2 - ancho / 2;
            int y = faro$yDebajoDeLosBotonesDeForge();

            this.addRenderableWidget(Button.builder(
                            Component.literal("Faro: revisar y resolver"),
                            b -> Minecraft.getInstance().setScreen(
                                    new PantallaRescate(esta, RescateArranque.problemas())))
                    .bounds(x, y, ancho, 20)
                    .build());
        } catch (Throwable ignored) {
            // La pantalla de error de Forge importa mas que nuestro boton.
        }
    }

    /**
     * Ubica la fila mas baja de botones existentes y devuelve la Y justo debajo.
     *
     * Se calcula en vez de hardcodear una posicion porque el layout de esa
     * pantalla cambio entre versiones de Forge; asi el boton no se superpone
     * aunque los tres originales esten en otro lado.
     */
    private int faro$yDebajoDeLosBotonesDeForge() {
        int masAbajo = -1;
        for (Renderable r : this.renderables) {
            if (r instanceof AbstractWidget w) {
                masAbajo = Math.max(masAbajo, w.getY() + w.getHeight());
            }
        }
        int propuesta = (masAbajo > 0) ? masAbajo + 4 : this.height - 48;
        // Nunca fuera de pantalla.
        return Math.min(propuesta, this.height - 24);
    }
}
