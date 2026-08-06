package com.coco.faro.client;

import com.coco.faro.diag.Biseccion;
import com.coco.faro.diag.MotorDiagnostico;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Asistente de biseccion: busqueda binaria del mod culpable.
 *
 * La pantalla hace una sola pregunta por vuelta —"¿el problema sigue?"— y se
 * encarga de todo lo demas: que mover, en que orden, arrastrar las dependencias,
 * y devolver todo al final.
 *
 * Se muestra siempre cuantos arranques faltan. Ese numero es lo que hace la
 * diferencia entre "esto es interminable" y "son ocho reinicios".
 */
public class PantallaBiseccion extends PantallaHerramienta {

    private Biseccion biseccion;
    private String mensaje = "";

    public PantallaBiseccion(Screen anterior) {
        super(anterior, "Biseccion automatica",
                "busqueda binaria: en 8 arranques en vez de 190");
        MotorDiagnostico motor = MotorDiagnostico.get();
        if (motor != null) {
            biseccion = new Biseccion(motor.carpetaJuego(), motor.registro());
        }
    }

    @Override
    protected int altoBotonera() {
        return 50;
    }

    @Override
    protected void botones(int x, int y, int ancho) {
        if (biseccion == null) {
            return;
        }
        MotorDiagnostico motor = MotorDiagnostico.get();
        int mitad = (ancho - 6) / 2;

        switch (biseccion.estado()) {
            case INACTIVA -> {
                Button empezar = Button.builder(Component.literal("Empezar la biseccion"),
                                b -> {
                                    mensaje = biseccion.iniciar(motor.jars());
                                    AlertasSonoras.listo();
                                    refrescar();
                                })
                        .bounds(x, y, ancho, 20).build();
                empezar.active = motor != null && motor.listo();
                addRenderableWidget(empezar);

                int apartados = biseccion.apartadosEnDisco();
                if (apartados > 0) {
                    addRenderableWidget(Button.builder(
                                    Component.literal("Devolver " + apartados
                                            + " mods que quedaron apartados"),
                                    b -> {
                                        mensaje = "Devolví " + biseccion.restaurarTodo()
                                                + " archivos a la carpeta mods.";
                                        refrescar();
                                    })
                            .bounds(x, y + 24, ancho, 20).build());
                }
            }
            case ESPERANDO_PRUEBA -> {
                addRenderableWidget(Button.builder(
                                Component.literal("El problema SIGUE pasando"),
                                b -> {
                                    mensaje = biseccion.responder(true, motor.jars());
                                    AlertasSonoras.listo();
                                    refrescar();
                                })
                        .bounds(x, y, mitad, 20).build());

                addRenderableWidget(Button.builder(
                                Component.literal("El problema DESAPARECIO"),
                                b -> {
                                    mensaje = biseccion.responder(false, motor.jars());
                                    AlertasSonoras.listo();
                                    refrescar();
                                })
                        .bounds(x + mitad + 6, y, mitad, 20).build());

                addRenderableWidget(Button.builder(
                                Component.literal("Cancelar y devolver todo"),
                                b -> {
                                    mensaje = biseccion.cancelar();
                                    refrescar();
                                })
                        .bounds(x, y + 24, ancho, 20).build());
            }
            case TERMINADA -> addRenderableWidget(Button.builder(
                            Component.literal("Cerrar la sesion y devolver todo"),
                            b -> {
                                mensaje = biseccion.cancelar();
                                refrescar();
                            })
                    .bounds(x, y, ancho, 20).build());
        }
    }

    @Override
    protected int contenido(GuiGraphics g, int x, int y, int ancho) {
        if (biseccion == null) {
            return vacio(g, x, y, ancho, "El motor de diagnostico no arranco.");
        }
        MotorDiagnostico motor = MotorDiagnostico.get();
        Biseccion.Situacion s = biseccion.situacion();

        if (!mensaje.isEmpty()) {
            y = veredicto(g, x, y, ancho, mensaje, Paleta.NEUTRO);
        }

        // --- Estado
        int color = switch (s.estado()) {
            case INACTIVA -> Paleta.TEXTO_APAGADO;
            case ESPERANDO_PRUEBA -> Paleta.ADVERTENCIA;
            case TERMINADA -> s.culpable() != null ? Paleta.ERROR : Paleta.TEXTO_TENUE;
        };
        y = veredicto(g, x, y, ancho, biseccion.instruccion(), color);

        if (s.estado() != Biseccion.Estado.INACTIVA) {
            y = seccion(g, x, y, ancho, "Progreso");
            y = fila(g, x, y, ancho, "Vuelta", String.valueOf(s.vuelta()), Paleta.TEXTO);
            y = fila(g, x, y, ancho, "Candidatos que quedan", String.valueOf(s.candidatos()),
                    Paleta.TEXTO);
            int faltan = Biseccion.arranquesEstimados(s.candidatos());
            y = fila(g, x, y, ancho, "Arranques que faltan",
                    faltan == 0 ? "ninguno" : "~" + faltan,
                    faltan <= 2 ? Paleta.OK : Paleta.TEXTO_TENUE);
            if (s.estado() == Biseccion.Estado.ESPERANDO_PRUEBA) {
                y = fila(g, x, y, ancho, "Apartados en esta vuelta",
                        String.valueOf(s.desactivadosAhora()), Paleta.ADVERTENCIA);
            }
            y += 6;

            // Barra de avance sobre el total de arranques estimados al inicio.
            if (!s.historial().isEmpty()) {
                int inicial = s.historial().get(0).candidatosAntes();
                int totalArranques = Math.max(1, Biseccion.arranquesEstimados(inicial));
                Widgets.barra(g, x, y, ancho, 6,
                        Math.min(1f, (totalArranques - faltan) / (float) totalArranques),
                        Paleta.NEUTRO);
                y += 12;
            }
        }

        // --- Instrucciones de la vuelta
        if (s.estado() == Biseccion.Estado.ESPERANDO_PRUEBA) {
            y = seccion(g, x, y, ancho, "Que tenes que hacer ahora");
            y = Widgets.parrafo(g, this.font,
                    "1) Cerrá el juego del todo. No alcanza con salir al menu: Forge lee la "
                            + "carpeta mods una sola vez, al arrancar.\n\n"
                            + "2) Volvé a abrirlo y probá exactamente lo que hacia fallar el "
                            + "juego. Si el problema era un crash al arrancar, con que arranque "
                            + "o no arranque ya tenes la respuesta.\n\n"
                            + "3) Volvé a esta pantalla y tocá el boton que corresponda.\n\n"
                            + "Si el juego ni arranca por culpa de la biseccion misma, entrá a "
                            + "la pantalla de error de Forge: el boton de Faro esta ahi tambien.",
                    x, y, ancho, Paleta.TEXTO, 16) + 6;

            y = seccion(g, x, y, ancho, "Apartados en esta vuelta ("
                    + s.desactivadosAhora() + " archivos)");
            y = Widgets.parrafo(g, this.font,
                    "Estan en mods/" + Biseccion.CARPETA_APARTE + "/. No se borro nada. "
                            + "Si algo sale mal, moverlos de vuelta a mods/ deja todo como estaba "
                            + "— y para eso ni siquiera hace falta Faro.",
                    x, y, ancho, Paleta.TEXTO_TENUE, 5) + 4;

            boton(g, x, y, "Abrir la carpeta de apartados", Paleta.NEUTRO,
                    () -> {
                        if (motor != null) {
                            Util.getPlatform().openFile(motor.carpetaMods()
                                    .resolve(Biseccion.CARPETA_APARTE).toFile());
                        }
                    });
            y += 22;
        }

        // --- Resultado
        if (s.estado() == Biseccion.Estado.TERMINADA && s.culpable() != null) {
            y = seccion(g, x, y, ancho, "Culpable");
            Widgets.tarjeta(g, x, y, ancho, 22, Paleta.ERROR);
            g.drawString(this.font, s.culpable(), x + 8, y + 6, Paleta.ERROR, false);
            y += 28;
            y = Widgets.parrafo(g, this.font,
                    "Todos los demas mods ya volvieron a su lugar. Ahora que sabes cual es, las "
                            + "opciones son: buscarle una version distinta, revisar su config, "
                            + "reportarlo al autor, o dejarlo desactivado.\n\n"
                            + "Ojo con una cosa: la biseccion encuentra el mod cuya PRESENCIA "
                            + "hace aparecer el problema. Eso no siempre significa que el bug sea "
                            + "suyo — puede estar destapando un fallo de otro. Pero es el punto "
                            + "de partida correcto, y sin esto no lo tenias.",
                    x, y, ancho, Paleta.TEXTO_TENUE, 14) + 6;
        }

        // --- Historial
        if (!s.historial().isEmpty()) {
            y = seccion(g, x, y, ancho, "Historial");
            for (Biseccion.Paso p : s.historial()) {
                Widgets.lineaRecortada(g, this.font,
                        "Vuelta " + p.numero() + ": aparte " + p.desactivados()
                                + " de " + p.candidatosAntes() + " candidatos  ->  "
                                + (p.seguiaFallando() ? "seguia fallando" : "desaparecio"),
                        x + 4, y, ancho - 8,
                        p.seguiaFallando() ? Paleta.ADVERTENCIA : Paleta.OK);
                y += 11;
            }
            y += 6;
        }

        // --- Explicacion
        if (s.estado() == Biseccion.Estado.INACTIVA) {
            y = seccion(g, x, y, ancho, "Cuando usar esto");
            y = Widgets.parrafo(g, this.font,
                    "Cuando tenes un problema que se repite y el resto de Faro no lo pudo "
                            + "atribuir: un crash sin culpable claro, un congelamiento, un "
                            + "rendimiento que se desplomo sin motivo, algo visual que no deja "
                            + "rastro en el log.\n\n"
                            + "Como funciona: se apaga la mitad de los mods y probas. Si el "
                            + "problema sigue, el culpable esta en la mitad que quedo; si "
                            + "desaparece, esta en la que se apago. Repetis. Cada vuelta descarta "
                            + "la mitad, y por eso con 190 mods alcanzan 8 arranques en vez de 190.",
                    x, y, ancho, Paleta.TEXTO_TENUE, 16) + 6;

            y = seccion(g, x, y, ancho, "Lo que hace Faro y a mano no se hace");
            y = Widgets.parrafo(g, this.font,
                    "· Arrastra las dependencias. Apagar una libreria deja sin cargar a diez "
                            + "mods, Forge muestra la pantalla de error, y el resultado de la "
                            + "prueba no dice nada. Faro calcula el cierre completo y mueve el "
                            + "grupo entero.\n\n"
                            + "· Nunca toca librerias compartidas ni a Faro mismo.\n\n"
                            + "· El estado vive en disco, asi que sobrevive a los reinicios — que "
                            + "son justamente el punto de todo esto.\n\n"
                            + "· Cada movimiento queda en faro/acciones.log y se revierte con un "
                            + "boton. Nada se borra nunca.",
                    x, y, ancho, Paleta.TEXTO_TENUE, 18);

            if (motor != null && motor.listo()) {
                y += 6;
                int n = biseccion.candidatosIniciales(motor.jars()).size();
                y = veredicto(g, x, y, ancho,
                        "Con tu instalacion serian " + n + " candidatos y como maximo "
                                + Biseccion.arranquesEstimados(n) + " arranques.",
                        Paleta.NEUTRO);
            }
        }
        return y;
    }
}
