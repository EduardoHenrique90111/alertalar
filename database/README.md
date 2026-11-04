# Banco de dados do AlertaLar

- **SGBD:** PostgreSQL 14 ou superior (testado com PostgreSQL 16).
- **Criação do banco e do usuário:** `criar-banco.sql` (execute uma vez).
- **Estrutura (tabelas, índices, restrições):** versionada com **Flyway** em
  `backend/src/main/resources/db/migration`. O back-end aplica as migrations sozinho ao iniciar.
  Para mudar a estrutura, crie um novo arquivo `V3__descricao.sql` (nunca altere uma migration já aplicada).

## Modelo

```
usuario 1───* consulta *───1 endereco
                 │
                 └──* consulta_fator *───1 fonte_dados
ocorrencia *───1 fonte_dados
ocorrencia *───0..1 endereco
ocorrencia *───0..1 usuario   (quem relatou / quem validou)
```

| Tabela | Conteúdo |
|---|---|
| `fonte_dados` | Fontes usadas (APIs, bases geográficas, importações oficiais, relatos), situação da integração e data da última atualização. |
| `usuario` | Contas; senha guardada somente como hash BCrypt; papel `USUARIO` ou `ADMIN`. |
| `endereco` | Endereços geocodificados (latitude/longitude, precisão). Também é o cache da geocodificação. |
| `consulta` | Cada análise: classificação, índice, justificativa, versão da metodologia e geometrias exibidas no mapa. Identificador UUID. |
| `consulta_fator` | Resultado de cada fator da análise (pontuação, peso, valor observado, explicação, fonte, data). |
| `ocorrencia` | Registros oficiais e relatos de usuários, com origem (`OFICIAL`/`RELATO_USUARIO`), status de validação (`PENDENTE`/`CONFIRMADA`/`REJEITADA`) e nível de localização (`PONTO`/`MUNICIPIO`). |

Consultas ao banco são feitas por Spring Data JPA com parâmetros vinculados (sem concatenação de SQL), o que impede SQL Injection.
