package com.alertalar.ferramentas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ferramenta que gera a base compacta a partir do CSV original do Atlas Digital de Desastres. */
class PrepararBaseAtlasTest {

    @Test
    @DisplayName("Conversão do CSV do Atlas: Windows-1252, campo com quebra de linha, filtro por tipo e ordenação")
    void conversaoDoCsvOriginal() throws Exception {
        String csv = "\"Protocolo_S2iD\";\"Nome_Municipio\";\"Sigla_UF\";\"Data_Evento\";\"Cod_Cobrade\";\"descricao_tipologia\";"
                + "\"Status\";\"Cod_IBGE_Mun\";\"DH_MORTOS\";\"DH_DESABRIGADOS\";\"DH_DESALOJADOS\";"
                + "\"DM_Uni Habita Danificadas\";\"DM_Uni Habita Destruidas\";\"Observacao\"\r\n"
                + "SP-2;São Paulo;SP;10/02/2020;12300;Alagamentos;Registro;3550308;0;0;0;0;0;\"\"\r\n"
                + "RS-1;Canoas;RS;02/05/2024;13214;Chuvas Intensas;Reconhecido;4304606;31;\"12.000\";0;0;0;\"Água subiu;\r\nbairros ilhados\"\r\n"
                + "RS-3;Canoas;RS;01/01/2022;14110;Estiagem e Seca;Reconhecido;4304606;0;0;0;0;0;\r\n"
                + "RS-4;Canoas;RS;data ruim;12100;Inundações;Registro;4304606;0;0;0;0;0;\r\n";
        var r = PrepararBaseAtlas.converter(new ByteArrayInputStream(csv.getBytes(Charset.forName("windows-1252"))),
                Charset.forName("windows-1252"));
        assertEquals(4, r.lidas());
        assertEquals(1, r.outrasTipologias());
        assertEquals(1, r.invalidos());
        assertEquals(2, r.linhas().size());
        String[] primeiro = r.linhas().get(0); // ordenado pelo código IBGE: 3550308 (São Paulo) vem antes de 4304606
        assertEquals("SP-2", primeiro[0]);
        assertEquals("São Paulo", primeiro[2]);
        String[] canoas = r.linhas().get(1);
        assertEquals("2024-05-02", canoas[4]);
        assertEquals("Chuvas Intensas", canoas[6]);
        assertEquals("12000", canoas[9]);
    }
}
