# Execução do roteiro — Parte 2

Referência: prompts-agente-cli-parte-2.md, prompts 16–32 e F0–F11.
As alterações que já existiam na árvore de trabalho foram preservadas. Nenhum commit ou operação na VPS.

| Etapa | Resultado local |
|---|---|
| 16 | Configuração SMTP, health, métricas protegidas, filas neutras, prefetch e shutdown |
| 17 | Templates por aplicação/global, migração V6, isolamento e precedência |
| 18–19 | Claim atômico, classificação de falhas, retries e reconciliador recuperável |
| 20–21 | Limites, rate limiter, cron mínimo, idempotência estrita e envio em lote |
| 22–23 | Rotação com carência e retenção/anomização em lotes |
| 24–25 | Correlação/MDC, logs JSON, runbook e OpenAPI por grupos |
| 26–28 | CI, arquivos de produção, backup/restore/smoke e auditoria |
| 29–32 | Sessão/CSRF/roles, usuários/auditoria, consultas globais, operações e exportação OpenAPI |
| F0–F3 | Repositório irmão Angular, contrato gerado, autenticação, shell e componentes |
| F4–F9 | Dashboard, notificações, templates, aplicações, agendamentos, envio e auditoria |
| F10–F11 | Testes, axe, CSP, budgets, imagem estática e integração com Caddy/Compose |

## Verificação

Backend: três execuções completas em checkout isolado, cada uma com 175 testes, zero falhas/erros,
BUILD SUCCESS. A cópia isolada evita bloqueio de limpeza de target pelo OneDrive.
Testcontainers usa MySQL/RabbitMQ reais; não foi introduzido H2.
Logs em C:/Users/razza/notification-part2-validation/verification-{1,2,3}.log.

Painel: 29 testes unitários, lint e build aprovados. Testes Playwright contra a imagem de produção,
backend real e Mailpit passaram; axe sem violações graves nas telas principais em 360/768/1280.
A imagem usa nginx não-root, nonce CSP por resposta e cache separado para arquivos/index.
npm audit retornou zero vulnerabilidades.

Docker local: imagens backend e painel construídas; stack smoke saudável; SENT capturado no Mailpit;
backup com checksum e restauração em banco temporário executados, contagens verificadas.
Compose de produção validado. A integração usa cookies/CSRF do mesmo domínio.

## O que depende do ambiente externo

A VPS não foi acessada. HTTPS público, credenciais SMTP reais, DNS/SPF/DKIM/DMARC e envio externo
precisam ser configurados conforme deploy.md. O upload rclone externo e o cron de backup não foram
executados localmente. GitHub Actions foi preparado e não disparado: não houve commit/push.
A leitura por leitor de tela real não foi executada; foram usados axe, harnesses e revisão visual.

Decisões: gráfico SVG com tabela equivalente; presets cron sem biblioteca; gerador OpenAPI isolado
em TypeScript 5 por compatibilidade com Angular/TypeScript 6; VIEWER usa prévia local para preservar
a regra da API de que somente ADMIN executa POST.
