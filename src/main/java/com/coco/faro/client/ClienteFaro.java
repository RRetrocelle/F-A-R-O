package com.coco.faro.client;

import com.coco.faro.Faro;
import com.coco.faro.config.ConfigFaro;
import com.coco.faro.diag.MotorDiagnostico;
import com.coco.faro.diag.Severidad;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

/** Todo el cableado del lado cliente: teclas, overlay, botones y medicion de ticks. */
public final class ClienteFaro {

    public static final KeyMapping TECLA_ABRIR = new KeyMapping(
            "key.faro.abrir", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F6, "key.categories.faro");

    public static final KeyMapping TECLA_OVERLAY = new KeyMapping(
            "key.faro.overlay", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F7, "key.categories.faro");

    /**
     * Libera el cursor sin abrir ningun menu, para poder clickear el "Leer mas"
     * de una notificacion.
     *
     * Hace falta porque en pleno juego el mouse esta capturado por Minecraft: no
     * hay cursor con el que apuntarle a nada del HUD. Es configurable desde
     * Opciones > Controles como cualquier otra tecla.
     */
    public static final KeyMapping TECLA_LIBERAR_MOUSE = new KeyMapping(
            "key.faro.liberar_mouse", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT, "key.categories.faro");

    /** true mientras el cursor esta liberado por Faro. */
    private static boolean mouseLiberado = false;

    public static boolean mouseLiberado() {
        return mouseLiberado;
    }

    private ClienteFaro() {
    }

    // ------------------------------------------------------------- bus del MOD

    @Mod.EventBusSubscriber(modid = Faro.MOD_ID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class BusMod {

        @SubscribeEvent
        public static void alConfigurarCliente(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                MotorDiagnostico motor = MotorDiagnostico.get();
                if (motor == null) {
                    return;
                }
                motor.vigilante().iniciar(ConfigFaro.INSTANCIA.intervaloVigilanciaMs.get());
                motor.analizarEnSegundoPlano();
                // El muestreo de CPU tiene que correr a intervalo fijo en su
                // propio hilo, nunca desde el render: ver MonitorHardware.
                com.coco.faro.diag.MonitorHardware.get().iniciarMuestreo();
                Faro.LOG.info("[Faro] Vigilancia de log iniciada y analisis lanzado.");
            });
        }

        @SubscribeEvent
        public static void alRegistrarTeclas(RegisterKeyMappingsEvent event) {
            event.register(TECLA_ABRIR);
            event.register(TECLA_OVERLAY);
            event.register(TECLA_LIBERAR_MOUSE);
        }

        @SubscribeEvent
        public static void alRegistrarOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("faro_aviso", OverlayFaro.INSTANCIA);
            event.registerAboveAll("faro_ticks", OverlayTicks.INSTANCIA);
        }
    }

    // ----------------------------------------------------------- bus de FORGE

    @Mod.EventBusSubscriber(modid = Faro.MOD_ID, value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class BusForge {

        @SubscribeEvent
        public static void alAbrirPantalla(ScreenEvent.Init.Post event) {
            if (!ConfigFaro.INSTANCIA.botonEnMenuPrincipal.get()) {
                return;
            }
            Screen pantalla = event.getScreen();

            if (pantalla instanceof TitleScreen || pantalla instanceof PauseScreen) {
                int[] pos = faroPosicionJuntoAlMenu(pantalla);
                event.addListener(new BotonIconoFaro(pos[0], pos[1], nivelAlerta(),
                        b -> Minecraft.getInstance().setScreen(new PantallaFaro(pantalla)),
                        textoTooltip()));
            }
        }

        /** 0 = todo bien, 1 = algo importante, 2 = algo critico. */
        private static int nivelAlerta() {
            MotorDiagnostico motor = MotorDiagnostico.get();
            if (motor == null || !motor.listo()) {
                return 0;
            }
            if (motor.problemas().stream().anyMatch(p -> p.severidad() == Severidad.CRITICA)) {
                return 2;
            }
            boolean alto = motor.problemas().stream()
                    .anyMatch(p -> p.severidad() == Severidad.ALTA);
            return (alto || motor.diagnostico().map(d -> d.huboCrash()).orElse(false)) ? 1 : 0;
        }

        private static String textoTooltip() {
            MotorDiagnostico motor = MotorDiagnostico.get();
            if (motor == null) {
                return "Faro — diagnostico del modpack";
            }
            return "Faro — " + motor.titular();
        }

        /**
         * Ubica el boton a la derecha de la columna central de botones del menu.
         *
         * Se calcula a partir de los botones que ya existen en la pantalla en vez
         * de hardcodear coordenadas: el layout del menu principal cambia entre
         * versiones de Minecraft y de Forge, y ademas Forge inserta su boton
         * "Mods" corriendo todo hacia abajo. Midiendo lo que hay, el boton queda
         * alineado con la fila mas baja y nunca encima de otro.
         *
         * @return {x, y, ancho}
         */
        private static int[] faroPosicionJuntoAlMenu(Screen pantalla) {
            int columnaX = pantalla.width / 2 - 100;
            int derechaColumna = pantalla.width / 2 + 100;
            int yMasBajo = -1;
            int yFilaMods = -1;

            for (Renderable r : pantalla.renderables) {
                if (!(r instanceof AbstractWidget w) || w.getHeight() > 22) {
                    continue;
                }
                // Solo la columna central: descarta los iconos de idioma y
                // accesibilidad, que estan a los costados.
                if (w.getX() < columnaX - 8 || w.getX() > derechaColumna) {
                    continue;
                }
                yMasBajo = Math.max(yMasBajo, w.getY());

                // Se busca la fila de "Mods" por su texto, para poder ponerse al
                // lado. Se compara en minusculas y por prefijo porque Forge la
                // traduce segun el idioma del juego.
                String texto = w.getMessage().getString().toLowerCase();
                if (texto.startsWith("mod")) {
                    yFilaMods = w.getY();
                }
            }

            // Boton cuadrado de 20x20, como el selector de idioma, pero a la
            // izquierda de la fila de "Mods": es el lugar que pidio el usuario y
            // queda simetrico con el icono de idioma de la fila de abajo.
            int lado = 20;
            int y = (yFilaMods > 0) ? yFilaMods
                    : (yMasBajo > 0 ? yMasBajo - 24 : pantalla.height / 4 + 108);
            int x = columnaX - lado - 4;

            // Si no entra a la izquierda (ventana angosta), va a la derecha.
            if (x < 4) {
                x = derechaColumna + 4;
            }
            // Y si tampoco entra ahi (ventana muy angosta), al borde inferior.
            if (x < 4) {
                x = 4;
                y = pantalla.height - lado - 4;
            }
            return new int[]{x, y, lado};
        }

        /**
         * Click sobre "Leer mas" de una notificacion.
         *
         * El HUD no recibe clicks por si mismo, asi que se escucha el boton del
         * mouse cuando no hay ninguna pantalla abierta. Abrir la pantalla de Faro
         * pausa el juego solo en un mundo local, que es como funciona cualquier
         * menu de Minecraft.
         */
        @SubscribeEvent
        public static void alClickearMouse(InputEvent.MouseButton.Pre event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null || event.getAction() != 1 || event.getButton() != 0) {
                return;
            }
            if (!NotificacionLogro.hayAvisoVisible()) {
                return;
            }
            double escala = mc.getWindow().getGuiScale();
            double mx = mc.mouseHandler.xpos() / escala;
            double my = mc.mouseHandler.ypos() / escala;

            NotificacionLogro.Destino destino = NotificacionLogro.clickEnLeerMas(mx, my);
            if (destino != null) {
                mc.setScreen(new PantallaFaro(null, destino));
            }
        }

        @SubscribeEvent
        public static void alRegistrarComandos(RegisterClientCommandsEvent event) {
            ComandoFaro.registrar(event.getDispatcher());
        }

        @SubscribeEvent
        public static void alTickCliente(TickEvent.ClientTickEvent event) {
            try {
                tickInterno(event);
            } catch (Throwable t) {
                // El tick del cliente corre 20 veces por segundo: una excepcion
                // aca tumbaria el juego entero. Faro no puede ser esa causa.
                Faro.LOG.error("[Faro] Fallo en el tick del cliente", t);
            }
        }

        private static void tickInterno(TickEvent.ClientTickEvent event) {
            MotorDiagnostico motor = MotorDiagnostico.get();
            Minecraft mc = Minecraft.getInstance();

            // Medicion del tick: arrancamos en START y cerramos en END.
            if (motor != null) {
                if (event.phase == TickEvent.Phase.START) {
                    motor.rendimiento().marcarInicioTick();
                    return;
                }
                motor.rendimiento().marcarFinTick(contexto(mc));
            }

            if (event.phase != TickEvent.Phase.END) {
                return;
            }

            revisarAvisos(motor);

            // Liberar / recapturar el cursor sin abrir ningun menu.
            while (TECLA_LIBERAR_MOUSE.consumeClick()) {
                if (mc.screen != null || mc.player == null) {
                    continue;
                }
                mouseLiberado = !mouseLiberado;
                if (mouseLiberado) {
                    mc.mouseHandler.releaseMouse();
                    mc.player.displayClientMessage(Component.literal(
                            "[Faro] cursor libre — clickeá 'Leer mas', o volvé a apretar la tecla"),
                            true);
                } else {
                    mc.mouseHandler.grabMouse();
                }
            }

            // Si se cierra el aviso o se abre una pantalla, se recaptura solo:
            // dejar el cursor suelto sin motivo seria molesto.
            if (mouseLiberado && (mc.screen != null || !NotificacionLogro.hayAvisoVisible())) {
                mouseLiberado = false;
                if (mc.screen == null) {
                    mc.mouseHandler.grabMouse();
                }
            }

            while (TECLA_ABRIR.consumeClick()) {
                if (mc.screen == null) {
                    OverlayFaro.INSTANCIA.marcarComoVisto();
                    mc.setScreen(new PantallaFaro(null));
                }
            }

            while (TECLA_OVERLAY.consumeClick()) {
                boolean nuevo = !ConfigFaro.INSTANCIA.overlayActivo.get();
                ConfigFaro.INSTANCIA.overlayActivo.set(nuevo);
                ConfigFaro.INSTANCIA.overlayActivo.save();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal(
                            "[Faro] aviso en pantalla: " + (nuevo ? "activado" : "desactivado")), true);
                }
            }
        }

        private static int erroresYaAvisados = 0;
        private static int texturasYaAvisadas = 0;
        private static boolean avisoDeProblemasDado = false;
        private static int ticksDesdeUltimaRevision = 0;

        /**
         * Genera las notificaciones estilo logro.
         *
         * Se revisa una vez por segundo, no por tick: comparar contadores 20
         * veces por segundo no aporta nada y el mod no puede costar rendimiento.
         */
        private static void revisarAvisos(MotorDiagnostico motor) {
            if (motor == null || ++ticksDesdeUltimaRevision < 20) {
                return;
            }
            ticksDesdeUltimaRevision = 0;

            // Problemas de instalacion, una sola vez por sesion.
            if (!avisoDeProblemasDado && motor.listo()) {
                var serios = motor.problemasSerios();
                if (!serios.isEmpty()) {
                    avisoDeProblemasDado = true;
                    boolean critico = serios.stream()
                            .anyMatch(p -> p.severidad() == Severidad.CRITICA);
                    NotificacionLogro.mostrar(new NotificacionLogro.Aviso(
                            critico ? "Problemas criticos detectados" : "Problemas para revisar",
                            serios.size() + " en tu instalacion de mods",
                            critico ? Severidad.CRITICA : Severidad.ALTA,
                            NotificacionLogro.Destino.PROBLEMAS));
                }
            }

            // Errores nuevos en el log durante la partida.
            int errores = motor.vigilante().errores();
            if (errores > erroresYaAvisados + 4) {
                erroresYaAvisados = errores;
                String origen = motor.vigilante().origenMasRuidoso();
                NotificacionLogro.mostrar(new NotificacionLogro.Aviso(
                        "Errores nuevos en el log",
                        origen != null ? "el que mas reporta: " + origen : errores + " en total",
                        Severidad.ALTA,
                        NotificacionLogro.Destino.CRASH));
            }

            // Texturas faltantes: sintoma tipico de un mod incompatible.
            int texturas = motor.vigilante().texturasFaltantes();
            if (texturas > texturasYaAvisadas + 2) {
                texturasYaAvisadas = texturas;
                NotificacionLogro.mostrar(new NotificacionLogro.Aviso(
                        "Texturas o modelos faltantes",
                        texturas + " recursos que no cargaron",
                        Severidad.ALTA,
                        NotificacionLogro.Destino.CRASH));
            }
        }

        /** Etiqueta corta de que estaba pasando, para asociarla a un tiron. */
        private static String contexto(Minecraft mc) {
            if (mc.level == null) {
                return "en el menu";
            }
            if (mc.screen != null) {
                return "con una pantalla abierta";
            }
            return "en partida (" + mc.level.dimension().location().getPath() + ")";
        }
    }
}
