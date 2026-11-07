package com.alertalar.service;

import com.alertalar.dto.CepResponse;
import com.alertalar.dto.EnderecoRequest;
import com.alertalar.dto.LocalizacaoResponse;
import com.alertalar.entity.Endereco;
import com.alertalar.entity.enums.PrecisaoGeocodificacao;
import com.alertalar.exception.EnderecoNaoEncontradoException;
import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.exception.PrecisaoInsuficienteException;
import com.alertalar.integration.ibge.IbgeLocalidadesClient;
import com.alertalar.integration.ibge.MunicipiosIbge;
import com.alertalar.integration.nominatim.NominatimClient;
import com.alertalar.integration.nominatim.NominatimClient.ResultadoGeocodificacao;
import com.alertalar.integration.viacep.ViaCepClient;
import com.alertalar.integration.viacep.ViaCepClient.DadosCep;
import com.alertalar.repository.EnderecoRepository;
import com.alertalar.util.Geo;
import com.alertalar.util.Texto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Validação de endereços, consulta de CEP e geocodificação.
 * Regra: a análise só é feita com precisão de rua ou melhor (ou posição confirmada no mapa);
 * nunca apenas pelo CEP, bairro ou cidade.
 */
@Service
public class EnderecoService {

    private static final Logger log = LoggerFactory.getLogger(EnderecoService.class);

    private final ViaCepClient viaCep;
    private final NominatimClient nominatim;
    private final IbgeLocalidadesClient ibge;
    private final MunicipiosIbge municipios;
    private final EnderecoRepository repository;
    private final FonteDadosService fontes;

    public EnderecoService(ViaCepClient viaCep, NominatimClient nominatim, IbgeLocalidadesClient ibge,
                           MunicipiosIbge municipios, EnderecoRepository repository, FonteDadosService fontes) {
        this.viaCep = viaCep;
        this.nominatim = nominatim;
        this.ibge = ibge;
        this.municipios = municipios;
        this.repository = repository;
        this.fontes = fontes;
    }

    /** Consulta o CEP (para preencher o formulário). */
    public CepResponse buscarCep(String cep) {
        String digitos = Texto.somenteDigitos(cep);
        if (digitos.length() != 8) {
            throw new IllegalArgumentException("O CEP deve ter 8 dígitos.");
        }
        DadosCep d = viaCep.buscar(digitos)
                .orElseThrow(() -> new EnderecoNaoEncontradoException("CEP " + Texto.formatarCep(digitos) + " não encontrado."));
        fontes.registrarAcesso("VIACEP", Instant.now());
        return new CepResponse(d.cep(), d.logradouro(), d.bairro(), d.cidade(), d.uf(), d.codigoIbge(), ViaCepClient.FONTE);
    }

    /** Geocodifica e devolve os candidatos para o usuário confirmar no mapa. */
    public LocalizacaoResponse localizar(EnderecoRequest req) {
        EnderecoRequest limpo = limpar(req);
        List<Candidato> candidatos = geocodificar(limpo);
        if (candidatos.isEmpty()) {
            throw new EnderecoNaoEncontradoException(mensagemNaoEncontrado(limpo));
        }
        boolean algumSuficiente = candidatos.stream().anyMatch(c -> c.precisao() != null);
        String mensagem = algumSuficiente
                ? "Confira no mapa se o marcador está sobre o imóvel. Se não estiver, clique na posição correta."
                : "Encontramos apenas a localização aproximada (bairro ou cidade). Clique no mapa sobre o imóvel "
                  + "para confirmar a posição exata antes da análise.";
        return new LocalizacaoResponse(candidatos.stream().map(c -> new LocalizacaoResponse.Candidato(
                c.r().latitude(), c.r().longitude(),
                c.precisao() == null ? "APROXIMADA" : c.precisao().name(),
                c.precisao() == null ? "Somente bairro/cidade: precisão insuficiente" : c.precisao().getDescricao(),
                c.precisao() != null, c.r().nomeExibicao(), NominatimClient.FONTE)).toList(), mensagem);
    }

    /**
     * Resolve o endereço para análise. Com coordenadas confirmadas pelo usuário, usa-as
     * (precisão MANUAL); sem elas, geocodifica exigindo ao menos precisão de rua.
     */
    public Endereco resolverParaAnalise(EnderecoRequest req) {
        EnderecoRequest limpo = limpar(req);
        Optional<DadosCep> dadosCep = consultarCepSilencioso(limpo.cep());
        String codigoIbge = dadosCep.map(DadosCep::codigoIbge)
                .filter(c -> dadosCep.get().uf().equalsIgnoreCase(limpo.estado())
                        && Texto.normalizar(dadosCep.get().cidade()).equals(Texto.normalizar(limpo.cidade())))
                .orElseGet(() -> codigoIbgeSilencioso(limpo.estado(), limpo.cidade()));

        if (limpo.possuiCoordenadas()) {
            if (!Geo.dentroDoBrasil(limpo.latitude(), limpo.longitude())) {
                throw new IllegalArgumentException("As coordenadas informadas estão fora do território brasileiro.");
            }
            Endereco e = novoEndereco(limpo, codigoIbge, dadosCep);
            e.setLatitude(limpo.latitude());
            e.setLongitude(limpo.longitude());
            e.setPrecisao(PrecisaoGeocodificacao.MANUAL);
            e.setFonteGeocodificacao("Posição confirmada pelo usuário no mapa");
            return repository.save(e);
        }

        String chave = chave(limpo);
        Optional<Endereco> emCache = repository.findByChaveNormalizada(chave);
        if (emCache.isPresent()) {
            Endereco salvo = emCache.get();
            if (Texto.vazio(salvo.getCodigoIbge()) && !Texto.vazio(codigoIbge)) {
                // endereço guardado quando o município não pôde ser identificado: completa agora
                salvo.setCodigoIbge(codigoIbge);
                return repository.save(salvo);
            }
            return salvo;
        }
        List<Candidato> candidatos = geocodificar(limpo);
        if (candidatos.isEmpty()) {
            throw new EnderecoNaoEncontradoException(mensagemNaoEncontrado(limpo));
        }
        Candidato melhor = candidatos.get(0);
        if (melhor.precisao() == null) {
            throw new PrecisaoInsuficienteException("O endereço foi localizado apenas de forma aproximada (bairro, CEP ou "
                    + "cidade). Para não analisar o imóvel errado, confirme a posição exata clicando no mapa.",
                    melhor.r().latitude(), melhor.r().longitude());
        }
        Endereco e = novoEndereco(limpo, codigoIbge, dadosCep);
        e.setLatitude(melhor.r().latitude());
        e.setLongitude(melhor.r().longitude());
        e.setPrecisao(melhor.precisao());
        e.setFonteGeocodificacao(NominatimClient.FONTE);
        e.setChaveNormalizada(chave);
        try {
            return repository.save(e);
        } catch (DataIntegrityViolationException concorrente) {
            // outra requisição gravou o mesmo endereço ao mesmo tempo
            return repository.findByChaveNormalizada(chave).orElseThrow(() -> concorrente);
        }
    }

    /** Candidatos ordenados do mais para o menos preciso, já filtrados por cidade/UF. */
    List<Candidato> geocodificar(EnderecoRequest req) {
        List<ResultadoGeocodificacao> resultados = nominatim.buscarEstruturado(req.rua(), req.numero(), req.cidade(), req.estado(), 5);
        if (filtrar(resultados, req).isEmpty()) {
            String texto = req.rua() + (Texto.vazio(req.numero()) ? "" : ", " + req.numero())
                    + (Texto.vazio(req.bairro()) ? "" : ", " + req.bairro())
                    + ", " + req.cidade() + " - " + req.estado() + ", Brasil";
            resultados = nominatim.buscarTexto(texto, 5);
        }
        String nomeJunto = nomeJunto(req);
        if (filtrar(resultados, req).isEmpty() && nomeJunto != null) {
            // Nomes de lugares escritos separados ("Park Shopping") e cadastrados juntos ("ParkShopping")
            resultados = nominatim.buscarTexto(nomeJunto + ", " + req.cidade() + " - " + req.estado() + ", Brasil", 5);
        }
        fontes.registrarAcesso("NOMINATIM", Instant.now());
        return filtrar(resultados, req).stream()
                .map(r -> new Candidato(r, precisao(r, req.numero())))
                .sorted(Comparator.comparing((Candidato c) -> c.precisao() == null ? 9 : c.precisao().ordinal())
                        .thenComparing(c -> -c.r().placeRank()))
                .toList();
    }

    /** "Park Shopping" → "ParkShopping" (só para nomes curtos, sem número, que não parecem endereço). */
    static String nomeJunto(EnderecoRequest req) {
        if (!Texto.vazio(req.numero()) || Texto.vazio(req.rua())) {
            return null;
        }
        String[] palavras = req.rua().trim().split("\\s+");
        if (palavras.length < 2 || palavras.length > 3 || req.rua().matches("(?i)^(rua|r\\.|avenida|av\\.?|travessa|estrada|rodovia)\\b.*")) {
            return null;
        }
        return String.join("", palavras);
    }

    private static List<ResultadoGeocodificacao> filtrar(List<ResultadoGeocodificacao> resultados, EnderecoRequest req) {
        String cidade = Texto.normalizar(req.cidade());
        return resultados.stream()
                .filter(r -> r.uf() == null || r.uf().equalsIgnoreCase(req.estado()))
                .filter(r -> r.cidade() == null || Texto.normalizar(r.cidade()).equals(cidade)
                        || Texto.normalizar(r.nomeExibicao()).contains(cidade))
                .toList();
    }

    /**
     * NUMERO: o Nominatim encontrou o mesmo número do imóvel; RUA: encontrou a via;
     * null: apenas bairro, CEP ou cidade (insuficiente).
     */
    static PrecisaoGeocodificacao precisao(ResultadoGeocodificacao r, String numeroPedido) {
        String pedido = Texto.normalizar(numeroPedido);
        boolean mesmoNumero = r.numero() != null && !pedido.isEmpty() && Texto.normalizar(r.numero()).equals(pedido);
        if (mesmoNumero && r.placeRank() >= 26) {
            return PrecisaoGeocodificacao.NUMERO;
        }
        if (r.placeRank() >= 26 && r.rua() != null) {
            return PrecisaoGeocodificacao.RUA;
        }
        return null;
    }

    private Endereco novoEndereco(EnderecoRequest req, String codigoIbge, Optional<DadosCep> dadosCep) {
        Endereco e = new Endereco();
        e.setCep(Texto.formatarCep(req.cep()));
        e.setRua(req.rua());
        e.setNumero(req.numero());
        e.setBairro(req.bairro() != null ? req.bairro() : dadosCep.map(DadosCep::bairro).orElse(null));
        e.setCidade(req.cidade());
        e.setEstado(req.estado());
        e.setCodigoIbge(codigoIbge);
        return e;
    }

    private Optional<DadosCep> consultarCepSilencioso(String cep) {
        if (Texto.somenteDigitos(cep).length() != 8) {
            return Optional.empty();
        }
        try {
            return viaCep.buscar(cep);
        } catch (IntegracaoExternaException e) {
            log.info("ViaCEP indisponível; seguindo sem dados do CEP");
            return Optional.empty();
        }
    }

    /** Código IBGE do município: lista guardada no sistema e, se não estiver nela, API do IBGE. */
    private String codigoIbgeSilencioso(String uf, String cidade) {
        Optional<String> local = municipios.codigo(uf, cidade);
        if (local.isPresent()) {
            return local.get();
        }
        try {
            Optional<String> codigo = ibge.codigoMunicipio(uf, cidade);
            codigo.ifPresent(c -> fontes.registrarAcesso("IBGE_LOCALIDADES", Instant.now()));
            return codigo.orElse(null);
        } catch (IntegracaoExternaException e) {
            log.info("API do IBGE indisponível; código do município não identificado");
            return null;
        }
    }

    static EnderecoRequest limpar(EnderecoRequest r) {
        return new EnderecoRequest(
                Texto.vazio(r.cep()) ? null : Texto.formatarCep(r.cep()),
                Texto.limpar(r.rua()),
                Texto.limpar(r.numero()),
                Texto.limpar(r.bairro()),
                Texto.limpar(r.cidade()),
                r.estado() == null ? null : r.estado().trim().toUpperCase(),
                r.latitude(), r.longitude());
    }

    static String chave(EnderecoRequest r) {
        return String.join("|", Texto.normalizar(r.rua()), Texto.normalizar(r.numero()),
                Texto.normalizar(r.cidade()), r.estado().toUpperCase());
    }

    private static String mensagemNaoEncontrado(EnderecoRequest r) {
        return "Não encontramos o endereço \"" + r.rua() + (Texto.vazio(r.numero()) ? "" : ", " + r.numero())
                + "\" em " + r.cidade() + "/" + r.estado() + ". Confira o nome da rua e da cidade, ou marque a "
                + "posição do imóvel diretamente no mapa.";
    }

    record Candidato(ResultadoGeocodificacao r, PrecisaoGeocodificacao precisao) {
    }
}
