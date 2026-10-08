# Implantação na VPS

Arquivos preparados localmente; nenhum comando deste guia é executado automaticamente na VPS.

1. Use usuário não-root, SSH por chave e atualizações automáticas. Desabilite senha SSH/login root.
2. Firewall: somente 22, 80, 443. Confira externamente as regras do Docker. Management usa 127.0.0.1:15672.
3. Instale Docker/Compose, copie .env.prod.example para .env.prod, chmod 600 e preencha senhas novas,
   SMTP, ADMIN_TOKEN e APP_DOMAIN com DNS apontando para o servidor. Nunca versione .env.prod.
4. Valide `docker compose --env-file .env.prod -f docker-compose.prod.yml config --quiet`.
5. Suba `docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --build`.
   Só Caddy publica 80/443 e obtém HTTPS automático. MySQL/RabbitMQ ficam em rede interna; a aplicação
   também tem rede de saída para SMTP. O proxy encaminha /api/** e health; métricas ficam fora do proxy.
6. Mantenha o repositório notification-console na pasta irmã do backend. O compose constrói o painel;
   Caddy encaminha a interface ao nginx do serviço console. Configure ADMIN_EMAIL/ADMIN_PASSWORD para
   criar o primeiro administrador, ADMIN_COOKIE_SECURE=true e remova a senha após o bootstrap.
   Preserve mysql_prod_data, rabbitmq_prod_data, caddy_data,
   caddy_config; confira permissões/disco. Não use down -v em produção.
7. RabbitMQ novo cria somente SPRING_RABBITMQ_USERNAME configurado. Não use guest. Em volume reutilizado,
   confira rabbitmqctl list_users e remova guest caso ainda exista. Abra o painel com
   `ssh -L 15672:127.0.0.1:15672 usuario@servidor`, depois http://localhost:15672.
8. Consulte métricas dentro da rede com X-Admin-Token. Configure backup externo e teste restauração
   antes de dados reais. Execute smoke test com destinatário controlado.

## SMTP e domínio
SES, Brevo, Mailgun ou SendGrid: configure SPRING_MAIL_HOST/PORT (587), USERNAME/PASSWORD,
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true e SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true.
MAIL_FROM deve usar domínio validado. Publique os registros exatos fornecidos pelo provedor:
- SPF: único TXT autorizando os servidores de envio; não crie dois registros SPF.
- DKIM: CNAME/TXT do provedor e aguarde validação.
- DMARC: comece p=none, examine relatórios e avance para quarantine/reject após validar remetentes.
Envie a caixa controlada e confira Authentication-Results: SPF/DKIM/DMARC. SENT significa aceitação SMTP,
não garante caixa de entrada.

## Backups e CI
Configure rclone no usuário do cron e BACKUP_REMOTE fora da VPS, cifrado e com acesso restrito.
Agende `0 2 * * * cd /caminho/notification-service && ./scripts/backup.sh`.
Execute restore-test.sh mensalmente e após mudanças. O banco temporário é removido após conferir checksum/contagens.
CI roda verify em push/PR, publica relatórios e constrói imagem na main sem publicar. A primeira execução
remota ocorre após seu commit/push; nenhum workflow remoto é disparado pelo agente.
