# Envio real por no-reply@razzadev.io

O projeto foi preparado para autenticar no Resend via SMTP/STARTTLS em 587.
O arquivo .env.smtp local tem host, porta, usuário e remetente preenchidos; falta a chave.
O envio atual continua no Mailpit até o domínio ser verificado e o override SMTP ser ativado.

## Configurar a conta e o domínio

1. Entre em https://resend.com e abra https://resend.com/domains.
2. Adicione razzadev.io com a capacidade de envio habilitada. Recebimento não é necessário.
3. Copie os registros exatos mostrados em Records para a zona DNS do domínio.
   O provedor gera os valores DKIM/SPF por domínio/região; não há valores genéricos para preencher.
   Os registros podem ser TXT, MX ou CNAME, conforme a conta. Use exatamente o host indicado.
4. Se o DNS for administrado pela Hostinger, abra Domínios → razzadev.io → DNS/Nameservers.
   A consulta pública de 8/10/2026 retornou athena.dns-parking.com e apollo.dns-parking.com.
5. Clique Verify DNS Records no Resend e aguarde o status Verified para envio.
6. Gere uma API key autorizada a enviar nesse domínio. Cole-a somente no valor
   SPRING_MAIL_PASSWORD de .env.smtp. Não compartilhe a chave no chat e não faça commit desse arquivo.

Não é necessário criar uma caixa de entrada no-reply no Resend: após autorizar o domínio,
o SMTP pode usar esse endereço como From. Os registros A/AAAA/CNAME que hospedam os sites
não precisam ser substituídos para autorizar o envio. Se houver um registro no mesmo host
de um dos registros gerados, revise a configuração existente antes de substituí-lo.

## Ativar no ambiente local

Depois da verificação e da chave preenchida:

```powershell
$env:LAN_BIND_IP='192.168.2.100'
docker compose --env-file .env --env-file .env.smtp -f docker-compose.smoke.yml -f docker-compose.lan.yml -f docker-compose.smtp.yml up -d --wait
```

A interface permanece em http://192.168.2.100:14201. Crie um novo envio para um endereço
controlado e confira o recebimento e o histórico no provedor. Mensagens antigas aceitas
pelo Mailpit não são entregues externamente de forma retroativa.

Referências:
- https://resend.com/docs/dashboard/domains/introduction
- https://resend.com/docs/dashboard/domains/manage-domains
- https://resend.com/docs/send-with-smtp
- https://support.hostinger.com/en/articles/1583249-how-to-manage-dns-records-at-hostinger
