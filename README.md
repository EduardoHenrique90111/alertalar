# AlertaLar

Sistema web de consulta e análise de riscos de enchentes e alagamentos em imóveis e regiões.

A pessoa informa um endereço, confirma a posição no mapa e recebe duas respostas:

- **Risco agora** (baixo, atenção, alto ou muito alto): a chuva das últimas horas e a prevista
  para as próximas 24 horas, pelos limites de chuvas intensas do INMET, combinadas com a
  suscetibilidade do local e a subida de rios próximos. É recalculado cada vez que o resultado
  é aberto.
- **Suscetibilidade do local** (**baixo risco**, **risco moderado**, **alto risco** ou **dados
  insuficientes**): o quanto o endereço tende a ser atingido quando chove forte, com a explicação
  de cada fator, as fontes usadas e a data dos dados. Não muda com o tempo do dia.

A página **Rota segura** sugere um caminho a pé ou de carro, a partir da localização da pessoa
ou de um endereço, até um terreno mais alto fora das áreas de risco de inundação mapeadas, ou
até um lugar que a pessoa escolher (casa de parente, abrigo), desviando dessas áreas.

## Objetivo

Ajudar moradores, compradores e inquilinos a saber se um endereço fica em região historicamente
afetada por enchentes ou geograficamente suscetível a alagamentos, sempre separando:

- **risco mapeado**: setores de risco oficiais do Serviço Geológico do Brasil;
- **suscetibilidade geográfica**: proximidade de rios e córregos e altura relativa do terreno;
- **risco histórico**: desastres oficiais do município (inundações, enxurradas, alagamentos e
  chuvas intensas registrados no S2iD, de 1991 a 2025, já incluídos) e relatos de moradores verificados;
- **situação atual**: chuva e vazão de rios estimadas por modelos, que formam o risco agora.

O sistema nunca afirma que um local está livre de enchentes e não inventa dados: quando uma
fonte não responde ou não cobre a região, o fator aparece como indisponível e não entra na conta.

## Tecnologias

| Camada | Tecnologia |
|---|---|
| Back-end | Java 21, Spring Boot 4.1 (Spring Web MVC, Spring Data JPA, Spring Validation, Spring Security, Actuator), Maven |
| Banco de dados | PostgreSQL, Hibernate/JPA, Flyway (migrations) |
| Front-end | HTML5, CSS3, JavaScript puro (módulos ES), Leaflet 1.9.4 + OpenStreetMap |
| Testes | JUnit 5/6 (Jupiter) e Mockito |
| Integrações | ViaCEP, Nominatim (OSM), IBGE Localidades, Overpass (OSM), Open-Meteo (elevação Copernicus, chuva, vazão GloFAS), SGB (setores de risco), Valhalla (rotas sobre o OSM); base do Atlas Digital de Desastres embutida e importação de planilhas do S2iD |

Fontes tipográficas (Overpass e Public Sans, licença OFL) e Leaflet ficam dentro do projeto:
o front-end não depende de CDN.

## Requisitos

- **JDK 21** (Temurin, Oracle ou o JDK baixado pelo IntelliJ IDEA)
- **Maven 3.9+** (o IntelliJ IDEA já traz um Maven embutido)
- **PostgreSQL 14+**
- Internet: para o Maven baixar as dependências e para o sistema consultar as APIs públicas

## Instalação

### 1. PostgreSQL

Crie o usuário e o banco (uma vez):

```bash
psql -U postgres -f database/criar-banco.sql
```

Troque a senha `troque_esta_senha` no script antes de executar. As tabelas são criadas
automaticamente pelo Flyway quando o back-end inicia.

### 2. Variáveis de ambiente

```bash
cp .env.example .env
```

Edite o `.env` (ele fica fora do Git):

| Variável | Para quê |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Conexão com o PostgreSQL |
| `ALERTALAR_ADMIN_EMAIL`, `ALERTALAR_ADMIN_SENHA` | Cria o administrador (moderação e importação). Senha com 10+ caracteres, letras e números |
| `ALERTALAR_CONTATO_EMAIL` | Contato enviado às APIs públicas (pedido pela política do Nominatim) |
| `PORT` | Porta HTTP (padrão 8080) |
| `COOKIE_SECURE`, `FORWARD_HEADERS_STRATEGY` | Use `true` e `framework` quando publicar com HTTPS atrás de proxy |
| `ROTAS_URL`, `ROTAS_CHAVE_API` | Opcional. Servidor de rotas compatível com o Valhalla (padrão: servidor público da FOSSGIS, sem chave) |

O back-end lê o `.env` da raiz do projeto ou da pasta `backend`. Variáveis de ambiente do
sistema operacional têm prioridade sobre ele.

### 3. APIs externas

Nenhuma chave é necessária: todas as integrações ativas usam serviços públicos gratuitos.
A rota segura usa o servidor público do Valhalla mantido pela FOSSGIS
(`valhalla1.openstreetmap.de`), que aceita no máximo uma requisição por segundo; o AlertaLar
respeita esse intervalo e limita cada visitante a 10 rotas por minuto. Para uso intenso, aponte
`ROTAS_URL` para uma instância própria ou um provedor compatível (com `ROTAS_CHAVE_API`).
Os endereços podem ser trocados por variáveis (`NOMINATIM_URL`, `OVERPASS_URL` etc., veja o
`.env.example`), por exemplo para usar uma instância própria do Nominatim. Detalhes, limites
de uso e situação de cada fonte: [docs/fontes-de-dados.md](docs/fontes-de-dados.md).

## Como iniciar o back-end

**No IntelliJ IDEA**

1. *File → Open* e selecione a pasta `alertalar` (o IntelliJ importa o projeto Maven).
2. *File → Project Structure → SDK*: escolha Java 21.
3. Abra `backend/src/main/java/com/alertalar/AlertaLarApplication.java` e clique em *Run*.

**No terminal**

```bash
cd backend
mvn spring-boot:run
```

Para gerar o JAR executável:

```bash
cd backend
mvn package
java -jar target/alertalar.jar
```

## Como iniciar o front-end

Não é preciso um servidor separado: o back-end serve o front-end. Com a aplicação rodando,
abra **http://localhost:8080**.

Durante o desenvolvimento, os arquivos são lidos direto da pasta `frontend/` (basta recarregar
o navegador após editar). No `mvn package`, o front-end é copiado para dentro do JAR.

### Primeiros passos

1. Consulte um endereço na página inicial. Na primeira inicialização, o Flyway já carrega o
   histórico oficial de todos os municípios (veja abaixo); não é preciso importar nada.
2. Abra **Rota segura** para testar o caminho até um terreno mais alto.
3. Entre com o administrador configurado no `.env` e abra **Moderação** para validar relatos.

### Histórico oficial embutido

O arquivo `backend/src/main/resources/dados/atlas-desastres-chuva.csv.gz` traz 27.581 registros
de desastres ligados a chuva (inundações, enxurradas, alagamentos e chuvas intensas, de 1991 a
2025) de todos os municípios, extraídos do **Atlas Digital de Desastres no Brasil v1.1**
(MIDR/Sedec, 06/08/2026), que consolida os registros do S2iD. A migration Java
`V4__HistoricoOficialAtlas` grava esses registros no banco uma única vez.

As chuvas intensas (COBRADE 1.3.2.1.4) entram porque muitas enchentes são registradas com esse
tipo: as de maio de 2024 em Canoas e Porto Alegre, por exemplo.

Para incluir eventos mais novos, há dois caminhos:

- importar a planilha CSV do [S2iD](https://s2id.mi.gov.br) ou do
  [Atlas Digital](https://atlasdigital.mdr.gov.br) pela **Moderação** (registros repetidos são
  ignorados pelo número de protocolo);
- ou regenerar a base embutida a partir do CSV completo do Atlas e criar uma nova migration:

```bash
cd backend
mvn -q compile
java -cp target/classes com.alertalar.ferramentas.PrepararBaseAtlas \
  ~/Downloads/BD_Atlas_1991_2025_v1.1_2026.08.06_Consolidado.csv \
  src/main/resources/dados/atlas-desastres-chuva.csv.gz
```

Proveniência e filtro: [backend/src/main/resources/dados/LEIA-ME.md](backend/src/main/resources/dados/LEIA-ME.md).

## Testes

```bash
cd backend
mvn test
```

Há 154 testes (JUnit + Mockito): validação de endereços, geocodificação, código IBGE, fatores e
classificação de risco, risco agora (níveis de chuva do INMET e combinação com a
suscetibilidade), processamento da consulta, registro e moderação de ocorrências, importação de
planilhas, base oficial embutida, rota segura (escolha do destino, desvio de áreas de risco,
perfil de altura), tratamento de erros, segurança e comunicação HTTP com as APIs externas
(contra um servidor local que responde no mesmo formato das APIs reais).

O teste de **ponta a ponta** (`AlertaLarIntegracaoTest`) sobe a aplicação inteira com
PostgreSQL real e percorre consulta, persistência, relato, moderação, importação, situação
atual, rota segura, cadastro e login. Ele só roda quando existe a variável `ALERTALAR_TEST_DB_URL`
apontando para um banco **exclusivo de testes** (os dados dele são apagados):

```bash
# Linux/macOS
export ALERTALAR_TEST_DB_URL=jdbc:postgresql://localhost:5432/alertalar_test
export ALERTALAR_TEST_DB_USERNAME=alertalar
export ALERTALAR_TEST_DB_PASSWORD=sua_senha
mvn test
```

```powershell
# Windows (PowerShell)
$env:ALERTALAR_TEST_DB_URL="jdbc:postgresql://localhost:5432/alertalar_test"
$env:ALERTALAR_TEST_DB_USERNAME="alertalar"
$env:ALERTALAR_TEST_DB_PASSWORD="sua_senha"
mvn test
```

## Endpoints

Todas as respostas são JSON; erros seguem o formato Problem Details (RFC 9457), com mensagens
em português e, em erros de validação, o campo `erros` por campo.

| Método | Caminho | Acesso | Descrição |
|---|---|---|---|
| GET | `/api/enderecos/cep/{cep}` | público | Dados do CEP (ViaCEP) para preencher o formulário |
| GET | `/api/enderecos/buscar?rua=&numero=&bairro=&cidade=&estado=&cep=` | público | Valida e geocodifica; devolve candidatos e a precisão |
| POST | `/api/consultas` | público | Realiza a consulta de risco e grava o resultado (201) |
| GET | `/api/consultas/{id}` | público (link com UUID) | Resultado completo de uma consulta |
| GET | `/api/consultas/minhas` | logado | Histórico de consultas do usuário |
| GET | `/api/ocorrencias?estado=&municipio=&origem=&pagina=&tamanho=` | público | Ocorrências confirmadas (oficiais e relatos validados) |
| GET | `/api/ocorrencias/proximas?lat=&lon=&raio=&anos=` | público | Ocorrências confirmadas em torno de um ponto |
| POST | `/api/ocorrencias` | público | Envia relato (fica `PENDENTE`) |
| GET | `/api/fontes` | público | Fontes de dados, situação da integração e última atualização |
| GET | `/api/situacao-atual?lat=&lon=&consulta=` | público | Chuva (72 h passadas e 72 h previstas), vazão e risco agora; `consulta` (opcional, UUID) usa a suscetibilidade daquela consulta |
| GET | `/api/rotas/segura?lat=&lon=&modo=A_PE&destLat=&destLon=` | público | Caminho até o terreno mais alto fora de áreas de risco ou, com `destLat`/`destLon`, até um destino escolhido (até 20 km), desviando das áreas de risco (`modo`: `A_PE` ou `CARRO`; 10 por minuto por IP) |
| POST | `/api/auth/registro` | público | Cria conta e já entra |
| POST | `/api/auth/login` | público | Entra (sessão) |
| POST | `/api/auth/logout` | logado | Sai |
| GET | `/api/auth/me` | público | Usuário logado (204 se ninguém) |
| GET | `/api/auth/csrf` | público | Gera o cookie `XSRF-TOKEN` |
| GET | `/api/admin/resumo` | ADMIN | Quantidade de relatos pendentes |
| GET | `/api/admin/ocorrencias?status=PENDENTE` | ADMIN | Relatos para moderação (inclui contato do relator) |
| PATCH | `/api/admin/ocorrencias/{id}/validacao` | ADMIN | Corpo `{"status":"CONFIRMADA","observacao":"..."}` (ou `REJEITADA`) |
| POST | `/api/admin/importacoes/registros-oficiais` | ADMIN | Upload `multipart/form-data` (campo `arquivo`, CSV do S2iD/Atlas) |
| GET | `/actuator/health` | público | Verificação de saúde |

Requisições `POST`/`PATCH` exigem o token CSRF: leia o cookie `XSRF-TOKEN` e envie no cabeçalho
`X-XSRF-TOKEN` (o front-end faz isso em `frontend/js/api.js`). Exemplo com curl:

```bash
curl -c c.txt -b c.txt http://localhost:8080/api/auth/csrf
TOKEN=$(grep XSRF-TOKEN c.txt | awk '{print $7}')
curl -c c.txt -b c.txt -X POST http://localhost:8080/api/consultas \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $TOKEN" \
  -d '{"rua":"Rua Exemplo","numero":"100","cidade":"Canoas","estado":"RS"}'
```

## Estrutura do projeto

```
alertalar/
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/alertalar/
│       │   ├── controller/     endpoints REST
│       │   ├── service/        regras de negócio
│       │   │   └── analise/    metodologia de risco (fatores independentes)
│       │   ├── repository/     Spring Data JPA
│       │   ├── entity/         entidades e enums
│       │   ├── dto/            entrada e saída da API
│       │   ├── config/         segurança, propriedades, limites
│       │   ├── exception/      exceções e tratamento global
│       │   ├── integration/    clientes das APIs externas e leitor do S2iD
│       │   └── util/           texto, UFs e geografia
│       ├── main/resources/
│       │   ├── application.properties
│       │   └── db/migration/   migrations Flyway
│       └── test/java/          testes JUnit + Mockito
├── frontend/
│   ├── index.html, resultado.html, ocorrencias.html, relatar.html,
│   │   entrar.html, painel.html, metodologia.html
│   ├── css/estilo.css
│   ├── js/                     api.js, ui.js, mapa.js e scripts de cada página
│   └── assets/                 fontes, ícone e Leaflet
├── database/                   script de criação do banco e modelo
├── docs/                       arquitetura e fontes de dados
├── .env.example
├── .gitignore
├── pom.xml                     agregador (abre o projeto inteiro no IntelliJ)
└── README.md
```

## Publicação na internet

O GitHub guarda o código, mas não executa Java nem PostgreSQL. Dois caminhos gratuitos, os dois
com o banco no **Neon** (PostgreSQL):

- **Render**, sem cartão: o arquivo `render.yaml` cria o serviço (*New → Blueprint*) e só pede a
  connection string do Neon (`DATABASE_URL`). O site dorme após 15 minutos sem visitas e leva de
  1 a 3 minutos para acordar. Passo a passo: [docs/publicar-no-render.md](docs/publicar-no-render.md).
- **Google Cloud Run**, com cartão (sem cobrança dentro do nível gratuito): liga em segundos e tem
  bem mais CPU grátis. Passo a passo: [docs/publicar-no-google-cloud.md](docs/publicar-no-google-cloud.md).

Resumo do Cloud Run:

1. **Neon** (neon.com): crie um projeto e copie a *connection string*
   (`postgresql://USUARIO:SENHA@HOST/BANCO?sslmode=require`).
2. **Google Cloud** (console.cloud.google.com): crie um projeto com faturamento ativo, abra o
   **Cloud Shell**, envie o projeto (ou clone o repositório) e rode:

   ```bash
   bash implantar-cloud-run.sh
   ```

   O arquivo ativa os serviços, converte a connection string do Neon para as variáveis
   `DB_URL`, `DB_USERNAME` e `DB_PASSWORD`, escolhe a região mais perto do banco, publica com no
   máximo 2 instâncias e oferece um alerta de gastos por e-mail. No fim, mostra o endereço
   público (`https://alertalar-....run.app`). Para publicar uma versão nova, rode de novo.

Variáveis usadas em produção:

| Variável | Valor |
|---|---|
| `DATABASE_URL` | connection string do Neon (`postgresql://USUARIO:SENHA@HOST/BANCO?sslmode=require`), convertida sozinha para as três abaixo |
| `DB_URL` | `jdbc:postgresql://HOST/BANCO?sslmode=require` (se definida, vale mais que `DATABASE_URL`) |
| `DB_USERNAME` / `DB_PASSWORD` | usuário e senha do banco |
| `ALERTALAR_ADMIN_EMAIL` / `ALERTALAR_ADMIN_SENHA` | administrador (só para criar) |
| `ALERTALAR_CONTATO_EMAIL` | e-mail de contato para as APIs públicas |
| `COOKIE_SECURE` | `true` |
| `FORWARD_HEADERS_STRATEGY` | `framework` |

Observações:
- O nível gratuito do Cloud Run inclui 180 mil segundos de CPU, 360 mil GiB-segundos de
  memória e 2 milhões de requisições por mês, e só cobra CPU enquanto atende requisições.
  O plano gratuito do Vercel (4 horas de CPU por mês) não comporta bem uma aplicação Java, que
  gasta bastante CPU a cada vez que liga. Planos e limites mudam: confira nos sites.
- Com tráfego alto, troque o Nominatim público e os blocos de mapa do OpenStreetMap por
  serviços próprios ou comerciais: as políticas de uso das instâncias públicas proíbem uso intenso.
- O limitador de requisições é em memória; com várias instâncias, use um limitador distribuído.
- Para rodar o JAR em qualquer servidor: `java -jar alertalar.jar` com Java 21 e as mesmas variáveis.

## Limitações conhecidas

- A classificação resume indícios nos dados disponíveis: não é laudo técnico, estudo
  hidrológico nem previsão meteorológica.
- Modelo de elevação de 90 m (Copernicus GLO-90): erro vertical de alguns metros e pode incluir
  construções e vegetação.
- O mapeamento de rios e córregos do OpenStreetMap varia por região; córregos canalizados podem
  não aparecer.
- O SGB mapeou setores de risco em parte dos municípios; a ausência de setor não é tratada como
  segurança.
- O risco agora usa chuva estimada por modelos (Open-Meteo), que erram a chuva de um bairro,
  principalmente em temporais isolados. Não é alerta oficial.
- O código IBGE do município vem do CEP (ViaCEP), de uma lista guardada no sistema (5.256
  municípios do Atlas Digital) ou da API do IBGE, nessa ordem. Se o nome da cidade estiver
  muito diferente do oficial, o histórico municipal fica indisponível.
- Registros do S2iD são municipais (não indicam a rua). A base embutida vai até 2025; eventos
  mais novos dependem de importar a planilha, pois não há API pública documentada. A importação
  aceita CSV (não XLSX). A ausência de registro não significa que o município nunca teve enchente.
- A rota segura não é rota oficial de evacuação: não sabe quais ruas estão alagadas ou
  bloqueadas, a altura do terreno tem erro de alguns metros e o desvio cobre só os setores
  mapeados pelo SGB. O servidor público de rotas pode recusar pedidos em horários de muito uso.
- CEMADEN, INMET e ANA ainda não estão integrados (acesso exige credencial ou não há API
  documentada); estão registrados como fontes planejadas.
- O índice da camada do SGB (`/MapServer/0`) segue o serviço público atual; se o SGB mudar o
  serviço, ajuste `SGB_SETORES_RISCO_URL`.
- Vazão do GloFAS tem resolução de ~5 km e pode não representar rios pequenos.
- O link de um resultado é público para quem o tiver (identificador UUID não sequencial).
