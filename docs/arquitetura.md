# Arquitetura do AlertaLar

## Visão geral

```
Navegador (HTML + CSS + JavaScript puro + Leaflet)
        │  fetch JSON, mesma origem, cookie de sessão + token CSRF
        ▼
Spring Boot 4 (Java 21)
  controller  →  service  →  repository  →  PostgreSQL (Flyway)
                   │
                   ├── service/analise  (fatores de risco independentes + consolidação)
                   └── integration      (clientes HTTP das fontes externas)
```

O próprio back-end serve o front-end (pasta `frontend/`), então tudo roda em
`http://localhost:8080` sem configuração de CORS.

## Camadas (pacote `com.alertalar`)

| Pacote | Responsabilidade |
|---|---|
| `controller` | Endpoints REST, validação de entrada (`@Valid`), usuário logado. |
| `service` | Regras de negócio: geocodificação (`EnderecoService`), orquestração da consulta (`ConsultaService`), coleta de dados em paralelo (`ColetaDadosService`), ocorrências e moderação, importação oficial, situação atual, usuários. |
| `service/analise` | Metodologia de risco: `FatorRisco` (interface), um fator por classe em `fatores/`, `AnaliseRiscoService` (consolidação). |
| `repository` | Spring Data JPA. |
| `entity` | Entidades JPA e enums. |
| `dto` | Entrada e saída da API (records). |
| `integration` | Clientes de ViaCEP, Nominatim, IBGE, Overpass, Open-Meteo, SGB e leitor de CSV do S2iD. `ClienteHttpExterno` centraliza tempo limite, User-Agent e tratamento de falhas. |
| `config` | Propriedades, segurança, limitador de requisições, criação do administrador. |
| `exception` | Exceções de negócio e `GlobalExceptionHandler` (Problem Details, RFC 9457). |
| `util` | Texto, UFs e cálculos geográficos. |

## Fluxo de uma consulta

1. O usuário informa o endereço; o CEP preenche o formulário (ViaCEP).
2. `GET /api/enderecos/buscar` geocodifica (Nominatim) e devolve candidatos com a precisão
   (`NUMERO`, `RUA` ou aproximada). O usuário confirma ou ajusta a posição no mapa.
3. `POST /api/consultas`:
   - `EnderecoService` resolve o ponto (precisão mínima: rua; ou posição confirmada no mapa);
   - `ColetaDadosService` consulta em paralelo (threads virtuais) Overpass e SGB, depois a
     elevação; lê do banco as ocorrências confirmadas e o histórico municipal. Cada fonte que
     falha vira "indisponível" sem derrubar as outras;
   - cada `FatorRisco` avalia o contexto; `AnaliseRiscoService` consolida;
   - a consulta, os fatores e as geometrias exibidas no mapa são gravados.
4. `GET /api/consultas/{id}` reabre o resultado; a página chama `GET /api/situacao-atual`
   separadamente (chuva e vazão, sem efeito na classificação).

## Metodologia (versão 1.0)

| Fator | Categoria | Peso | Pontuação (0–3) |
|---|---|---|---|
| Setor de risco SGB | Risco mapeado | 3 | dentro de setor alto/muito alto: 3 (força alto risco); setor ≤100 m: 2; ≤300 m: 1; nenhum: não pontua |
| Proximidade de água | Suscetibilidade | 2 | rio/canal/corpo d'água ≤150 m: 3, ≤400 m: 2, ≤1.000 m: 1; córrego/vala ≤50 m: 2, ≤150 m: 1; nada em 1.500 m: 0 |
| Elevação relativa | Suscetibilidade | 2 | desnível até a água ≤2 m: 3, ≤5 m: 2, ≤10 m: 1; ponto ≥5 m abaixo do entorno: 2, ≥2 m: 1 |
| Ocorrências próximas | Histórico | 2 | confirmadas em 500 m/30 anos: 1, 2, ≥3 (≥3 impede "baixo"); nenhuma: não pontua |
| Histórico municipal | Histórico | 0,75 | registros oficiais em 30 anos: 1–4: 1; ≥5: 2; base importada sem registros: 0; sem base: não pontua |

Média ponderada M dos fatores avaliados: M ≥ 1,9 → alto; M ≥ 1,0 → moderado; abaixo → baixo.
Exige pelo menos dois fatores avaliados, um deles geográfico; senão, "dados insuficientes".
A ausência de dados nunca é tratada como evidência de segurança.

## Segurança

- Spring Security com sessão em cookie `HttpOnly` + `SameSite=Lax` e proteção CSRF
  (`XSRF-TOKEN` em cookie, enviado no cabeçalho `X-XSRF-TOKEN`).
- Senhas com BCrypt (fator 12); a resposta da API nunca inclui o hash.
- `/api/admin/**` restrito ao papel `ADMIN`; o primeiro administrador vem de variáveis de ambiente.
- Validação de todas as entradas (Bean Validation) e limpeza de caracteres de controle.
- Consultas JPA parametrizadas (sem SQL concatenado).
- Limite de requisições por IP nas rotas que consomem APIs externas ou recebem formulários.
- Cabeçalhos: Content-Security-Policy sem scripts/estilos inline, `X-Frame-Options: DENY`,
  `Referrer-Policy`, `Permissions-Policy`.
- Erros em formato Problem Details sem stack trace; erro inesperado gera um código para suporte
  e o detalhe só vai para o log do servidor.
- O front-end insere textos vindos da API com `textContent` (nunca `innerHTML`), evitando XSS.
- Nome e contato de quem relata são visíveis apenas para administradores.
