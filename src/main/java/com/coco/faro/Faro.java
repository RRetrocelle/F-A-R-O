package com.coco.faro;

import com.coco.faro.config.ConfigFaro;
import com.coco.faro.diag.MotorDiagnostico;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * Faro — companion de diagnostico para el modpack.
 *
 * Que hace, en concreto:
 *   - cuenta que mods cargaron y cuales estan en la carpeta pero no cargaron;
 *   - lee el crash report mas reciente y trata de explicar la causa;
 *   - vigila latest.log y avisa de errores nuevos con un overlay chico;
 *   - si (y solo si) identifico un culpable claro, ofrece desactivarlo moviendo
 *     su .jar a una subcarpeta, dejando todo anotado y reversible.
 *
 * Que NO hace, dicho de frente: no arregla mods rotos, no resuelve
 * incompatibilidades y no adivina causas cuando los datos no alcanzan. En esos
 * casos lo dice y te manda al log. Un boton de "reparar" que no repara nada
 * seria peor que no tener boton.
 */
@Mod(Faro.MOD_ID)
public class Faro {

    public static final String MOD_ID = "faro";
    public static final String NOMBRE = "Faro";
    public static final Logger LOG = LogUtils.getLogger();

    public Faro() {
        // Faro es puramente de diagnostico del cliente. En un servidor dedicado
        // se registra pero no monta nada de interfaz.
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ConfigFaro.SPEC, "faro-client.toml");

        if (FMLEnvironment.dist == Dist.CLIENT) {
            MotorDiagnostico motor = MotorDiagnostico.crear(FMLPaths.GAMEDIR.get());
            LOG.info("[Faro] Iniciado. Carpeta de juego: {}", motor.carpetaJuego());
        }
    }
}
