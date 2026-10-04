# Notification Service

Serviço de notificações desacoplado e reutilizável. Outros sistemas enviam um body padrão
(destinatário, canal, template, variáveis) e o serviço cuida de validar, agendar, renderizar,
enviar, tentar de novo e guardar o histórico.

## Stack
Java 21, Spring Boot 4.1.x (Maven), MySQL 8.4, Flyway, Spring Data JPA, Spring Mail, Lombok.
Local: Docker Compose com MySQL e Mailpit. Mais tarde: RabbitMQ.

## Arquitetura
Monólito modular. Pacote base: dev.ricardo.notification
- api: controllers, DTOs, tratamento de erros
- application: casos de uso
- domain: enums, interfaces, regras (não depende de Spring Web nem de JPA)
- infrastructure: email, persistence, messaging
- template: renderização de {{variavel}}
- security: autenticação por API Key
Dependências: api -> application -> domain <- infrastructure.

## Regras permanentes
1. O esquema do banco está em docs/banco.md. Siga-o. Se achar que algo está errado, pergunte antes de mudar.
2. Migrations Flyway são imutáveis. Nunca edite uma migration já criada; crie a próxima numeração.
   spring.jpa.hibernate.ddl-auto fica em validate.
3. Datas em UTC (Instant no Java, DATETIME(6) no banco). UUID guardado em CHAR(36).
4. Configuração por variáveis de ambiente. Nenhum segredo no código ou no repositório. .env fica no .gitignore.
5. Não adicione dependências sem me avisar e justificar. Fora da lista da etapa, pergunte.
6. Identificadores, rotas e mensagens de erro da API em inglês. Comentários e documentação em português.
7. Testes: unitários para regras de negócio; integração com Testcontainers MySQL 8.4. Não use H2.
8. Não faça I/O de rede (envio de e-mail, publicação em fila) dentro de uma transação de banco aberta.
9. Erros da API no formato ProblemDetail (RFC 9457). Nunca devolva stack trace ou detalhes internos.
10. Spring Boot 4 usa Jackson 3 (pacote tools.jackson para o databind). Confira os imports.

## Como trabalhar
- Para tarefas com mais de 3 arquivos, apresente um plano curto (arquivos a criar/alterar) e espere minha confirmação.
- Faça só o que a etapa pede. Nada de "já aproveitei e...". Se notar algo fora do escopo, anote no final.
- Rode os testes ao terminar (./mvnw test). Não diga que está pronto se algum teste falhar.
- Não faça commit. Eu reviso e commito.
- Ao final, resuma em até 10 linhas: o que mudou, decisões tomadas e o que devo revisar com atenção.
