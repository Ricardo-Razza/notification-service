# Estado do projeto — 8 de outubro de 2026

O roteiro da parte 2 está implementado localmente no backend e no repositório irmão notification-console.
Consulte execucao-parte-2.md para etapas e evidências; runbook.md e deploy.md para operação.

## Auditoria final

| Área revisada | Evidência e decisão |
|---|---|
| Segurança administrativa | AdminSecurityConfig:32–46 usa cookie CSRF, roles e revalidação de usuários; sessão HttpOnly/SameSite Strict; secure obrigatório na produção |
| Login | AdminUserService:26–48 usa BCrypt, resposta genérica, dummy hash, contador/lock persistidos e lock de linha; bloqueio após cinco falhas |
| Chaves | SHA-256 persistido, comparação constante, chave anterior expira; segredo entregue somente na criação/rotação |
| Entrega concorrente | NotificationConsumer:99–101 faz claim condicional; :175/:207 verificam estado/attempts antes de finalizar; SMTP fora de transação |
| Publicação | NotificationService:196–206 publica após commit e preserva estados concorrentes; publisher confirma mensagem e verifica retorno |
| Recuperação | NotificationReconciler usa SKIP LOCKED, corte por idade, timestamp e limite de recuperações; republica após commit |
| Isolamento | Templates próprios sobrepõem globais; API pública impede alterar globais e acessar recursos de outro sistema |
| Entradas | Corpo limitado, variáveis/conteúdo/lote/agendamentos limitados; cron validado; idempotência canônica compara conteúdo |
| Dados e logs | Sem chave/senha/variáveis nos logs; requestId/applicationId/notificationId/origin em MDC; destinatários mascarados para VIEWER; retenção remove/anomiza estados finais |
| Infraestrutura | Caddy expõe somente 80/443; management Rabbit em loopback; MySQL sem porta pública; imagens não-root e .dockerignore |
| Painel | Cookies/CSRF nativos, nenhuma credencial em storage, conteúdo por interpolação, chaves locais ao diálogo, guards e confirmação |
| Qualidade | 3×175 testes backend; 29 unitários frontend; fluxo real e axe/CSP em produção local; backup/restore aprovado |

Não foram identificados bloqueios para a validação local. A publicação ainda exige configuração do ambiente.

## Limites operacionais

- Entrega é at-least-once: falha depois da aceitação SMTP e antes do commit pode duplicar o e-mail.
- Rate limiter e sessão ficam na memória de uma instância. Escala horizontal exige limitação/sessão compartilhadas.
- Retenção conta a idade desde created_at; só afeta estados finais. Idempotência anterior sem request_hash mantém
  a compatibilidade, sem validar retrospectivamente o conteúdo original.
- GENERATE_ALL pode produzir muitos envios após interrupção longa; use SKIP_AND_GENERATE_LATEST,
  o padrão da produção, e limite cron/quantidade de agendamentos ao dimensionar recuperação.
- Auditoria é append-only pela aplicação; acesso direto privilegiado ao banco continua sendo responsabilidade operacional.
- Notificações administrativas são auditadas após a operação compartilhada: falha da auditoria não desfaz um envio
  que já foi persistido/publicado. Monitore erros e confira o histórico antes de repetir um pedido.
- Chaves anteriores têm uma única janela por aplicação; uma segunda rotação substitui a anterior.
- Alterar TTL de filas exige drenagem/migração conforme runbook; RabbitMQ não altera argumentos de filas existentes.
- Bootstrap não redefine senha de usuário existente. Remova ADMIN_PASSWORD do ambiente depois de criar o usuário.
