package com.coco.faro.diag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Un mod candidato a causante, con su puntaje y el detalle de como lo saco. */
public final class Sospechoso implements Comparable<Sospechoso> {

    /** Una evidencia suelta: cuanto suma y por que. */
    public record Indicio(String descripcion, int puntos) {
    }

    private final String modId;
    private final String nombreVisible;
    private final Path jar;
    private final List<Indicio> indicios = new ArrayList<>();
    private int puntaje = 0;

    public Sospechoso(String modId, String nombreVisible, Path jar) {
        this.modId = modId;
        this.nombreVisible = nombreVisible == null ? modId : nombreVisible;
        this.jar = jar;
    }

    public void sumar(String descripcion, int puntos) {
        if (puntos == 0) {
            return;
        }
        indicios.add(new Indicio(descripcion, puntos));
        puntaje += puntos;
    }

    public String modId() {
        return modId;
    }

    public String nombreVisible() {
        return nombreVisible;
    }

    public Path jar() {
        return jar;
    }

    public int puntaje() {
        return puntaje;
    }

    public List<Indicio> indicios() {
        return List.copyOf(indicios);
    }

    /** Ordena de mayor a menor puntaje. */
    @Override
    public int compareTo(Sospechoso otro) {
        return Integer.compare(otro.puntaje, this.puntaje);
    }
}
