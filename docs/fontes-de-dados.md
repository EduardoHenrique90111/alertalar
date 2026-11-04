# Fontes de dados do AlertaLar

Levantamento feito em novembro de 2026. A situação de cada fonte também fica registrada na
tabela `fonte_dados` e aparece em `GET /api/fontes` e na página "Como analisamos".

## Integradas (consultadas pelo sistema)

| Fonte | Uso no AlertaLar | Acesso | Condições de uso |
|---|---|---|---|
| **ViaCEP** (`viacep.com.br/ws/{cep}/json/`) | Preenche rua, bairro, cidade, UF e código IBGE a partir do CEP. Não fornece coordenadas, por isso o CEP sozinho nunca é usado na análise. | Gratuito, sem chave | Uso moderado; resultados em cache por 24 h. |
| **Nominatim / OpenStreetMap** (`nominatim.openstreetmap.org/search`) | Geocodificação (endereço → latitude/longitude) com nível de precisão. | Gratuito, sem chave | Política: no máximo 1 requisição/s, User-Agent identificável, cache obrigatório, sem geocodificação em massa, atribuição ao OSM. O AlertaLar impõe intervalo mínimo de 1,1 s, envia `AlertaLar/1.0 (contato)` e guarda endereços na tabela `endereco`. Para uso intenso, use uma instância própria ou um provedor comercial (configurável por `NOMINATIM_URL`). |
| **IBGE – API de Localidades** (`servicodados.ibge.gov.br/api/v1/localidades`) | Código IBGE do município (cruzamento com registros oficiais). | Gratuito, oficial | Cache por 24 h por UF. |
| **Overpass API / OpenStreetMap** (`overpass-api.de/api/interpreter`) | Rios, córregos, canais, valas e corpos d'água num raio de 1.500 m, com geometria. | Gratuito | Uso justo (instância pública compartilhada); cache de 6 h por ponto. Dados ODbL, atribuição obrigatória. |
| **Copernicus DEM GLO-90 via Open-Meteo Elevation API** (`api.open-meteo.com/v1/elevation`) | Elevação do ponto, do curso d'água mais próximo e de 8 pontos do entorno (uma requisição). | Gratuito para uso não comercial; até 100 coordenadas por chamada | Atribuição ao programa Copernicus e à Open-Meteo. Uso comercial exige chave. |
| **Serviço Geológico do Brasil – Setorização de Riscos** (ArcGIS REST: `geoportal.sgb.gov.br/server/rest/services/gestaoterritorial/risco/MapServer/0/query`) | Setores de risco alto e muito alto; somente os hidrológicos (inundação, enxurrada, alagamento) pontuam. | Público, oficial | Campos usados: `uf`, `munic`, `num_setor`, `local`, `tipolo_g1..5`, `tipolo_e1..5`, `cobrade_01..05`, `descricao`, `grau_risco`, `data_setor`. O índice da camada (`/0`) é configurável por `SGB_SETORES_RISCO_URL`; se o SGB reorganizar o serviço, ajuste essa variável. O SGB mapeou apenas parte dos municípios. |
| **Open-Meteo Forecast API** (`api.open-meteo.com/v1/forecast`) | Situação atual: chuva das últimas 24 h e prevista (24 h, 72 h). | Gratuito para uso não comercial | Dados CC BY 4.0. Não altera a classificação. |
| **GloFAS via Open-Meteo Flood API** (`flood-api.open-meteo.com/v1/flood`) | Situação atual: vazão diária simulada do rio mais próximo (grade de ~5 km). | Gratuito para uso não comercial | Pode não representar rios pequenos. Não altera a classificação. |

## Importação manual (dados oficiais sem API documentada)

| Fonte | Como usar |
|---|---|
| **S2iD / Atlas Digital de Desastres no Brasil** (MIDR – Defesa Civil Nacional) | Registros oficiais de desastres por município. Não há API pública documentada, então o administrador baixa a planilha em `s2id.mi.gov.br` ou `atlasdigital.mdr.gov.br`, salva como CSV e envia pelo painel de moderação (`POST /api/admin/importacoes/registros-oficiais`). O leitor reconhece variações de colunas (`UF`/`Sigla_UF`, `Município`/`Nome_Municipio`, `Cod_IBGE_Mun`, `Data do Evento`/`Ano`, `COBRADE`/`Desastre`, `Protocolo`) e só importa COBRADE 1.2.1 (inundação), 1.2.2 (enxurrada) e 1.2.3 (alagamento). Registros repetidos são ignorados pelo protocolo. |

O sistema **não vem com dados de desastres pré-carregados**: nada é apresentado como oficial
sem que a planilha oficial tenha sido importada.

## Não integradas (estrutura preparada, sem simulação)

| Fonte | Motivo |
|---|---|
| **CEMADEN** | O acesso programático aos dados de pluviômetros exige cadastro/credencial. Foi encontrado um serviço ArcGIS no geoportal do SGB com estações do CEMADEN, mas ele está identificado como base de teste, por isso não foi usado. |
| **INMET** | Não foi encontrada documentação pública estável da API de avisos meteorológicos. |
| **ANA – HidroWebService** | Exige cadastro e credenciais (há manual público). Previsto para níveis de rios em tempo real. |

Para integrar uma delas: crie um cliente em `integration/`, um fator em
`service/analise/fatores/` (ou um bloco em `SituacaoAtualService`, se for dado em tempo real)
e altere o `status_integracao` da fonte com uma nova migration.
