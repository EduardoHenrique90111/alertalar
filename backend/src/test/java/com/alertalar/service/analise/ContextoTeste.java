package com.alertalar.service.analise;

import com.alertalar.entity.FonteDados;
import com.alertalar.entity.Ocorrencia;
import com.alertalar.entity.enums.NivelLocalizacao;
import com.alertalar.entity.enums.OrigemOcorrencia;
import com.alertalar.entity.enums.StatusIntegracao;
import com.alertalar.entity.enums.StatusValidacao;
import com.alertalar.entity.enums.TipoEvento;
import com.alertalar.entity.enums.TipoFonte;
import com.alertalar.integration.overpass.OverpassClient.CursoDagua;
import com.alertalar.integration.sgb.SgbSetoresRiscoClient.SetorRisco;
import com.alertalar.service.analise.ContextoAnalise.HistoricoMunicipal;
import com.alertalar.service.analise.ContextoAnalise.OcorrenciaProxima;
import com.alertalar.service.analise.ContextoAnalise.PerfilElevacao;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Construtor de contextos de análise para os testes dos fatores. */
public class ContextoTeste {

    public static final double LAT = -29.92;
    public static final double LON = -51.18;

    private Dado<List<CursoDagua>> cursos = Dado.indisponivel("não consultado");
    private Dado<PerfilElevacao> elevacao = Dado.indisponivel("não consultado");
    private Dado<List<SetorRisco>> setores = Dado.indisponivel("não consultado");
    private Dado<List<OcorrenciaProxima>> ocorrencias = Dado.de(List.of());
    private Dado<HistoricoMunicipal> historico = Dado.de(new HistoricoMunicipal(List.of(), false));
    private String codigoIbge = "4304606";

    public static ContextoTeste novo() {
        return new ContextoTeste();
    }

    /** Rio vertical a uma distância (m) a leste do ponto. */
    public ContextoTeste rioA(double metros) {
        double dLon = metros / (111_320 * Math.cos(Math.toRadians(LAT)));
        List<double[]> linha = List.of(new double[]{LAT - 0.01, LON + dLon}, new double[]{LAT + 0.01, LON + dLon});
        cursos = Dado.de(List.of(new CursoDagua(1, "river", null, "Rio Teste", true, false, List.of(linha))));
        return this;
    }

    public ContextoTeste corregoA(double metros) {
        double dLat = metros / 111_320;
        List<double[]> linha = List.of(new double[]{LAT + dLat, LON - 0.01}, new double[]{LAT + dLat, LON + 0.01});
        List<CursoDagua> lista = new ArrayList<>(cursos.disponivel() ? cursos.valor() : List.of());
        lista.add(new CursoDagua(2, "stream", null, "Arroio Teste", false, false, List.of(linha)));
        cursos = Dado.de(lista);
        return this;
    }

    public ContextoTeste semCursosDagua() {
        cursos = Dado.de(List.of());
        return this;
    }

    public ContextoTeste elevacao(Double ponto, Double agua, Double distanciaAgua, Double mediaEntorno) {
        elevacao = Dado.de(new PerfilElevacao(ponto, agua, distanciaAgua, "rio Teste", mediaEntorno, 300, 8));
        return this;
    }

    public ContextoTeste semSetores() {
        setores = Dado.de(List.of());
        return this;
    }

    /** Setor hidrológico quadrado (~220 m de lado) centrado no ponto deslocado de "deslocamentoGraus". */
    public ContextoTeste setor(String grau, double deslocamentoGraus, boolean hidrologico) {
        double c = LON + deslocamentoGraus;
        double d = 0.001;
        List<double[]> anel = List.of(new double[]{LAT - d, c - d}, new double[]{LAT - d, c + d},
                new double[]{LAT + d, c + d}, new double[]{LAT + d, c - d}, new double[]{LAT - d, c - d});
        setores = Dado.de(List.of(new SetorRisco("Canoas", "RS", "RS_01", "Bairro", hidrologico ? "Inundação" : "Deslizamento",
                grau, "descrição", LocalDate.of(2024, 1, 1), hidrologico, List.of(anel))));
        return this;
    }

    public ContextoTeste ocorrencias(int quantidade, double distancia) {
        List<OcorrenciaProxima> lista = new ArrayList<>();
        for (int i = 0; i < quantidade; i++) {
            lista.add(new OcorrenciaProxima(ocorrencia(OrigemOcorrencia.RELATO_USUARIO, NivelLocalizacao.PONTO, LocalDate.of(2024, 5, 1)), distancia));
        }
        ocorrencias = Dado.de(lista);
        return this;
    }

    public ContextoTeste historicoMunicipal(int registros, boolean baseImportada) {
        List<Ocorrencia> lista = new ArrayList<>();
        for (int i = 0; i < registros; i++) {
            lista.add(ocorrencia(OrigemOcorrencia.OFICIAL, NivelLocalizacao.MUNICIPIO, LocalDate.of(2024 - i, 5, 1)));
        }
        historico = Dado.de(new HistoricoMunicipal(lista, baseImportada));
        return this;
    }

    public ContextoTeste semCodigoIbge() {
        codigoIbge = null;
        return this;
    }

    /** Permite ajustar os registros do histórico municipal antes de montar o contexto. */
    public ContextoAnalise construir(java.util.function.Consumer<List<Ocorrencia>> ajusteHistorico) {
        if (historico != null && historico.disponivel()) {
            ajusteHistorico.accept(historico.valor().registros());
        }
        return construir();
    }

    public ContextoAnalise construir() {
        return new ContextoAnalise(LAT, LON, "RS", codigoIbge, new ContextoAnalise.Parametros(1500, 500, 300, 30),
                cursos, elevacao, setores, ocorrencias, historico);
    }

    static Ocorrencia ocorrencia(OrigemOcorrencia origem, NivelLocalizacao nivel, LocalDate data) {
        Ocorrencia o = new Ocorrencia();
        o.setOrigem(origem);
        o.setNivelLocalizacao(nivel);
        o.setStatusValidacao(StatusValidacao.CONFIRMADA);
        o.setTipoEvento(TipoEvento.ALAGAMENTO);
        o.setDataOcorrencia(data);
        o.setLocalizacao("Local de teste");
        o.setDescricao("Ocorrência de teste");
        o.setFonte(new FonteDados("TESTE", "Fonte de teste", TipoFonte.RELATO_USUARIO, StatusIntegracao.ATIVA));
        return o;
    }
}
