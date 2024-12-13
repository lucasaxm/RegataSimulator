package com.boatarde.regatasimulator.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class SourceCsvRecord {
    private String nome;
    private String texto;
    private String tipo;
    private String conteudo;
}
