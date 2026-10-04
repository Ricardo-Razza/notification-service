# Etapa 1: Build da aplicação com Maven
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Copia arquivos de dependência para aproveitar o cache de camadas do Docker
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw
RUN ./mvnw dependency:go-offline -B

# Copia o código-fonte e gera o pacote compilado (pulando os testes que rodam no CI/CD)
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# Etapa 2: Imagem final de execução com JRE enxuto
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Instalação do curl para verificação de healthcheck do container
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Criação de usuário e grupo não-root para segurança
RUN groupadd -r appgroup && useradd -r -g appgroup -u 1001 appuser

# Copia o artefato da etapa de build
COPY --from=build /app/target/*.jar /app/app.jar
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]
