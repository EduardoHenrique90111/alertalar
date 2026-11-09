package com.alertalar.service;

import com.alertalar.dto.EnderecoRequest;
import com.alertalar.exception.IntegracaoExternaException;
import com.alertalar.integration.http.CacheTemporario;
import com.alertalar.integration.nominatim.NominatimClient;
import com.alertalar.integration.nominatim.NominatimClient.ResultadoGeocodificacao;
import com.alertalar.integration.photon.PhotonClient;
import com.alertalar.util.Texto;
import com.alertalar.util.UnidadeFederativa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Converte o endereço em coordenadas usando dois serviços com os dados do OpenStreetMap:
 * o Nominatim e, como reserva, o Photon.
 *
 * <ul>
 *   <li>Se o Nominatim recusar (limite de pedidos por IP, comum em hospedagens compartilhadas
 *       como o Render), bloquear ou não responder, a busca segue no Photon na mesma hora, e o
 *       Nominatim fica alguns minutos em pausa (o tempo pedido por ele, quando informado) para
 *       não insistir num serviço que pediu para esperar.</li>
 *   <li>Se o Nominatim responder sem achar o endereço, o Photon também é consultado: ele tolera
 *       pequenas diferenças de escrita. Como essa busca é aproximada, um resultado do Photon cujo
 *       nome não bate com o que foi digitado vale só como posição aproximada (a pessoa confirma
 *       no mapa).</li>
 *   <li>Endereços encontrados ficam 24 horas em memória, como pede a política de uso.</li>
 * </ul>
 */
@Service
public class GeocodificacaoService {

    private static final Logger log = LoggerFactory.getLogger(GeocodificacaoService.class);

    /** Pausa quando o Nominatim avisa que o limite de pedidos foi atingido (HTTP 429) sem dizer quanto esperar. */
    static final Duration PAUSA_LIMITE = Duration.ofMinutes(5);
    /** Pausa quando o Nominatim bloqueia o acesso (HTTP 403). */
    static final Duration PAUSA_BLOQUEIO = Duration.ofMinutes(30);
    /** Pausa quando ele não responde a tempo ou dá erro interno. */
    static final Duration PAUSA_FALHA = Duration.ofMinutes(1);
    static final Duration PAUSA_MAXIMA = Duration.ofMinutes(30);
    static final int LIMITE_RESULTADOS = 5;
    static final int LIMITE_RESULTADOS_PHOTON = 10;

    static final String MENSAGEM_INDISPONIVEL = "A busca de endereços do OpenStreetMap está sobrecarregada agora. "
            + "Tente de novo em alguns segundos ou marque a posição direto no mapa.";

    /** Resultados já filtrados e o serviço que os encontrou. */
    public record Busca(List<ResultadoGeocodificacao> resultados, String fonte) {
    }

    private final NominatimClient nominatim;
    private final PhotonClient photon;
    private final FonteDadosService fontes;
    private final Clock relogio;
    private final CacheTemporario<String, Busca> cache;
    private volatile Instant nominatimPausadoAte = Instant.MIN;

    @Autowired
    public GeocodificacaoService(NominatimClient nominatim, PhotonClient photon, FonteDadosService fontes) {
        this(nominatim, photon, fontes, Clock.systemUTC());
    }

    GeocodificacaoService(NominatimClient nominatim, PhotonClient photon, FonteDadosService fontes, Clock relogio) {
        this.nominatim = nominatim;
        this.photon = photon;
        this.fontes = fontes;
        this.relogio = relogio;
        this.cache = new CacheTemporario<>(Duration.ofHours(24), 2_000, relogio);
    }

    /**
     * @param filtro mantém só os resultados da cidade e UF pedidas (decide se vale tentar de outro jeito)
     * @return resultados filtrados; lista vazia quando os serviços responderam e não acharam o endereço
     * @throws IntegracaoExternaException quando nenhum dos dois serviços respondeu
     */
    public Busca buscar(EnderecoRequest req, UnaryOperator<List<ResultadoGeocodificacao>> filtro) {
        String chave = chave(req);
        Optional<Busca> guardada = cache.obter(chave);
        if (guardada.isPresent()) {
            return guardada.get();
        }
        boolean pausado = nominatimEmPausa();
        List<Servico> ordem = pausado ? List.of(Servico.PHOTON, Servico.NOMINATIM) : List.of(Servico.NOMINATIM, Servico.PHOTON);
        List<IntegracaoExternaException> falhas = new ArrayList<>();
        String respondeu = null;
        for (Servico servico : ordem) {
            if (servico == Servico.NOMINATIM && pausado && respondeu != null) {
                break; // em pausa, o Nominatim só é chamado se o Photon nem responder
            }
            try {
                List<ResultadoGeocodificacao> resultados = servico == Servico.NOMINATIM
                        ? tentativasNominatim(req, filtro) : tentativasPhoton(req, filtro);
                respondeu = respondeu == null ? servico.fonte : respondeu;
                if (servico == Servico.NOMINATIM) {
                    nominatimPausadoAte = Instant.MIN;
                    fontes.registrarAcesso("NOMINATIM", relogio.instant());
                } else {
                    fontes.registrarAcesso("PHOTON", relogio.instant());
                }
                if (!resultados.isEmpty()) {
                    Busca busca = new Busca(resultados, servico.fonte);
                    cache.guardar(chave, busca);
                    return busca;
                }
            } catch (IntegracaoExternaException e) {
                falhas.add(e);
                if (servico == Servico.NOMINATIM) {
                    pausarNominatim(e);
                } else {
                    log.warn("Photon também falhou: {}", e.getMessage());
                }
            }
        }
        if (respondeu != null) {
            return new Busca(List.of(), respondeu); // os serviços responderam, mas não acharam
        }
        throw new IntegracaoExternaException("OpenStreetMap", MENSAGEM_INDISPONIVEL, falhas.isEmpty() ? null : falhas.get(0));
    }

    /** Estruturada; depois texto livre; depois o nome junto ("Park Shopping" → "ParkShopping"). */
    private List<ResultadoGeocodificacao> tentativasNominatim(EnderecoRequest req, UnaryOperator<List<ResultadoGeocodificacao>> filtro) {
        List<ResultadoGeocodificacao> r = filtro.apply(
                nominatim.buscarEstruturado(req.rua(), req.numero(), req.cidade(), req.estado(), LIMITE_RESULTADOS));
        if (r.isEmpty()) {
            String texto = req.rua() + (Texto.vazio(req.numero()) ? "" : ", " + req.numero())
                    + (Texto.vazio(req.bairro()) ? "" : ", " + req.bairro())
                    + ", " + req.cidade() + " - " + req.estado() + ", Brasil";
            r = filtro.apply(nominatim.buscarTexto(texto, LIMITE_RESULTADOS));
        }
        String nomeJunto = EnderecoService.nomeJunto(req);
        if (r.isEmpty() && nomeJunto != null) {
            r = filtro.apply(nominatim.buscarTexto(nomeJunto + ", " + req.cidade() + " - " + req.estado() + ", Brasil",
                    LIMITE_RESULTADOS));
        }
        return r;
    }

    private List<ResultadoGeocodificacao> tentativasPhoton(EnderecoRequest req, UnaryOperator<List<ResultadoGeocodificacao>> filtro) {
        String estado = UnidadeFederativa.daSigla(req.estado()).map(UnidadeFederativa::getNome).orElse(req.estado());
        String lugar = ", " + req.cidade() + ", " + estado;
        List<ResultadoGeocodificacao> r = filtro.apply(photon.buscar(
                req.rua() + (Texto.vazio(req.numero()) ? "" : " " + req.numero()) + lugar, LIMITE_RESULTADOS_PHOTON));
        String nomeJunto = EnderecoService.nomeJunto(req);
        if (r.isEmpty() && nomeJunto != null) {
            r = filtro.apply(photon.buscar(nomeJunto + lugar, LIMITE_RESULTADOS_PHOTON));
        }
        return r.stream().map(item -> conferirNome(item, req.rua())).limit(LIMITE_RESULTADOS).toList();
    }

    /**
     * O Photon aceita diferenças de escrita e pode trazer outra rua parecida. Quando as palavras
     * digitadas não aparecem no resultado, ele vale só como posição aproximada (a pessoa confere no mapa).
     */
    static ResultadoGeocodificacao conferirNome(ResultadoGeocodificacao r, String ruaPedida) {
        if (nomeConfere(ruaPedida, r.nomeExibicao()) || r.placeRank() < 26) {
            return r;
        }
        return new ResultadoGeocodificacao(r.latitude(), r.longitude(), 20, r.tipoEndereco(), r.numero(), r.rua(),
                r.bairro(), r.cidade(), r.uf(), r.cep(), r.nomeExibicao());
    }

    /** Todas as palavras significativas pedidas aparecem no resultado ("Av." e "Rua" não contam). */
    static boolean nomeConfere(String pedido, String encontrado) {
        String alvo = Texto.normalizar(encontrado).replaceAll("[^a-z0-9 ]", " ");
        for (String palavra : Texto.normalizar(pedido).replaceAll("[^a-z0-9 ]", " ").split(" +")) {
            if (palavra.length() < 3 || PALAVRAS_GENERICAS.contains(palavra) || palavra.matches("\\d+")) {
                continue;
            }
            if (!alvo.contains(palavra)) {
                return false;
            }
        }
        return true;
    }

    private static final java.util.Set<String> PALAVRAS_GENERICAS = java.util.Set.of(
            "rua", "avenida", "travessa", "estrada", "rodovia", "alameda", "praca", "largo", "beco", "via",
            "das", "dos", "del", "para");

    boolean nominatimEmPausa() {
        return relogio.instant().isBefore(nominatimPausadoAte);
    }

    private void pausarNominatim(IntegracaoExternaException e) {
        Duration pausa = pausa(e);
        nominatimPausadoAte = relogio.instant().plus(pausa);
        log.warn("Nominatim indisponível ({}); buscas de endereço vão primeiro ao Photon por {} min",
                e.getMessage(), Math.max(1, pausa.toMinutes()));
    }

    /** 429: o tempo pedido pela fonte (entre 1 e 30 min) ou 5 min; 403: 30 min; outras falhas: 1 min. */
    static Duration pausa(IntegracaoExternaException e) {
        int status = e.statusHttp().orElse(0);
        if (status == 429) {
            return e.esperaPedida()
                    .map(d -> d.compareTo(PAUSA_FALHA) < 0 ? PAUSA_FALHA : d.compareTo(PAUSA_MAXIMA) > 0 ? PAUSA_MAXIMA : d)
                    .orElse(PAUSA_LIMITE);
        }
        if (status == 403) {
            return PAUSA_BLOQUEIO;
        }
        return PAUSA_FALHA;
    }

    private static String chave(EnderecoRequest req) {
        return String.join("|", Texto.normalizar(req.rua()), Texto.normalizar(req.numero()), Texto.normalizar(req.bairro()),
                Texto.normalizar(req.cidade()), req.estado() == null ? "" : req.estado().toUpperCase(Locale.ROOT));
    }

    private enum Servico {
        NOMINATIM(NominatimClient.FONTE), PHOTON(PhotonClient.FONTE);

        final String fonte;

        Servico(String fonte) {
            this.fonte = fonte;
        }
    }
}
